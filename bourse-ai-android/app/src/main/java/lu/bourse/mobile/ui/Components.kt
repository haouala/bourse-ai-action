package lu.bourse.mobile.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.*
import lu.bourse.mobile.*
import lu.bourse.mobile.data.*
import java.math.BigDecimal
import java.math.RoundingMode

fun amount(value: BigDecimal?, currency: String? = null): String = value?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()?.let { if (currency == null) it else "$it $currency" } ?: "Indisponible"
fun display(value: String?) = value ?: "Non renseigné"

@Composable
fun Page(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.background))).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun Panel(title: String, accent: androidx.compose.ui.graphics.Color? = null, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, accent?.copy(alpha = .8f) ?: MaterialTheme.colorScheme.outlineVariant)) {
        val container = MaterialTheme.colorScheme.surfaceContainer
        val background = if (accent == null) Modifier else Modifier.background(
            Brush.linearGradient(listOf(accent.copy(alpha = .22f), container)))
        Column(background.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (trailing == null) Text(title, style = MaterialTheme.typography.titleMedium)
            else Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                trailing()
            }
            content()
        }
    }
}

@Composable
fun Note(text: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Text(text, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun KeyValue(label: String, value: String) { Column { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyLarge) } }

@Composable
fun Remote(store: AppStore, key: String, empty: String = "Aucune donnée disponible", content: @Composable (JsonElement) -> Unit) {
    val resources by store.resources.collectAsStateWithLifecycle()
    val resource = resources[key]
    when {
        resource?.loading == true -> { LinearProgressIndicator(Modifier.fillMaxWidth().height(5.dp)); Text("Chargement…", style = MaterialTheme.typography.labelMedium) }
        resource?.error != null -> Note(resource.error, true)
        resource?.value != null -> {
            if (resource.value.isJsonNull) Text(empty) else content(resource.value)
        }
        else -> Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun <T> DataBlock(store: AppStore, key: String, empty: String = "Aucune donnée disponible", decode: (JsonElement) -> T, content: @Composable (T) -> Unit) {
    Remote(store, key, empty) { value ->
        val decoded = runCatching { decode(value) }
        if (decoded.isSuccess) content(decoded.getOrThrow()) else Note("Réponse du serveur incompatible : ${decoded.exceptionOrNull()?.message}", true)
    }
}

@Composable
fun Evidence(url: String?, label: String = "Ouvrir la source originale") {
    val context = LocalContext.current
    var failure by remember { mutableStateOf<String?>(null) }
    if (url != null && httpsEvidence(url)) {
        TextButton(onClick = {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { failure = "Aucune application ne peut ouvrir ce lien" }
        }) { Text(label) }
    } else Text("Source HTTPS non disponible", style = MaterialTheme.typography.bodySmall)
    failure?.let { Note(it, true) }
}

@Composable
fun JsonDetails(value: JsonElement, label: String = "Voir les preuves détaillées") {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Masquer les détails" else label) }
    if (expanded) SelectionContainer { Text(GsonBuilder().setPrettyPrinting().create().toJson(value), style = MaterialTheme.typography.bodySmall) }
}

data class InputField(val key: String, val label: String, val initial: String = "", val numeric: Boolean = false, val options: List<String> = emptyList())

@Composable
fun EditorDialog(title: String, fields: List<InputField>, store: AppStore, operation: String, onDismiss: () -> Unit, submitLabel: String = "Enregistrer", onSubmit: (Map<String, String>) -> Unit) {
    val values = rememberSaveable(saver = mapSaver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>>(
        save = { it.toMap() }, restore = { saved -> mutableStateMapOf<String, String>().apply { saved.forEach { (key, value) -> put(key, value as String) } } }
    )) { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.key, it.initial) } } }
    var validation by remember { mutableStateOf<String?>(null) }
    val resources by store.resources.collectAsStateWithLifecycle()
    val resource = resources[operation]
    AlertDialog(properties = DialogProperties(usePlatformDefaultWidth = false), modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f), onDismissRequest = { if (resource?.loading != true) onDismiss() }, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fields.forEach { field ->
                if (field.options.isEmpty()) OutlinedTextField(value = values[field.key].orEmpty(), onValueChange = { values[field.key] = it; validation = null }, label = { Text(field.label) }, keyboardOptions = KeyboardOptions(keyboardType = if (field.numeric) KeyboardType.Decimal else KeyboardType.Text), modifier = Modifier.fillMaxWidth().testTag("input-${field.key}"), singleLine = true)
                else {
                    Text(field.label, style = MaterialTheme.typography.labelLarge)
                    field.options.forEach { option -> Row { RadioButton(selected = values[field.key] == option, onClick = { values[field.key] = option }); TextButton(onClick = { values[field.key] = option }) { Text(option) } } }
                }
            }
            validation?.let { Note(it, true) }
            resource?.error?.let { Note(it, true) }
            if (resource?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        Button(enabled = resource?.loading != true, onClick = { try { onSubmit(values.toMap()) } catch (e: IllegalArgumentException) { validation = e.message ?: "Vérifier les champs" } }) { Text(submitLabel) }
    }, dismissButton = { TextButton(enabled = resource?.loading != true, onClick = onDismiss) { Text("Annuler") } })
}

@Composable
fun ScoreMeter(label: String, value: BigDecimal?, accent: androidx.compose.ui.graphics.Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KeyValue(label, value?.let { "${it.stripTrailingZeros().toPlainString()} / 100" } ?: "Indisponible")
        value?.let { score ->
            LinearProgressIndicator(progress = { (score.toFloat() / 100).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp), color = accent)
        }
    }
}
