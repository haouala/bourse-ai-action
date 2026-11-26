package lu.bourse.engine.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Descriptive, versioned OHLC rules. Never adds score points or changes ML inputs. */
public final class CandlestickPatterns {
    private CandlestickPatterns() {}
    public record Bar(Instant openTime, Instant closeTime, BigDecimal open, BigDecimal high,
                      BigDecimal low, BigDecimal close, BigDecimal volume, String provider,
                      boolean followsPrevious) {}
    public record Pattern(String code, Instant openTime, Instant closeTime, String direction,
                          String confirmation, Instant confirmationAt, BigDecimal volumeRatio,
                          String volumeStatus) {}
    public record Report(String method, String status, int inspectedCandles, List<Pattern> patterns) {}
    private static final BigDecimal TENTH = new BigDecimal("0.10");
    private static final BigDecimal THIRD = new BigDecimal("0.33");

    /** Input contains only closed bars, in ascending order. followsPrevious verifies market continuity. */
    public static Report analyze(List<Bar> bars) {
        var patterns = new ArrayList<Pattern>();
        int from = Math.max(0, bars.size() - 12);
        for (int i = from; i < bars.size(); i++) {
            Bar b = bars.get(i);
            if (!valid(b)) continue;
            BigDecimal range = b.high().subtract(b.low());
            BigDecimal body = b.close().subtract(b.open()).abs();
            if (range.signum() == 0) continue; // Flat / no-trade bars are not dojis.
            if (body.compareTo(range.multiply(TENTH)) <= 0) {
                patterns.add(pattern("DOJI", "NEUTRAL", bars, i));
                continue;
            }
            int trend = priorTrend(bars, i);
            BigDecimal lower = b.open().min(b.close()).subtract(b.low());
            BigDecimal upper = b.high().subtract(b.open().max(b.close()));
            if (trend < 0 && body.compareTo(range.multiply(THIRD)) <= 0
                    && lower.compareTo(body.multiply(BigDecimal.valueOf(2))) >= 0
                    && upper.compareTo(body.multiply(new BigDecimal("0.5"))) <= 0) {
                patterns.add(pattern("HAMMER", "BULLISH", bars, i));
            }
            if (i == 0 || !sameSeries(bars, i - 1, i)) continue;
            Bar p = bars.get(i - 1);
            BigDecimal previousBody = p.close().subtract(p.open()).abs();
            // A previous doji is not the opposite directional body required for an engulfing.
            if (previousBody.compareTo(p.high().subtract(p.low()).multiply(TENTH)) <= 0) continue;
            if (trend < 0 && p.close().compareTo(p.open()) < 0 && b.close().compareTo(b.open()) > 0
                    && b.open().compareTo(p.close()) <= 0 && b.close().compareTo(p.open()) >= 0
                    && body.compareTo(previousBody) > 0) {
                patterns.add(pattern("BULLISH_ENGULFING", "BULLISH", bars, i));
            }
            if (trend > 0 && p.close().compareTo(p.open()) > 0 && b.close().compareTo(b.open()) < 0
                    && b.open().compareTo(p.close()) >= 0 && b.close().compareTo(p.open()) <= 0
                    && body.compareTo(previousBody) > 0) {
                patterns.add(pattern("BEARISH_ENGULFING", "BEARISH", bars, i));
            }
        }
        return new Report("OHLC_PATTERNS_V1", bars.isEmpty() ? "NO_DATA" : "AVAILABLE",
                bars.size() - from, List.copyOf(patterns));
    }

    /** Four prior closes, three strictly monotone moves; pattern candle excluded. */
    private static int priorTrend(List<Bar> bars, int i) {
        if (i < 4 || !sameSeries(bars, i - 4, i)) return 0;
        boolean down = true, up = true;
        for (int j = i - 3; j < i; j++) {
            int move = bars.get(j).close().compareTo(bars.get(j - 1).close());
            down &= move < 0;
            up &= move > 0;
        }
        return down ? -1 : up ? 1 : 0;
    }

    private static Pattern pattern(String code, String direction, List<Bar> bars, int i) {
        Bar b = bars.get(i);
        String confirmation = direction.equals("NEUTRAL") ? "NOT_APPLICABLE" : "AWAITING_NEXT_BAR";
        Instant confirmationAt = null;
        if (!direction.equals("NEUTRAL") && i + 1 < bars.size()) {
            if (!sameSeries(bars, i, i + 1)) confirmation = "UNAVAILABLE";
            else {
                Bar next = bars.get(i + 1);
                // Only the immediate next closed bar is used, never a later favourable candle.
                boolean supports = direction.equals("BULLISH")
                        ? next.close().compareTo(b.high()) > 0 : next.close().compareTo(b.low()) < 0;
                confirmation = supports ? "PRICE_CONFIRMED" : "NOT_CONFIRMED";
                confirmationAt = next.closeTime();
            }
        }
        BigDecimal ratio = null;
        if (i >= 20 && sameSeries(bars, i - 20, i) && b.volume() != null && b.volume().signum() > 0) {
            BigDecimal total = BigDecimal.ZERO;
            boolean complete = true;
            for (int j = i - 20; j < i; j++) {
                BigDecimal v = bars.get(j).volume();
                if (v == null || v.signum() <= 0) { complete = false; break; }
                total = total.add(v);
            }
            if (complete) ratio = b.volume().multiply(BigDecimal.valueOf(20)).divide(total, 4, RoundingMode.HALF_UP);
        }
        String volume = ratio == null ? "UNAVAILABLE" : ratio.compareTo(new BigDecimal("1.5")) >= 0
                ? "ABOVE_USUAL" : "NOT_ELEVATED";
        return new Pattern(code, b.openTime(), b.closeTime(), direction, confirmation, confirmationAt, ratio, volume);
    }

    private static boolean sameSeries(List<Bar> bars, int from, int to) {
        String provider = bars.get(from).provider();
        if (provider == null || provider.isBlank()) return false;
        for (int j = from; j <= to; j++) {
            if (!valid(bars.get(j)) || !Objects.equals(provider, bars.get(j).provider())) return false;
            if (j > from && (!bars.get(j).followsPrevious()
                    || !bars.get(j).openTime().isAfter(bars.get(j - 1).openTime())
                    || bars.get(j).openTime().isBefore(bars.get(j - 1).closeTime()))) return false;
        }
        return true;
    }

    private static boolean valid(Bar b) {
        return b != null && b.openTime() != null && b.closeTime() != null && b.closeTime().isAfter(b.openTime())
                && b.open() != null && b.high() != null && b.low() != null && b.close() != null
                && b.low().signum() > 0 && b.low().compareTo(b.open().min(b.close())) <= 0
                && b.high().compareTo(b.open().max(b.close())) >= 0 && b.high().compareTo(b.low()) >= 0;
    }
}
