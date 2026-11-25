@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
package lu.bourse.mobile

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.IdlingPolicies
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import lu.bourse.mobile.data.*
import lu.bourse.mobile.ui.*
import lu.bourse.mobile.ui.theme.BourseTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiJourneysTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var store: AppStore
    private val owner = ViewModelStore()
    private fun start(error: Boolean = false, large: Boolean = false, restoration: StateRestorationTester? = null, goToMarket: Boolean = true, guide: Boolean = false) {
        IdlingPolicies.setMasterPolicyTimeout(20, TimeUnit.SECONDS)
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("alert-notifications", Context.MODE_PRIVATE).edit().clear().commit()
        if (guide) ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("bourse-design", Context.MODE_PRIVATE).edit().clear().commit()
        val storage = object : ConfigStorage {
            private var value = ConnectionConfig(portfolioId = 1)
            override fun read() = value
            override fun save(config: ConnectionConfig) { value = config }
        }
        val demo = DemoTransport()
        store = AppStore(storage) { if (error) object : Transport {
            override suspend fun request(method: String, path: List<String>, query: Map<String, String>, body: com.google.gson.JsonObject?) =
                throw ApiFailure(null, "Serveur inaccessible. Vérifier la connexion et son adresse.")
        } else demo }
        owner.put("test", store)
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            BourseTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.3f else 1f)) { BourseApp(store, firstUseGuide = guide) }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.waitForIdle()
        if (goToMarket) tab("market")
    }
    @After fun close() { owner.clear() }
    private fun node(text: String) = compose.onAllNodesWithText(text).onFirst()
    private fun click(text: String) {
        val target = node(text)
        runCatching { target.performScrollTo() }
        target.assertIsDisplayed().performClick()
        compose.waitForIdle()
    }
    private fun tab(route: String) { compose.onNodeWithTag("tab-$route").performClick(); compose.waitForIdle() }
    private fun back() { compose.onNodeWithContentDescription("Retour").performClick(); compose.waitForIdle() }
    private fun input(key: String, value: String) {
        compose.onNodeWithTag("input-$key").performScrollTo().performTextReplacement(value)
        compose.waitForIdle()
    }
    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        compose.onNodeWithText("Bourse AI").assertExists()
        val folder = File("build/outputs/screenshots").apply { mkdirs() }
        val path = File(folder, "$name.png").path
        if (dialog) captureScreenRoboImage(filePath = path) else compose.onRoot().captureRoboImage(filePath = path)
    }
    private fun asset() {
        compose.onNodeWithTag("asset-NVDA").performScrollTo().performClick()
        compose.waitForIdle()
        node("Dernière clôture disponible").assertExists()
    }
    private fun analysis() { asset(); click("Comprendre le score"); click("Calculer une nouvelle analyse"); node("Score explicatif").assertExists() }

    @Test fun marketFiltersSearchAndRetainsDialogAfterRecreation() {
        val restoration = StateRestorationTester(compose)
        start(restoration = restoration)
        capture("01-marche")
        click("Cryptos")
        node("BTC-USD · Bitcoin").assertExists()
        compose.onNodeWithText("NVDA · NVIDIA").assertDoesNotExist()
        click("Tous")
        compose.onNodeWithTag("market-search").performTextReplacement("NVIDIA")
        node("NVDA · NVIDIA").assertExists()
        compose.onNodeWithText("AAPL · Apple").assertDoesNotExist()
        compose.onNodeWithTag("market-search").performTextReplacement("")
        click("Ajouter un actif")
        click("Saisie manuelle")
        input("symbol", "AMD"); input("name", "Advanced Micro Devices")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("input-symbol").assertTextContains("AMD")
        compose.onNodeWithTag("input-name").assertTextContains("Advanced Micro Devices")
        capture("02-ajouter-actif", true)
        click("Annuler")
    }
    @Test fun symbolPickerAutofillsAndHomeWorksFromAssetDetails() {
        start()
        click("Ajouter un actif")
        compose.onNodeWithTag("asset-symbol-search").performTextReplacement("MSFT")
        compose.waitUntil(5000) { compose.onAllNodesWithText("MSFT · Microsoft · USD").fetchSemanticsNodes().isNotEmpty() }
        click("MSFT · Microsoft · USD")
        node("USD · US · 09:30–16:00").assertExists()
        click("Ajouter")
        node("MSFT · Microsoft").assertExists()
        asset()
        tab("home")
        node("Tableau de bord").assertExists()
    }
    @Test fun welcomeCanBeSkippedAndReopenedFromHome() {
        start(goToMarket = false, guide = true)
        node("Bienvenue sur Bourse AI").assertExists()
        click("Passer")
        capture("33-tableau-de-bord")
        compose.onNodeWithContentDescription("Guide de démarrage").performClick()
        compose.waitForIdle()
        capture("34-guide")
        click("Commencer")
        node("Tableau de bord").assertExists()
    }

    @Test fun chartsCoverEveryTimeframeAndCalendar() {
        start(); asset(); capture("03-graphique")
        listOf("30m", "1h", "1d", "15m").forEach { click(it); assertNotNull(store.resources.value["chart:NVDA:$it"]?.value) }
        click("Voir les 12 dernières bougies")
        click("Calendrier et données attendues")
        node("État du marché").assertExists()
        capture("04-calendrier")
    }
    @Test fun analysisSnapshotsExplainChangesAndKeepFirstScoreHonest() {
        start(); analysis()
        compose.onNodeWithText("Pourquoi le score a changé").assertDoesNotExist()
        capture("05-score")
        click("Calculer une nouvelle analyse")
        node("Pourquoi le score a changé").assertExists()
        runCatching { node("Pourquoi le score a changé").performScrollTo() }
        capture("08-score-variation")
        listOf("Journée", "Année", "Mois").forEach { label ->
            click(label); click("Calculer une nouvelle analyse"); node("Score explicatif").assertExists()
        }
    }
    @Test fun ruleVariantIsValidatedAndCreatedWithoutOverwritingHistory() {
        start(); analysis()
        click("Comparer et créer une version de règles"); capture("06-versions-regles")
        click("Créer une variante"); input("technicalMax", "100")
        click("Enregistrer")
        compose.onNodeWithTag("input-technicalMax").assertExists()
        input("technicalMax", "15"); input("name", "UI_TEST_VERSION")
        capture("07-variante-formulaire", true)
        click("Enregistrer")
        node("Version de règles : 4").assertExists()
        click("Calculer une nouvelle analyse")
        assertNotNull(store.resources.value["analysis:NVDA:MONTH:4"]?.value)
    }
    @Test fun seasonalityHasOnlyMonthlyQuarterlyAndAnnualViews() {
        start(); analysis()
        click("Saisonnalité mensuelle, trimestrielle et annuelle")
        capture("09-saisonnalite-mois")
        click("Trimestres"); node("Trimestre 1").assertExists(); capture("10-saisonnalite-trimestres")
        click("Années"); node("Années complètes").assertExists(); capture("11-saisonnalite-annees")
    }
    @Test fun newsKeepsOriginalEvidenceAndValidatesHumanQuotation() {
        start(); tab("news"); capture("12-actualites")
        click("Lire et consulter les preuves"); node("Publication et preuves").assertExists()
        capture("13-publication-originale")
        click("Valider manuellement une citation exacte")
        input("actorId", "1"); input("quote", "Nous envisageons de nouveaux droits de douane.")
        capture("14-citation-formulaire", true)
        click("Confirmer l’attribution")
        assertNotNull(store.resources.value["quote-write"]?.value)
        assertNull(store.resources.value["quote-write"]?.error)
    }
    @Test fun feedControlsPersonsAndCompanyImpactsRemainSeparate() {
        start(); tab("news"); click("Sources"); capture("15-sources")
        compose.onAllNodes(isToggleable()).onFirst().performClick(); compose.waitForIdle()
        click("Collecter ce flux")
        node("Derniers imports").performScrollTo(); capture("16-journal-imports")
        assertNull(store.resources.value["feed-write"]?.error)
        back(); click("Personnes"); click("Voir ses publications")
        node("Publications de la personne 1").performScrollTo(); capture("17-personnes-publications")
        back(); click("Impacts"); click("Voir les entreprises affectées")
        node("Contribution signée").performScrollTo(); capture("18-impacts-entreprise")
    }
    @Test fun portfolioDeclarationsRiskMarksAndPriceScenariosAreUsable() {
        start(); tab("portfolio"); capture("19-portefeuille-risque")
        click("Créer"); capture("20-creation-portefeuille", true); click("Annuler")
        click("Choisir un identifiant"); capture("21-choix-portefeuille", true); click("Annuler")
        click("Déclarer un achat effectué")
        compose.onNodeWithTag("portfolio-symbol-search").performScrollTo().performTextReplacement("Apple")
        click("AAPL · Apple · USD")
        node("Moyen · bougies de 1 h").assertExists()
        input("quantity", "1"); input("price", "100"); input("fx", "0.93")
        capture("22-declaration-achat", true)
        click("Enregistrer la déclaration")
        assertNull(store.resources.value["portfolio-write"]?.error)
        assertEquals(2, positions(store.resources.value.getValue("positions:1").value!!).size)
        node("Perte globale").performScrollTo(); capture("32-risque-incomplet")
        click("Déclarer vente partielle ou nouvel achat")
        input("quantity", "1"); input("price", "160"); input("fx", "0.93")
        capture("23-declaration-operation", true); click("Enregistrer l’opération effectuée")
        assertNull(store.resources.value["portfolio-write"]?.error)
        click("Renseigner un prix et un FX datés")
        input("price", "150"); input("fx", "0.93")
        capture("24-prix-fx-dates", true); click("Enregistrer")
        assertNull(store.resources.value["portfolio-write"]?.error)
        click("Plan et scénarios illustratifs"); capture("25-plan-risque")
        node("Scénario personnel · NVDA").performScrollTo()
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(10f) }
        node("Prix hypothétique").performScrollTo(); capture("26-scenarios-prix")
        node("Variation du prix d’entrée : 10.00%").assertExists()
    }
    @Test fun acknowledgedAlertsStayInHistoryAndSettingsValidateConnections() {
        start(); tab("alerts"); capture("27-alertes")
        click("Marquer comme lue")
        node("Aucune alerte correspondant à ce filtre. Cela ne garantit pas l’absence de risque.").assertExists()
        click("Toutes")
        node("SOCIAL_EVENT_REVIEW · ACKNOWLEDGED").assertExists()
        node("Signal à réexaminer").performScrollTo(); capture("28-alertes-lues")
        tab("settings")
        compose.onNodeWithTag("server-url").performTextReplacement("pas-une-url")
        click("Enregistrer la connexion"); node("Adresse du serveur invalide").assertExists()
        compose.onNodeWithTag("server-url").performTextReplacement("http://10.0.2.2:8081")
        click("Enregistrer la connexion"); click("Tester la connexion enregistrée")
        node("UP").assertExists(); capture("29-reglages")
    }
    @Test @Config(sdk = [35], qualifiers = "w412dp-h915dp-night-420dpi")
    fun darkThemeAndReadableNetworkError() {
        start(error = true, large = true)
        node("Serveur inaccessible. Vérifier la connexion et son adresse.").assertExists()
        capture("31-erreur-reseau-grand-texte")
    }
}
