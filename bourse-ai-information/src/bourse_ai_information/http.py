"""Bounded public fetcher: operator allowlist, pinned public DNS, no cookies or proxy.

Redirects are revalidated, including DNS. Secrets never go to public content sites.
"""
import http.client
import ipaddress
import socket
import ssl
from dataclasses import dataclass
from urllib.parse import urlsplit, urljoin


class FetchError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


@dataclass(frozen=True)
class Response:
    body: bytes
    content_type: str
    url: str


def public_target(url, allowed_hosts, resolver=socket.getaddrinfo):
    try:
        u = urlsplit(url)
        host = (u.hostname or "").lower()
        if u.scheme != "https" or u.username or u.password or u.port not in (None, 443) or host not in allowed_hosts or len(url) > 1500:
            raise ValueError()
        addresses = {row[4][0] for row in resolver(host, 443, type=socket.SOCK_STREAM)}
        if not addresses or any(not ipaddress.ip_address(a).is_global for a in addresses):
            raise ValueError()
        return host, sorted(addresses)[0]
    except (ValueError, OSError):
        raise FetchError("UNSAFE_OR_UNAPPROVED_URL") from None


class PublicFetcher:
    def __init__(self, allowed_hosts, timeout=20):
        self.allowed_hosts = frozenset(h.lower() for h in allowed_hosts)
        self.timeout = timeout

    def get(self, url, max_bytes=2_000_000):
        for _ in range(4):
            host, address = public_target(url, self.allowed_hosts)
            connection = http.client.HTTPSConnection(host, timeout=self.timeout, context=ssl.create_default_context())
            # Pin the validated address; HTTPS still verifies the original hostname.
            connection._create_connection = lambda target, timeout, source_address: socket.create_connection((address, 443), timeout, source_address)
            try:
                u = urlsplit(url)
                connection.request("GET", (u.path or "/") + ("?" + u.query if u.query else ""), headers={"User-Agent": "BourseAI/0.3 (+public information reader)", "Accept-Encoding": "identity"})
                response = connection.getresponse()
                if response.status in (301, 302, 303, 307, 308):
                    url = urljoin(url, response.getheader("Location", ""))
                    continue
                if response.status != 200:
                    raise FetchError("HTTP_" + str(response.status))
                if response.getheader("Content-Encoding", "identity").lower() != "identity":
                    raise FetchError("UNSUPPORTED_ENCODING")
                body = response.read(max_bytes + 1)
                if len(body) > max_bytes:
                    raise FetchError("CONTENT_TOO_LARGE")
                return Response(body, response.getheader("Content-Type", ""), url)
            except FetchError:
                raise
            except (OSError, http.client.HTTPException):
                raise FetchError("CONNECTION_FAILED") from None
            finally:
                connection.close()
        raise FetchError("TOO_MANY_REDIRECTS")
