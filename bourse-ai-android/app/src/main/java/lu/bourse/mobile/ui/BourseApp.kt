@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package lu.bourse.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import lu.bourse.mobile.*
import lu.bourse.mobile.data.*

private data class Tab(val route: String, val title: String, val icon: ImageVector)
private val tabs = listOf(Tab("home", "Accueil", Icons.Default.Home), Tab("market", "Marchés", Icons.Default.ShowChart), Tab("intelligence", "Intelligence", Icons.Default.Psychology), Tab("portfolio", "Portefeuille", Icons.Default.AccountBalanceWallet), Tab("settings", "Profil", Icons.Default.Person))

@Composable
fun BourseApp(store: AppStore, alertRequest: Int = 0, firstUseGuide: Boolean = false) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    val config by store.config.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("bourse-design", 0) }
    var guideOpen by rememberSaveable { mutableStateOf(firstUseGuide && !preferences.getBoolean("guideSeen", false)) }
    if (guideOpen && alertRequest == 0) {
        androidx.activity.compose.BackHandler {
            preferences.edit().putBoolean("guideSeen", true).apply()
            guideOpen = false
        }
        Scaffold { padding -> Box(Modifier.padding(padding)) {
            WelcomeGuide {
                preferences.edit().putBoolean("guideSeen", true).apply()
                guideOpen = false
            }
        } }
        return
    }
    val notice by store.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice?.id) {
        val current=notice ?: return@LaunchedEffect
        val message = current.requestId?.let { "${current.message} · Réf. $it" } ?: current.message
        snackbar.showSnackbar(message, withDismissAction = true,
            duration = if (current.severity == "INFO") SnackbarDuration.Short else SnackbarDuration.Long)
        store.dismissNotice()
    }
    LaunchedEffect(alertRequest) { if (alertRequest > 0) nav.navigate("alerts") { launchSingleTop = true } }
    LaunchedEffect(config) { AlertNotifications.reconcile(context, config) }
    val root = route in tabs.map { it.route } || route == "news" || route == "alerts"
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = { TopAppBar(title = { Text("Bourse AI", style = MaterialTheme.typography.titleLarge) }, actions = {
        IconButton(onClick = { nav.navigate("news") { launchSingleTop = true } }, modifier = Modifier.testTag("tab-news")) { Icon(Icons.Default.Article, "Actualités") }
        IconButton(onClick = { nav.navigate("alerts") { launchSingleTop = true } }, modifier = Modifier.testTag("tab-alerts")) { Icon(Icons.Default.Notifications, "Alertes") }
        IconButton(onClick = { nav.navigate("guide") { launchSingleTop = true } }) { Icon(Icons.Default.HelpOutline, "Guide de démarrage") }
    }, navigationIcon = {
        if (!root) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
    }) }, bottomBar = {
        if (root) NavigationBar { tabs.forEach { tab -> NavigationBarItem(selected = route == tab.route, onClick = {
            if (route != tab.route) nav.navigate(tab.route) { popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true }
        }, icon = { Icon(tab.icon, contentDescription = null) }, label = { Text(tab.title, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.testTag("tab-${tab.route}")) } }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Surface(color = if (config.demo) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer) {
                Text(if (config.demo) "DÉMONSTRATION · données fictives locales" else "SERVEUR · ${config.server}", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            }
            NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f),
                enterTransition = { fadeIn(tween(150)) }, exitTransition = { fadeOut(tween(100)) },
                popEnterTransition = { fadeIn(tween(150)) }, popExitTransition = { fadeOut(tween(100)) }) {
                composable("guide") { WelcomeGuide { nav.popBackStack() } }
                composable("home") {
                    HomeScreen(store, { nav.navigate("market") }, { nav.navigate("portfolio") }, { nav.navigate("intelligence") })
                }
                composable("intelligence") {
                    IntelligencePicker(store) { nav.navigate("research/${Uri.encode(it)}") }
                }
                composable("market") { MarketScreen(store) { nav.navigate("asset/${Uri.encode(it)}") } }
                composable("asset/{symbol}") { e ->
                    val symbol = e.arguments?.getString("symbol").orEmpty()
                    AssetScreen(store, symbol, { nav.navigate("analysis/${Uri.encode(symbol)}") }, { nav.navigate("calendar/${Uri.encode(symbol)}") }, { nav.navigate("research/${Uri.encode(symbol)}") })
                }
                composable("research/{symbol}") { ResearchDashboardScreen(store, it.arguments?.getString("symbol").orEmpty()) }
                composable("calendar/{symbol}") { CalendarScreen(store, it.arguments?.getString("symbol").orEmpty()) }
                composable("analysis/{symbol}") { e ->
                    val symbol = e.arguments?.getString("symbol").orEmpty()
                    val rulesId by e.savedStateHandle.getStateFlow<Long?>("rulesId", null).collectAsStateWithLifecycle()
                    AnalysisScreen(store, symbol, rulesId, { nav.navigate("seasonality/${Uri.encode(symbol)}") }, { nav.navigate("rules") })
                }
                composable("seasonality/{symbol}") { SeasonalityScreen(store, it.arguments?.getString("symbol").orEmpty()) }
                composable("rules") { RulesScreen(store) { id -> nav.previousBackStackEntry?.savedStateHandle?.set<Long?>("rulesId", id); nav.popBackStack() } }
                composable("news") { NewsScreen(store, { nav.navigate("news-item/$it") }, { nav.navigate("sources") }, { nav.navigate("persons") }, { nav.navigate("events") }) }
                composable("news-item/{id}") { NewsDetailScreen(store, it.arguments?.getString("id")?.toLongOrNull() ?: 0) }
                composable("sources") { SourcesScreen(store) }
                composable("persons") { PersonsScreen(store) }
                composable("events") { EventsScreen(store) }
                composable("portfolio") { PortfolioScreen(store, { portfolio, position -> nav.navigate("plan/$portfolio/$position") }, { nav.navigate("asset/${Uri.encode(it)}") }) }
                composable("plan/{portfolio}/{position}") { PositionPlanScreen(store, it.arguments?.getString("portfolio")?.toLongOrNull() ?: 0, it.arguments?.getString("position")?.toLongOrNull() ?: 0) }
                composable("alerts") { AlertsScreen(store) }
                composable("settings") { SettingsScreen(store) { nav.navigate("admin") } }
                composable("admin") { AdminScreen(config) }
            }
        }
    }
}

