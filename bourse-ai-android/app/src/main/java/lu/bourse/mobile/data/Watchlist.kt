package lu.bourse.mobile.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.LocalTime
import java.time.ZoneId

data class Asset(val assetId: Long, val symbol: String, val name: String, val type: String, val currency: String, val providerSymbol: String, val region: String, val zone: String, val opens: String, val closes: String, val enabled: Boolean) {
    fun payload(active: Boolean = enabled) = obj("symbol" to symbol, "name" to name, "assetType" to type, "currency" to currency, "providerSymbol" to providerSymbol, "region" to region, "marketZone" to zone, "sessionOpen" to opens, "sessionClose" to closes, "enabled" to active)
}
fun assets(value: JsonElement): List<Asset> = value.rows().map { r -> Asset(
    r.id("assetId") ?: throw ContractFailure("Identifiant d’actif absent"), r.required("symbol"), r.required("name"), r.required("assetType"), r.required("currency"), r.required("providerSymbol"), r.required("region"), r.required("marketZone"), r.required("sessionOpen"), r.required("sessionClose"), r.flag("enabled") ?: throw ContractFailure("État de suivi absent")
) }
fun filterAssets(all: List<Asset>, query: String, type: String): List<Asset> = all.filter { (type == "ALL" || it.type == type) && (it.symbol.contains(query.trim(), true) || it.name.contains(query.trim(), true)) }.sortedBy { it.symbol }
fun watchPayload(values: Map<String, String>, enabled: Boolean = true): JsonObject {
    val symbol = normalizedSymbol(values.getValue("symbol")); val type = values.getValue("type")
    require(type in setOf("STOCK", "CRYPTO")) { "Type d’actif invalide" }
    val currency = values.getValue("currency").trim().uppercase(java.util.Locale.ROOT)
    require(currency.matches(Regex("[A-Z]{3}"))) { "Devise sur trois lettres requise" }
    val name = values.getValue("name").trim(); require(name.isNotBlank() && name.length <= 255) { "Nom requis (255 caractères maximum)" }
    val provider = values.getValue("provider").trim(); require(provider.matches(Regex("[A-Za-z0-9._:/-]{1,80}"))) { "Symbole fournisseur invalide" }
    val zone = if (type == "CRYPTO") "UTC" else values.getValue("zone").trim()
    val opens = if (type == "CRYPTO") "00:00" else values.getValue("opens").trim()
    val closes = if (type == "CRYPTO") "00:00" else values.getValue("closes").trim()
    try { ZoneId.of(zone); require(type == "CRYPTO" || LocalTime.parse(opens).isBefore(LocalTime.parse(closes))) } catch (_: Exception) { throw IllegalArgumentException("Fuseau ou horaires invalides") }
    val region = if (type == "CRYPTO") "GLOBAL" else values.getValue("region")
    require(region in setOf("US", "EU", "GLOBAL")) { "Région invalide" }
    return obj("symbol" to symbol, "name" to name, "assetType" to type, "currency" to currency, "providerSymbol" to provider, "region" to region, "marketZone" to zone, "sessionOpen" to opens, "sessionClose" to closes, "enabled" to enabled)
}
