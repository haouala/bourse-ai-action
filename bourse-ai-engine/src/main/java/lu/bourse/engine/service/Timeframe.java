package lu.bourse.engine.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/** A period describes a candle, never a promise about feed latency. */
public enum Timeframe {
    M1("1m", "1min", 60), M15("15m", "15min", 900),
    M30("30m", "30min", 1800), H1("1h", "1h", 3600), D1("1d", "1day", 86400);
    private final String code;
    private final String twelveCode;
    private final Duration duration;
    Timeframe(String code, String twelveCode, long seconds) {
        this.code = code; this.twelveCode = twelveCode; this.duration = Duration.ofSeconds(seconds);
    }
    public String code() { return code; }
    public String twelveCode() { return twelveCode; }
    public Duration duration() { return duration; }
    public Instant closeTime(Instant openTime) { return openTime.plus(duration); }
    public boolean isClosed(Instant openTime, Instant now) { return !closeTime(openTime).isAfter(now); }
    public static Timeframe parse(String code) {
        return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported timeframe"));
    }
}