@Composable
fun SettingsScreen(store: AppStore, openAdmin: () -> Unit = {}) {
    val current by store.config.collectAsStateWithLifecycle()
    var demo by rememberSaveable(current) { mutableStateOf(current.demo) }
    var server by rememberSaveable(current) { mutableStateOf(current.server) }
    var portfolio by rememberSaveable(current) { mutableStateOf(current.portfolioId?.toString().orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    Page("Réglages", "Les clés de données de marché et les clés IA restent sur le backend.") {
        Panel("Connexion") {
            Row { Checkbox(checked = demo, onCheckedChange = { demo = it; portfolio = "" }); TextButton(onClick = { demo = !demo; portfolio = "" }) { Text("Mode démonstration") } }
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
        if (!current.demo) {
            OutlinedButton(onClick = openAdmin, modifier = Modifier.testTag("open-admin")) {
                Text("Administration")
            }
        }
        message?.let { Note(it) }
        Note("La version de développement autorise HTTP pour les essais locaux. Utiliser un serveur protégé en HTTPS avant tout accès externe. Aucun ordre de bourse ne sera envoyé.")
        Panel("Comprendre les résultats") { Text("Un score n’est pas une probabilité de gain. Les informations absentes ou périmées restent signalées. La saisonnalité reste descriptive.") }
    }
}
