@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
package lu.bourse.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import lu.bourse.mobile.data.*
import lu.bourse.mobile.ui.*
import lu.bourse.mobile.ui.theme.BourseTheme
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FinancialHelpUiTest {
    @get:Rule val compose = createComposeRule()
    private val chart = runBlocking { chartData(DemoTransport().request("GET", api("charts", "NVDA"))) }
    @Test fun hammerExplanationOpensClosesAndSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { BourseTheme { Page("Exemple fictif") { CandlePatternsPanel(chart) } } }
        compose.onNodeWithText("Marteau").assertExists()
        compose.onNodeWithContentDescription("Comprendre Marteau").performScrollTo().performClick()
        compose.onNodeWithText(FinancialTerm.HAMMER.definition, substring = true).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Compris").assertIsDisplayed().performClick()
        compose.onNodeWithText("Compris").assertDoesNotExist()
        val folder = File("build/outputs/screenshots").apply { mkdirs() }
        compose.onRoot().captureRoboImage(File(folder, "figures-bougies.png").path)
    }
    @Test fun sharedFinancialFieldsHaveAccessibleDefinitionsAtLargerFontSize() {
        compose.setContent { BourseTheme {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, 1.3f)) {
                Page("Définitions") { Panel("RSI · 14 périodes") { KeyValue("Volatilité annualisée", "20 %") } }
            }
        } }
        compose.onNodeWithContentDescription("Comprendre RSI · force des mouvements").performClick()
        compose.onNodeWithText(FinancialTerm.RSI.definition, substring = true).assertIsDisplayed()
        compose.onNodeWithText("Compris").performClick()
        compose.onNodeWithContentDescription("Comprendre Volatilité").performClick()
        compose.onNodeWithText(FinancialTerm.VOLATILITY.definition, substring = true).assertIsDisplayed()
    }
    @Test fun glossaryCanBeSearchedAndShowsExampleAndLimits() {
        compose.setContent { BourseTheme { Page("Aide") { FinancialGlossaryButton() } } }
        compose.onNodeWithText("Lexique financier").performClick()
        compose.onNodeWithTag("financial-glossary-search").performTextInput("doji")
        compose.onNodeWithText("Doji", useUnmergedTree = true).performClick()
        compose.onNodeWithText(FinancialTerm.DOJI.definition, substring = true).assertExists()
        compose.onNodeWithText("Fermer").performScrollTo().performClick()
        compose.onNodeWithText("Les termes expliqués").assertDoesNotExist()
    }
    @Test fun missingDetectorIsUnavailableRatherThanNoPatternDetected() {
        compose.setContent { BourseTheme { Page("Historique") { CandlePatternsPanel(chart.copy(patterns = null)) } } }
        compose.onNodeWithText("Détection indisponible", substring = true).assertExists()
        compose.onNodeWithText("Aucune figure reconnue", substring = true).assertDoesNotExist()
    }
}
