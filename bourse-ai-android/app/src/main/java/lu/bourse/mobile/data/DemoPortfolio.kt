package lu.bourse.mobile.data

import com.google.gson.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

class DemoPortfolio {
    private data class Ledger(val id: Long, val currency: String, val initial: BigDecimal, val maxLoss: BigDecimal, var cash: BigDecimal, val positions: MutableList<JsonObject> = mutableListOf(), val marks: MutableMap<Long, JsonObject> = mutableMapOf(), val operations: MutableMap<String, Pair<JsonObject, Long>> = mutableMapOf(), var latched: Boolean = false)
    private val books = mutableMapOf<Long, Ledger>()
    private var positionSequence = 1L
    val alerts = mutableMapOf<Long, MutableList<JsonObject>>()
    init {
        val book = Ledger(1, "EUR", BigDecimal(1000), BigDecimal(100), BigDecimal("711.70"))
        book.positions.add(obj("id" to 1, "assetId" to 2, "symbol" to "NVDA", "currency" to "USD", "quantity" to 2, "averageEntryPrice" to 155, "remainingCostBase" to BigDecimal("288.30"), "horizon" to "MEDIUM", "signalTimeframe" to "1h", "status" to "OPEN", "openedAt" to "2026-11-10T14:35:00Z"))
        book.marks[1] = obj("price" to 150, "fxToBase" to BigDecimal("0.93"), "observedAt" to "2026-11-19T21:00:00Z", "fxObservedAt" to "2026-11-19T21:00:00Z", "source" to "DEMO_ONLY")
        books[1] = book
        alerts[1] = mutableListOf(obj("id" to 1, "portfolio_id" to 1, "position_id" to 1, "alert_type" to "SOCIAL_EVENT_REVIEW", "message" to "Exemple fictif : réexaminer NVDA à la suite du projet tarifaire. Aucune vente n’est exécutée.", "status" to "NEW", "created_time" to "2026-11-19T17:40:00Z", "data_time" to "2026-11-19T17:30:00Z"))
    }
    private fun money(n: BigDecimal) = n.setScale(8, RoundingMode.HALF_UP)
    private fun risk(book: Ledger): JsonObject {
        val open = book.positions.filter { it.text("status") == "OPEN" }
        val missing = open.count { it.id() !in book.marks }
        val equity = open.fold(book.cash) { total, p -> total + (book.marks[p.id()]?.let { p.decimal("quantity")!! * it.decimal("price")!! * it.decimal("fxToBase")!! } ?: BigDecimal.ZERO) }
        val loss = if (missing == 0) (book.initial - equity).max(BigDecimal.ZERO) else null
        val status = if (missing > 0) "DATA_INCOMPLETE" else if (loss!! >= book.maxLoss) "LOSS_LIMIT_REACHED" else "DEMO_ESTIMATE"
        if (status == "LOSS_LIMIT_REACHED" && !book.latched) {
            val rows = alerts.getOrPut(book.id) { mutableListOf() }
            rows.add(obj("id" to rows.size + 100L, "portfolio_id" to book.id, "alert_type" to "LOSS_LIMIT", "message" to "Démo : limite de perte globale atteinte. Réexaminer le portefeuille.", "status" to "NEW", "created_time" to Instant.now(), "data_time" to Instant.now())); book.latched = true
        } else if (loss != null && loss < book.maxLoss) book.latched = false
        return obj("portfolioId" to book.id, "baseCurrency" to book.currency, "cash" to money(book.cash), "knownEquity" to money(equity), "loss" to loss?.let { money(it) }, "maxLossAmount" to book.maxLoss, "remainingLossBudget" to loss?.let { (book.maxLoss - it).max(BigDecimal.ZERO) }, "missingMarks" to missing, "staleMarks" to 0, "status" to status, "evaluatedAt" to Instant.now(), "valuationBasis" to "DEMO_ONLY", "quality" to JsonArray().apply { open.forEach { p -> add(obj("assetId" to p.id("assetId"), "status" to if (p.id() in book.marks) "DEMO_ESTIMATE" else "DATA_INCOMPLETE", "observedAt" to book.marks[p.id()]?.text("observedAt"), "fxObservedAt" to book.marks[p.id()]?.text("fxObservedAt"))) } })
    }
    fun request(method: String, path: List<String>, body: JsonObject?, asset: (String) -> JsonObject?): JsonElement? {
        if (path.take(3) != listOf("api", "v1", "portfolios")) return null
        if (path.size == 3 && method == "POST") {
            require(body != null); val id = (books.keys.maxOrNull() ?: 0) + 1
            val initial = body.decimal("initialCapital")!!; val max = body.decimal("maxLossAmount")!!
            if (max > initial) throw ApiFailure(400, "La limite dépasse le capital")
            books[id] = Ledger(id, body.required("baseCurrency"), initial, max, initial); return obj("id" to id)
        }
        val id = path.getOrNull(3)?.toLongOrNull() ?: return null
        val book = books[id] ?: throw ApiFailure(404, "Portefeuille inconnu")
        if (path.last() in listOf("evaluate", "monitor")) return risk(book)
        if (path.getOrNull(4) == "alerts") {
            if (path.last() == "acknowledge") { alerts[id]?.firstOrNull { it.id() == path.getOrNull(5)?.toLongOrNull() }?.addProperty("status", "ACKNOWLEDGED"); return JsonNull.INSTANCE }
            return JsonArray().apply { alerts[id].orEmpty().asReversed().forEach { add(it.deepCopy()) } }
        }
        if (path.getOrNull(4) != "positions") return null
        if (path.size == 5 && method == "GET") return JsonArray().apply { book.positions.forEach { add(it.deepCopy()) } }
        if (path.size == 5 && method == "POST") {
            require(body != null); val key = body.required("clientOrderId")
            book.operations[key]?.let { if (it.first != body) throw ApiFailure(409, "Même identifiant avec une autre opération"); return obj("positionId" to it.second) }
            val a = asset(body.required("symbol")) ?: throw ApiFailure(404, "Actif inconnu")
            if (a.text("currency") == book.currency && body.decimal("fxToBase")?.compareTo(BigDecimal.ONE) != 0) throw ApiFailure(400, "Même devise : FX égal à 1 requis")
            val cost = (body.decimal("quantity")!! * body.decimal("price")!! + body.decimal("fees")!!) * body.decimal("fxToBase")!!
            if (cost > book.cash) throw ApiFailure(409, "Liquidités insuffisantes")
            val position = ++positionSequence
            book.positions.add(obj("id" to position, "assetId" to a.id("assetId"), "symbol" to a.text("symbol"), "currency" to a.text("currency"), "quantity" to body.decimal("quantity"), "averageEntryPrice" to body.decimal("price"), "remainingCostBase" to cost, "horizon" to body.text("horizon"), "signalTimeframe" to when(body.text("horizon")) { "SHORT" -> "15m"; "LONG" -> "1d"; else -> "1h" }, "status" to "OPEN", "openedAt" to body.text("executedAt")))
            book.cash -= cost; book.operations[key] = body.deepCopy() to position; return obj("positionId" to position)
        }
        val positionId = path.getOrNull(5)?.toLongOrNull() ?: return null
        val p = book.positions.firstOrNull { it.id() == positionId } ?: throw ApiFailure(404, "Position inconnue")
        when(path.last()) {
            "mark" -> { require(body != null); if (p.text("currency") == book.currency && body.decimal("fxToBase")?.compareTo(BigDecimal.ONE) != 0) throw ApiFailure(400, "Même devise : FX égal à 1 requis"); book.marks[positionId] = body.deepCopy(); return JsonNull.INSTANCE }
            "plan" -> {
                val r = risk(book); val mark = book.marks[positionId]
                val valid = r.text("status") != "DATA_INCOMPLETE" && r.text("status") != "LOSS_LIMIT_REACHED" && mark != null
                return obj("position" to p.deepCopy(), "risk" to r, "executableOrder" to false, "status" to if (valid) "ILLUSTRATIVE_DEMO_SCENARIO" else "INSUFFICIENT_FRESH_DATA_OR_RISK_LIMIT", "observedPrice" to mark?.decimal("price"), "proposedProtection" to if (valid) p.decimal("averageEntryPrice")!! * BigDecimal("0.9") else null, "targetPrice" to if (valid) p.decimal("averageEntryPrice")!! * BigDecimal("1.2") else null)
            }
            "trades" -> {
                require(body != null); val key = body.required("clientOrderId")
                book.operations[key]?.let { if (it.first != body) throw ApiFailure(409, "Opération modifiée avec le même identifiant"); return obj("tradeId" to it.second) }
                if (p.text("status") != "OPEN") throw ApiFailure(409, "Position clôturée")
                val q = body.decimal("quantity")!!; val oldQ = p.decimal("quantity")!!; val price = body.decimal("price")!!; val fees = body.decimal("fees")!!; val fx = body.decimal("fxToBase")!!
                if (p.text("currency") == book.currency && fx.compareTo(BigDecimal.ONE) != 0) throw ApiFailure(400, "Même devise : FX égal à 1 requis")
                if (body.text("side") == "SELL") {
                    if (q > oldQ) throw ApiFailure(409, "Quantité trop élevée")
                    p.addProperty("quantity", oldQ - q); p.addProperty("remainingCostBase", p.decimal("remainingCostBase")!! * (oldQ - q).divide(oldQ, 8, RoundingMode.HALF_UP)); book.cash += (q * price - fees) * fx
                    if (oldQ.compareTo(q) == 0) p.addProperty("status", "CLOSED")
                } else {
                    val cost = (q * price + fees) * fx; if (cost > book.cash) throw ApiFailure(409, "Liquidités insuffisantes")
                    p.addProperty("averageEntryPrice", (oldQ * p.decimal("averageEntryPrice")!! + q * price).divide(oldQ + q, 8, RoundingMode.HALF_UP)); p.addProperty("quantity", oldQ + q); p.addProperty("remainingCostBase", p.decimal("remainingCostBase")!! + cost); book.cash -= cost
                }
                val trade = book.operations.size + 1000L; book.operations[key] = body.deepCopy() to trade; return obj("tradeId" to trade)
            }
        }
        return null
    }
}
