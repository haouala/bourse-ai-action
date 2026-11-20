package lu.bourse.mobile.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lu.bourse.mobile.*
import lu.bourse.mobile.data.*

@Composable
fun AlertsScreen(store: AppStore) {
    val config by store.config.collectAsStateWithLifecycle()
    val states by store.resources.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var onlyNew by rememberSaveable { mutableStateOf(true) }
    var notifications by remember(config) { mutableStateOf(AlertNotifications.enabled(context, config)) }
    var message by remember { mutableStateOf<String?>(null) }
    fun refresh() { config.portfolioId?.let { store.load("alerts:$it", api("portfolios", it, "alerts")) } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && AlertNotifications.allowed(context) && !config.demo && config.portfolioId != null) {
            AlertNotifications.setEnabled(context, config, true); notifications = true; message = "Consultation périodique activée"
        } else { notifications = false; message = "Notifications non autorisées. Les alertes restent consultables dans l’application." }
    }
    LaunchedEffect(config) { AlertNotifications.reconcile(context, config); notifications = AlertNotifications.enabled(context, config); if (config.demo && config.portfolioId == null) store.selectPortfolio(1) else refresh() }
    Page("Alertes", "Les signaux invitent à réexaminer une position. Aucun ordre de vente n’est exécuté.") {
        if (config.portfolioId == null) Note("Choisir ou créer un portefeuille pour consulter ses alertes.")
        else {
            Panel("Surveillance Android") {
                Row { Checkbox(checked = notifications, enabled = !config.demo, onCheckedChange = { active ->
                    if (!active) { AlertNotifications.setEnabled(context, config, false); notifications = false }
                    else if (Build.VERSION.SDK_INT >= 33 && !AlertNotifications.allowed(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else if (AlertNotifications.allowed(context)) { AlertNotifications.setEnabled(context, config, true); notifications = true }
                    else message = "Autoriser les notifications dans les réglages Android."
                }); Text("Recevoir les alertes du backend") }
                Text("Consultation périodique au plus tôt toutes les 15 minutes, soumise aux restrictions Android et à Internet. Ce mécanisme n’est pas un push instantané.")
                Text("Le backend doit rester démarré et produire les alertes. Le téléphone ne collecte pas les cours et ne réévalue pas le portefeuille en arrière-plan.")
                if (config.demo) Text("Les notifications en arrière-plan sont désactivées en mode démonstration.")
                message?.let { Note(it) }
            }
            Row { FilterChip(onlyNew, { onlyNew = true }, label = { Text("Nouvelles") }); FilterChip(!onlyNew, { onlyNew = false }, label = { Text("Toutes") }) }
            OutlinedButton(onClick = { refresh() }) { Text("Actualiser les alertes") }
            Button(enabled = states["alert-write"]?.loading != true, onClick = { store.mutate("alert-write", api("portfolios", config.portfolioId!!, "monitor")) { refresh() } }) { Text("Réévaluer maintenant dans le backend") }
            states["alert-write"]?.error?.let { Note(it, true) }
            DataBlock(store, "alerts:${config.portfolioId}", decode = { alerts(it).filter { alert -> !onlyNew || alert.status == "NEW" } }) { all ->
                if (all.isEmpty()) Note("Aucune alerte correspondant à ce filtre. Cela ne garantit pas l’absence de risque.")
                all.forEach { alert -> Panel(if (alert.type == "LOSS_LIMIT") "Limite de perte" else "Signal à réexaminer") {
                    Text(alert.message)
                    KeyValue("Donnée à l’origine du signal", dateLabel(alert.dataTime))
                    KeyValue("Création de l’alerte", dateLabel(alert.createdAt))
                    Text("${alert.type} · ${alert.status}", style = MaterialTheme.typography.labelSmall)
                    if (alert.status == "NEW") TextButton(enabled = states["alert-write"]?.loading != true, onClick = { store.mutate("alert-write", api("portfolios", config.portfolioId!!, "alerts", alert.id, "acknowledge")) { refresh() } }) { Text("Marquer comme lue") }
                } }
            }
        }
    }
}
