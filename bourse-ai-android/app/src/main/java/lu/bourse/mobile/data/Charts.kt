package lu.bourse.mobile.data

import com.google.gson.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val chartFrames = listOf("15m", "30m", "1h", "1d")
fun priceText(value: BigDecimal?, currency: String? = null): String = value?.stripTrailingZeros()?.toPlainString()?.let { if (currency == null) it else "$it $currency" } ?: "Indisponible"
data class Candle(val opensAt: Instant, val closesAt: Instant, val open: BigDecimal, val high: BigDecimal, val low: BigDecimal, val close: BigDecimal, val provider: String?, val volume: BigDecimal? = null)
data class CandlePattern(val code: String, val opensAt: Instant, val closesAt: Instant, val confirmation: String, val confirmationAt: Instant?, val volumeRatio: BigDecimal?, val volumeStatus: String)
data class PatternReport(val inspected: Int, val patterns: List<CandlePattern>)
data class ChartData(val symbol: String, val timeframe: String, val currency: String, val candles: List<Candle>, val indicators: JsonObject?, val warning: String?, val patterns: PatternReport? = null)
fun chartData(value: JsonElement): ChartData {
    val r = value.objectValue(); val timeframe = r.required("timeframe")
    if (timeframe !in chartFrames) throw ContractFailure("Intervalle de bougie non pris en charge")
    val candles = r.list("candles").map { bar ->
        fun price(key: String) = bar.decimal(key)?.takeIf { it.signum() > 0 } ?: throw ContractFailure("Prix de bougie invalide")
        val openTime = try { Instant.parse(bar.required("openTime")) } catch (_: Exception) { throw ContractFailure("Date de bougie invalide") }
        val closeTime = try { Instant.parse(bar.required("closeTime")) } catch (_: Exception) { throw ContractFailure("Clôture de bougie invalide") }
        val candle = Candle(openTime, closeTime, price("open"), price("high"), price("low"), price("close"), bar.text("provider"), bar.decimal("volume")?.takeIf { it.signum() >= 0 })
        if (!closeTime.isAfter(openTime) || candle.low > candle.high || candle.close !in candle.low..candle.high || candle.open !in candle.low..candle.high) throw ContractFailure("Bougie incohérente")
        candle
    }
    if (candles.zipWithNext().any { (a, b) -> !b.opensAt.isAfter(a.opensAt) }) throw ContractFailure("Bougies non ordonnées ou dupliquées")
    // Optional extension: an old or incompatible detector must not hide the price history.
    val patterns = runCatching { r.child("patternAnalysis")?.let { patternReport(it, candles) } }.getOrNull()
    return ChartData(r.required("symbol"), timeframe, r.required("currency"), candles, r.child("latestIndicators"), r.text("warning"), patterns)
}

private fun patternReport(r: JsonObject, candles: List<Candle>): PatternReport {
    require(r.required("method") == "OHLC_PATTERNS_V1")
    require(r.required("status") in listOf("AVAILABLE", "NO_DATA"))
    val inspected = r.id("inspectedCandles")?.toInt() ?: error("Fenêtre absente")
    require(inspected in 0..minOf(12, candles.size))
    val patterns = r.list("patterns").map { row ->
        val code = row.required("code")
        require(code in listOf("HAMMER", "DOJI", "BULLISH_ENGULFING", "BEARISH_ENGULFING"))
        val open = Instant.parse(row.required("openTime")); val close = Instant.parse(row.required("closeTime"))
        val index = candles.indexOfFirst { it.opensAt == open && it.closesAt == close }
        require(index >= candles.size - inspected && index >= 0)
        val confirmation = row.required("confirmation")
        require(confirmation in listOf("AWAITING_NEXT_BAR", "PRICE_CONFIRMED", "NOT_CONFIRMED", "NOT_APPLICABLE", "UNAVAILABLE"))
        require((code == "DOJI") == (confirmation == "NOT_APPLICABLE"))
        val at = row.text("confirmationAt")?.let(Instant::parse)
        if (confirmation in listOf("PRICE_CONFIRMED", "NOT_CONFIRMED")) require(at == candles.getOrNull(index + 1)?.closesAt && at != null)
        else require(at == null)
        if (confirmation == "AWAITING_NEXT_BAR") require(index == candles.lastIndex)
        val ratio = row.decimal("volumeRatio")
        val volumeStatus = row.required("volumeStatus")
        require(volumeStatus in listOf("UNAVAILABLE", "ABOVE_USUAL", "NOT_ELEVATED"))
        require((ratio == null) == (volumeStatus == "UNAVAILABLE"))
        require(ratio == null || ratio.signum() >= 0) // A tiny positive ratio may round to zero on the server.
        CandlePattern(code, open, close, confirmation, at, ratio, volumeStatus)
    }
    require(patterns.size <= 24 && patterns.distinctBy { it.code to it.opensAt }.size == patterns.size)
    return PatternReport(inspected, patterns.sortedByDescending { it.closesAt })
}
fun dateLabel(value: String?): String = value?.let { try { DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm z", Locale.FRANCE).withZone(ZoneId.systemDefault()).format(Instant.parse(it)) } catch (_: Exception) { it } } ?: "Indisponible"
/** Normalization uses decimal arithmetic; Float is used only for pixel coordinates. */
fun normalizedPoints(prices: List<BigDecimal>): List<Float> {
    if (prices.isEmpty()) return emptyList()
    val min = prices.min(); val range = prices.max() - min
    return if (range.signum() == 0) prices.map { 0.5f } else prices.map { (it - min).divide(range, 10, java.math.RoundingMode.HALF_UP).toFloat() }
}
