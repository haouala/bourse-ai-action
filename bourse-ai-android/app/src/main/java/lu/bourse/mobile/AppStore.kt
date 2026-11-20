package lu.bourse.mobile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import lu.bourse.mobile.data.*

data class Resource(val loading: Boolean = false, val value: JsonElement? = null, val error: String? = null)
interface ConfigStorage { fun read(): ConnectionConfig; fun save(config: ConnectionConfig) }
class PhoneConfig(context: Context) : ConfigStorage {
    private val prefs = context.getSharedPreferences("bourse-settings", Context.MODE_PRIVATE)
    override fun read() = ConnectionConfig(prefs.getBoolean("demo", true), prefs.getString("server", "http://10.0.2.2:8081")!!, prefs.getLong("portfolio", 0).takeIf { it > 0 })
    override fun save(config: ConnectionConfig) { prefs.edit().putBoolean("demo", config.demo).putString("server", config.server).putLong("portfolio", config.portfolioId ?: 0).apply() }
}

class AppStore(private val storage: ConfigStorage, private val factory: (ConnectionConfig) -> Transport = { if (it.demo) DemoTransport() else HttpTransport(it, BuildConfig.DEBUG) }) : ViewModel() {
    private val _config = MutableStateFlow(storage.read())
    val config: StateFlow<ConnectionConfig> = _config.asStateFlow()
    private var transport = factory(_config.value)
    private val _resources = MutableStateFlow<Map<String, Resource>>(emptyMap())
    val resources: StateFlow<Map<String, Resource>> = _resources.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private var generation = 0

    fun configure(config: ConnectionConfig) {
        val valid = config.validated(BuildConfig.DEBUG)
        val next = factory(valid)
        generation++
        jobs.values.forEach { it.cancel() }; jobs.clear()
        storage.save(valid); transport = next; _config.value = valid; _resources.value = emptyMap()
    }
    fun selectPortfolio(id: Long) { require(id > 0); val next = config.value.copy(portfolioId = id); storage.save(next); _config.value = next }
    fun clear(key: String) { jobs.remove(key)?.cancel(); _resources.update { it - key } }

    fun load(key: String, path: List<String>, query: Map<String, String> = emptyMap(), method: String = "GET", body: JsonObject? = null) {
        jobs.remove(key)?.cancel()
        val epoch = generation; val source = transport
        _resources.update { it + (key to Resource(loading = true)) }
        jobs[key] = viewModelScope.launch {
            try {
                val result = source.request(method, path, query, body)
                if (epoch == generation) _resources.update { it + (key to Resource(value = result)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation) _resources.update { it + (key to Resource(error = e.message ?: "Impossible de charger les données")) } }
        }
    }
    /** No automatic retry: writes are explicit and stable request IDs are supplied by forms. */
    fun mutate(key: String, path: List<String>, body: JsonObject? = null, method: String = "POST", onSuccess: (JsonElement) -> Unit = {}) {
        if (jobs[key]?.isActive == true) return
        val epoch = generation; val source = transport
        _resources.update { it + (key to Resource(loading = true)) }
        jobs[key] = viewModelScope.launch {
            try {
                val result = source.request(method, path, body = body)
                if (epoch == generation) { _resources.update { it + (key to Resource(value = result)) }; onSuccess(result) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation) _resources.update { it + (key to Resource(error = e.message ?: "Opération refusée")) } }
        }
    }
}

fun api(vararg segments: Any) = listOf("api", "v1") + segments.map { it.toString() }
