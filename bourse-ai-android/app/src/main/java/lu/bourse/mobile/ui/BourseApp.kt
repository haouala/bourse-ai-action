@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package lu.bourse.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import lu.bourse.mobile.*
import lu.bourse.mobile.data.*

private data class Tab(val route: String, val title: String, val icon: ImageVector)
private val tabs = listOf(Tab("market", "Marché", Icons.Default.ShowChart), Tab("news", "Infos", Icons.Default.Article), Tab("portfolio", "Portefeuille", Icons.Default.AccountBalanceWallet), Tab("alerts", "Alertes", Icons.Default.Notifications), Tab("settings", "Réglages", Icons.Default.Settings))

@Composable
fun BourseApp(store: AppStore) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "market"
    val config by store.config.collectAsStateWithLifecycle()
    val root = route in tabs.map { it.route }
    Scaffold(topBar = { TopAppBar(title = { Text("Bourse AI") }, navigationIcon = {
        if (!root) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
    }) }, bottomBar = {
        if (root) NavigationBar { tabs.forEach { tab -> NavigationBarItem(selected = route == tab.route, onClick = {
            if (route != tab.route) nav.navigate(tab.route) { popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true }
        }, icon = { Icon(tab.icon, contentDescription = null) }, label = { Text(tab.title) }, modifier = Modifier.testTag("tab-${tab.route}")) } }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Surface(color = if (config.demo) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer) {
                Text(if (config.demo) "DÉMONSTRATION · données fictives locales" else "SERVEUR · ${config.server}", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            }
            NavHost(nav, startDestination = "market", modifier = Modifier.weight(1f)) {
                composable("market") { MarketScreen(store) { nav.navigate("asset/${Uri.encode(it)}") } }
                composable("asset/{symbol}") { Page(it.arguments?.getString("symbol") ?: "Actif") { Note("Le module de détail arrive dans le prochain commit.") } }
                composable("news") { Page("Informations", "Actualités et personnages publics") { Note("Module en cours de préparation.") } }
                composable("portfolio") { Page("Portefeuille") { Note("Module en cours de préparation.") } }
                composable("alerts") { Page("Alertes") { Note("Module en cours de préparation.") } }
                composable("settings") { SettingsScreen(store) }
            }
        }
    }
}

@Composable
fun SettingsScreen(store: AppStore) {
    val current by store.config.collectAsStateWithLifecycle()
    var demo by rememberSaveable(current) { mutableStateOf(current.demo) }
    var server by rememberSaveable(current) { mutableStateOf(current.server) }
    var portfolio by rememberSaveable(current) { mutableStateOf(current.portfolioId?.toString().orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    Page("Réglages", "Les clés de données de marché et les clés IA restent sur le backend.") {
        Panel("Connexion") {
            Row { Checkbox(checked = demo, onCheckedChange = { demo = it }); TextButton(onClick = { demo = !demo }) { Text("Mode démonstration") } }
            OutlinedTextField(server, { server = it }, label = { Text("Adresse du backend") }, modifier = Modifier.fillMaxWidth().testTag("server-url"), singleLine = true)
            Text("Émulateur Android : http://10.0.2.2:8081. Téléphone : adresse du PC sur le même réseau. Le backend doit être démarré.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(portfolio, { portfolio = it }, label = { Text("Identifiant du portefeuille (facultatif)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = {
                try {
                    val id = if (portfolio.isBlank()) null else portfolio.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("Identifiant de portefeuille invalide")
                    store.configure(ConnectionConfig(demo, server, id)); message = "Configuration enregistrée"
                } catch (e: IllegalArgumentException) { message = e.message }
            }) { Text("Enregistrer la connexion") }
            OutlinedButton(onClick = { store.load("health", listOf("actuator", "health")) }) { Text("Tester la connexion enregistrée") }
            DataBlock(store, "health", "Connexion non testée", { it.objectValue() }) { KeyValue("État du serveur", display(it.text("status"))) }
        }
        message?.let { Note(it) }
        Note("La version de développement autorise HTTP pour les essais locaux. Utiliser un serveur protégé en HTTPS avant tout accès externe. Aucun ordre de bourse ne sera envoyé.")
        Panel("Comprendre les résultats") { Text("Un score n’est pas une probabilité de gain. Les informations absentes ou périmées restent signalées. La saisonnalité reste descriptive.") }
    }
}
