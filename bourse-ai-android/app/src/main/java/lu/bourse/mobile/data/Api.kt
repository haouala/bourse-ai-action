package lu.bourse.mobile.data

import com.google.gson.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiFailure(val status: Int?, message: String, val code: String? = null,
    val severity: String = "ERROR", val retryAfterSeconds: Long? = null,
    val requestId: String? = null) : IOException(message)

/** Only accepts the explicitly defined, bounded backend error contract, never arbitrary upstream bodies. */
fun serverFailure(status: Int, body: String?): ApiFailure {
    val defaultMessage = when (status) {
        401, 403 -> "Accès refusé par le serveur."
        404 -> "Donnée ou route introuvable. Vérifier la version du backend."
        409 -> "Conflit : capacité, identifiant ou opération déjà enregistrée."
        429 -> "Quota atteint. Réessayer plus tard."
        502, 503, 504 -> "Un fournisseur externe est temporairement indisponible."
        else -> "Le serveur a refusé la demande (HTTP $status)."
    }
    val details = runCatching { JsonParser.parseString(body.orEmpty()).asJsonObject }.getOrNull()
    val code = details?.get("code")?.takeIf { it.isJsonPrimitive }?.asString
        ?.takeIf { it.matches(Regex("[A-Z0-9_]{3,64}")) }
    val message = if (code != null) details.get("message")?.takeIf { it.isJsonPrimitive }?.asString
        ?.takeIf { it.length in 8..240 && !it.contains('\\n') } else null
    val severity = if (code != null && details?.get("severity")?.asString == "WARNING") "WARNING"
        else if (status == 429 || status == 502 || status == 503 || status == 504) "WARNING" else "ERROR"
    val retry = details?.get("retryAfterSeconds")?.takeIf { it.isJsonPrimitive }
        ?.asString?.toLongOrNull()?.takeIf { it in 1..86400 }
    val requestId = details?.get("requestId")?.takeIf { it.isJsonPrimitive }?.asString
        ?.takeIf { it.matches(Regex("[a-zA-Z0-9-]{1,64}")) }
    return ApiFailure(status, message ?: defaultMessage, code, severity, retry, requestId)
}
class ContractFailure(message: String) : IOException(message)

data class ConnectionConfig(val demo: Boolean = true, val server: String = "http://10.0.2.2:8081", val portfolioId: Long? = null) {
    fun validated(debug: Boolean): ConnectionConfig {
        val url = server.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Adresse du serveur invalide")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Adresse sans identifiants, paramètres ou fragment requise" }
        require(debug || url.isHttps) { "HTTPS est obligatoire dans une version de production" }
        require(portfolioId == null || portfolioId > 0) { "Identifiant de portefeuille positif requis" }
        return copy(server = url.toString().trimEnd('/'))
    }
}

interface Transport {
    suspend fun request(method: String, path: List<String>, query: Map<String, String> = emptyMap(), body: JsonObject? = null): JsonElement
}

class HttpTransport(config: ConnectionConfig, debug: Boolean) : Transport {
    private val base = config.validated(debug).server.toHttpUrlOrNull()!!
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    override suspend fun request(method: String, path: List<String>, query: Map<String, String>, body: JsonObject?): JsonElement {
        val url = base.newBuilder().apply {
            path.forEach { addPathSegment(it) }
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val payload = if (method in setOf("POST", "PUT")) (body?.toString() ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()) else null
        val req = Request.Builder().url(url).header("Accept", "application/json").method(method, payload).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(req)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ApiFailure(null, "Serveur inaccessible. Vérifier la connexion et son adresse."))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!it.isSuccessful) {
                                val responseSource = it.body?.source()
                                responseSource?.request(4097)
                                val responseText = responseSource?.buffer?.readUtf8(
                                    minOf(responseSource.buffer.size, 4096L)).orEmpty()
                                throw serverFailure(it.code, responseText)
                            }
                            val source = it.body?.source()
                            if (source != null && source.request(4L * 1024 * 1024 + 1)) throw ContractFailure("Réponse trop volumineuse")
                            val raw = source?.readUtf8().orEmpty()
                            val parsed = if (raw.isBlank()) JsonNull.INSTANCE else try { JsonParser.parseString(raw) } catch (_: JsonParseException) { throw ContractFailure("Réponse JSON invalide") }
                            if (continuation.isActive) continuation.resume(parsed)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }
}

fun obj(vararg values: Pair<String, Any?>): JsonObject = JsonObject().apply {
    values.forEach { (key, value) -> add(key, when (value) {
        null -> JsonNull.INSTANCE
        is JsonElement -> value
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }) }
}
fun JsonElement.objectValue(): JsonObject = if (isJsonObject) asJsonObject else throw ContractFailure("Objet attendu dans la réponse")
fun JsonElement.rows(): List<JsonObject> = if (isJsonArray) asJsonArray.map { it.objectValue() } else throw ContractFailure("Liste attendue dans la réponse")
fun JsonObject.field(name: String): JsonElement? = entrySet().firstOrNull { it.key.replace("_", "").equals(name.replace("_", ""), ignoreCase = true) }?.value?.takeUnless { it.isJsonNull }
fun JsonObject.text(name: String): String? = field(name)?.takeIf { it.isJsonPrimitive }?.asString
fun JsonObject.decimal(name: String): BigDecimal? = text(name)?.toBigDecimalOrNull()
fun JsonObject.id(name: String = "id"): Long? = try { decimal(name)?.longValueExact() } catch (_: ArithmeticException) { null }
fun JsonObject.flag(name: String): Boolean? = text(name)?.toBooleanStrictOrNull()
fun JsonObject.child(name: String): JsonObject? = field(name)?.takeIf { it.isJsonObject }?.asJsonObject
fun JsonObject.list(name: String): List<JsonObject> = field(name)?.rows() ?: emptyList()
fun JsonObject.required(name: String): String = text(name) ?: throw ContractFailure("Champ absent : $name")

fun positiveDecimal(value: String, label: String, allowZero: Boolean = false): BigDecimal {
    val normalized = value.trim().replace(',', '.')
    require(normalized.length <= 40 && normalized.matches(Regex("[0-9]+(?:\\.[0-9]{1,8})?"))) { "$label : nombre décimal invalide (sans exposant)" }
    val number = normalized.toBigDecimalOrNull() ?: throw IllegalArgumentException("$label : nombre invalide")
    require(number.precision() - number.scale() <= 14) { "$label : montant trop élevé" }
    require(number.scale() <= 8 && number.precision() <= 24 && (number.signum() > 0 || allowZero && number.signum() == 0)) { "$label : montant ${if (allowZero) "positif ou nul" else "positif"}, au plus 8 décimales" }
    return number
}
fun normalizedSymbol(value: String): String = value.trim().uppercase(java.util.Locale.ROOT).also { require(it.matches(Regex("[A-Z0-9._-]{1,40}"))) { "Symbole invalide" } }
fun httpsEvidence(value: String): Boolean = value.toHttpUrlOrNull()?.let { it.isHttps && it.username.isEmpty() && it.password.isEmpty() } == true
