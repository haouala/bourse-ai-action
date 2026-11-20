package lu.bourse.mobile.data

import com.google.gson.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val chartFrames = listOf("15m", "30m", "1h", "1d")
fun priceText(value: BigDecimal?, currency: String? = null): String = value?.stripTrailingZeros()?.toPlainString()?.let { if (currency == null) it else "$it $currency" } ?: "Indisponible"
data class Candle(val opensAt: Instant, val closesAt: Instant, val open: BigDecimal, val high: BigDecimal, val low: BigDecimal, val close: BigDecimal, val provider: String?)
data class ChartData(val symbol: String, val timeframe: String, val currency: String, val candles: List<Candle>, val indicators: JsonObject?, val warning: String?)
fun chartData(value: JsonElement): ChartData {
    val r = value.objectValue(); val timeframe = r.required("timeframe")
    if (timeframe !in chartFrames) throw ContractFailure("Intervalle de bougie non pris en charge")
    val candles = r.list("candles").map { bar ->
        fun price(key: String) = bar.decimal(key)?.takeIf { it.signum() > 0 } ?: throw ContractFailure("Prix de bougie invalide")
        val openTime = try { Instant.parse(bar.required("openTime")) } catch (_: Exception) { throw ContractFailure("Date de bougie invalide") }
        val closeTime = try { Instant.parse(bar.required("closeTime")) } catch (_: Exception) { throw ContractFailure("Clôture de bougie invalide") }
        val candle = Candle(openTime, closeTime, price("open"), price("high"), price("low"), price("close"), bar.text("provider"))
        if (!closeTime.isAfter(openTime) || candle.low > candle.high || candle.close !in candle.low..candle.high || candle.open !in candle.low..candle.high) throw ContractFailure("Bougie incohérente")
        candle
    }
    if (candles.zipWithNext().any { (a, b) -> !b.opensAt.isAfter(a.opensAt) }) throw ContractFailure("Bougies non ordonnées ou dupliquées")
    return ChartData(r.required("symbol"), timeframe, r.required("currency"), candles, r.child("latestIndicators"), r.text("warning"))
}
fun dateLabel(value: String?): String = value?.let { try { DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm z", Locale.FRANCE).withZone(ZoneId.systemDefault()).format(Instant.parse(it)) } catch (_: Exception) { it } } ?: "Indisponible"
/** Normalization uses decimal arithmetic; Float is used only for pixel coordinates. */
fun normalizedPoints(prices: List<BigDecimal>): List<Float> {
    if (prices.isEmpty()) return emptyList()
    val min = prices.min(); val range = prices.max() - min
    return if (range.signum() == 0) prices.map { 0.5f } else prices.map { (it - min).divide(range, 10, java.math.RoundingMode.HALF_UP).toFloat() }
}
