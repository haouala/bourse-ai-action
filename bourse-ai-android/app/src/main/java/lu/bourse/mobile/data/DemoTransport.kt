package lu.bourse.mobile.data

import com.google.gson.*

/** Local fixtures only. They never call or write to a server. */
class DemoTransport : Transport {
    override suspend fun request(method: String, path: List<String>, query: Map<String, String>, body: JsonObject?): JsonElement {
        if (path == listOf("actuator", "health")) return obj("status" to "UP", "mode" to "DEMO")
        throw ApiFailure(404, "Module de démonstration pas encore disponible")
    }
}
