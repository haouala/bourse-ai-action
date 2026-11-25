package lu.bourse.mobile.data

internal data class IndicatorHistory(
    val sma: List<Double?>,
    val rsi: List<Double?>,
    val macd: List<Double?>,
    val signal: List<Double?>,
    val histogram: List<Double?>,
)

/** The backend's SMA, Wilder RSI and seeded EMA conventions, applied to each close. */
internal fun indicatorHistory(prices: List<Double>): IndicatorHistory {
    fun ema(values: List<Double>, period: Int): List<Double?> {
        var average = if (values.size >= period) values.take(period).average() else 0.0
        val weight = 2.0 / (period + 1)
        return values.mapIndexed { i, value ->
            if (i < period - 1) null else {
                if (i >= period) average += (value - average) * weight
                average
            }
        }
    }

    val fast = ema(prices, 12)
    val slow = ema(prices, 26)
    val macd = prices.indices.map { i -> slow[i]?.let { fast[i]!! - it } }
    val signal = List(minOf(25, prices.size)) { null } + ema(macd.filterNotNull(), 9)
    val sma = prices.indices.map { i -> if (i < 19) null else prices.subList(i - 19, i + 1).average() }
    var gain = 0.0
    var loss = 0.0
    val rsi = prices.indices.map { i ->
        if (i == 0) null else {
            val change = prices[i] - prices[i - 1]
            val up = change.coerceAtLeast(0.0)
            val down = (-change).coerceAtLeast(0.0)
            if (i <= 14) {
                gain += up / 14
                loss += down / 14
            } else {
                gain = (gain * 13 + up) / 14
                loss = (loss * 13 + down) / 14
            }
            if (i < 14) null else if (loss == 0.0) {
                if (gain == 0.0) 50.0 else 100.0
            } else 100 - 100 / (1 + gain / loss)
        }
    }
    return IndicatorHistory(sma, rsi, macd, signal,
        macd.indices.map { i -> signal[i]?.let { macd[i]!! - it } })
}
