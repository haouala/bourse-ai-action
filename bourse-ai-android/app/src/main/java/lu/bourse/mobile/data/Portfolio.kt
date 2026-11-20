package lu.bourse.mobile.data

import com.google.gson.*
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime

data class Position(val id: Long, val symbol: String, val currency: String, val quantity: BigDecimal, val entry: BigDecimal, val cost: BigDecimal, val horizon: String, val status: String, val openedAt: String?, val raw: JsonObject)
fun positions(value: JsonElement): List<Position> = value.rows().map { r -> Position(r.id() ?: throw ContractFailure("Position sans identifiant"), r.required("symbol"), r.required("currency"), r.decimal("quantity") ?: throw ContractFailure("Quantité absente"), r.decimal("averageEntryPrice") ?: throw ContractFailure("Prix de revient absent"), r.decimal("remainingCostBase") ?: throw ContractFailure("Coût absent"), r.required("horizon"), r.required("status"), r.text("openedAt"), r) }
fun timestamp(value: String, label: String, now: Instant = Instant.now()): Instant {
    val instant = try { OffsetDateTime.parse(value.trim()).toInstant() } catch (_: Exception) { throw IllegalArgumentException("$label : date ISO avec fuseau requise, ex. 2026-11-20T08:00:00+01:00") }
    require(!instant.isAfter(now)) { "$label : la date ne peut pas être future" }
    require(!instant.isBefore(Instant.parse("1970-01-01T00:00:00Z"))) { "$label : date trop ancienne" }
    return instant.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
}
fun createPortfolio(values: Map<String, String>): JsonObject {
    val name = values.getValue("name").trim(); require(name.isNotBlank() && name.length <= 120) { "Nom de portefeuille requis" }
    val currency = values.getValue("currency").trim().uppercase(java.util.Locale.ROOT); require(currency.matches(Regex("[A-Z]{3}"))) { "Devise sur trois lettres requise" }
    val capital = positiveDecimal(values.getValue("capital"), "Capital"); val loss = positiveDecimal(values.getValue("loss"), "Perte maximale")
    require(loss <= capital) { "La perte maximale ne peut pas dépasser le capital" }
    return obj("name" to name, "baseCurrency" to currency, "initialCapital" to capital, "maxLossAmount" to loss)
}
fun declaredTrade(values: Map<String, String>, requestId: String, position: Position? = null): JsonObject {
    val quantity = positiveDecimal(values.getValue("quantity"), "Quantité")
    val price = positiveDecimal(values.getValue("price"), "Prix")
    val fees = positiveDecimal(values.getValue("fees"), "Frais", true)
    val fx = positiveDecimal(values.getValue("fx"), "Taux de change explicite")
    val executed = timestamp(values.getValue("executedAt"), "Exécution")
    val side = values["side"] ?: "BUY"
    require(side in setOf("BUY", "SELL")) { "Sens invalide" }
    if (position != null) {
        require(position.status == "OPEN") { "La position est déjà clôturée" }
        require(side != "SELL" || quantity <= position.quantity) { "La vente dépasse la quantité détenue" }
    }
    if (side == "SELL") require(fees < price * quantity) { "Les frais dépassent le produit de vente" }
    return obj("clientOrderId" to requestId, "quantity" to quantity, "price" to price, "fees" to fees, "fxToBase" to fx, "executedAt" to executed).apply {
        if (position == null) {
            val horizon = values.getValue("horizon"); require(horizon in setOf("SHORT", "MEDIUM", "LONG")) { "Horizon invalide" }
            addProperty("symbol", normalizedSymbol(values.getValue("symbol"))); addProperty("horizon", horizon)
        } else addProperty("side", side)
    }
}
fun priceMark(values: Map<String, String>): JsonObject {
    val source = values.getValue("source").trim(); require(source.isNotBlank() && source.length <= 64) { "Source du prix requise (64 caractères maximum)" }
    return obj("price" to positiveDecimal(values.getValue("price"), "Prix"), "fxToBase" to positiveDecimal(values.getValue("fx"), "Taux de change explicite"), "observedAt" to timestamp(values.getValue("observedAt"), "Date du prix"), "fxObservedAt" to timestamp(values.getValue("fxObservedAt"), "Date du FX"), "source" to source)
}
