package lu.bourse.engine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Component
class AlpacaClient {
    private final RestClient http;
    private final String key;
    private final String secret;

    AlpacaClient(@Value("${ALPACA_API_KEY_ID:}") String key,
                 @Value("${ALPACA_API_SECRET_KEY:}") String secret) {
        this.key = key;
        this.secret = secret;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    boolean configured() {
        return !key.isBlank() && !secret.isBlank();
    }

    JsonNode calendar(LocalDate from, LocalDate to) {
        return get(UriComponentsBuilder.fromUriString("https://paper-api.alpaca.markets/v2/calendar")
                .queryParam("start", from).queryParam("end", to));
    }

    JsonNode bars(List<String> symbols, Instant start, Instant end, String cursor) {
        var url = UriComponentsBuilder.fromUriString("https://data.alpaca.markets/v2/stocks/bars")
                .queryParam("symbols", String.join(",", symbols)).queryParam("timeframe", "15Min")
                .queryParam("feed", "sip").queryParam("adjustment", "raw").queryParam("limit", 10000)
                .queryParam("start", start).queryParam("end", end);
        if (cursor != null) url.queryParam("page_token", cursor);
        return get(url);
    }

    private JsonNode get(UriComponentsBuilder url) {
        try {
            var body = http.get().uri(url.build().encode().toUri())
                    .header("APCA-API-KEY-ID", key).header("APCA-API-SECRET-KEY", secret)
                    .retrieve().body(JsonNode.class);
            if (body == null) throw new IllegalStateException("ALPACA_EMPTY_RESPONSE");
            return body;
        } catch (RestClientResponseException error) {
            throw new IllegalStateException("ALPACA_HTTP_" + error.getStatusCode().value());
        } catch (RuntimeException error) {
            throw new IllegalStateException("ALPACA_UNAVAILABLE");
        }
    }
}
