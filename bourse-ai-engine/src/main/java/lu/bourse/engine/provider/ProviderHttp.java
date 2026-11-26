package lu.bourse.engine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lu.bourse.engine.provider.quota.ProviderQuotaService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

@Component
public class ProviderHttp {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final ProviderQuotaService quotas;

    public ProviderHttp(ObjectMapper mapper, ProviderQuotaService quotas) {
        HttpClient transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.client = RestClient.builder().requestFactory(factory).build();
        this.mapper = mapper;
        this.quotas = quotas;
    }

    public JsonNode get(ProviderOperation operation, String url, String userAgent) {
        Objects.requireNonNull(operation, "A named provider operation is required");
        if (operation.quotaProvider() != null) quotas.reserve(operation.quotaProvider());
        try {
            var response = client.get().uri(URI.create(url)).headers(headers -> {
                if (userAgent != null && !userAgent.isBlank()) headers.set(HttpHeaders.USER_AGENT, userAgent);
            }).retrieve().toEntity(String.class);
            if (!response.getStatusCode().is2xxSuccessful()) throw unavailable();
            String body = response.getBody();
            if (body == null || body.isBlank()) throw unavailable();
            JsonNode json = mapper.readTree(body);
            if (json.isObject() && json.path("code").asInt() == 429) {
                pause(operation);
                throw limited();
            }
            if (json.isObject() && ("error".equalsIgnoreCase(json.path("status").asText())
                    || json.has("Error Message") || json.has("error"))) throw unavailable();
            return json;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 429) {
                pause(operation);
                throw limited();
            }
            throw unavailable();
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            // Never propagate URLs, keys, remote bodies or exception causes into logs/jobs/responses.
            throw unavailable();
        }
    }

    private void pause(ProviderOperation operation) {
        if (operation.quotaProvider() != null) quotas.pauseAfterUpstreamLimit(operation.quotaProvider());
    }

    private static ResponseStatusException limited() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Provider reported a usage limit; no automatic retry");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider unavailable, access denied or invalid response");
    }
}
