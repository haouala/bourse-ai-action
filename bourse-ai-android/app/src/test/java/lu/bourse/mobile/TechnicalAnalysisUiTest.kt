@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
package lu.bourse.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
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
@Config(sdk=[35],qualifiers="w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TechnicalAnalysisUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun effectivePointsAndElliottDefinitionAreVisibleWithoutInventingPredictions(){
        val report=demoTechnical("NVDA","1d",100.0)
        compose.setContent { BourseTheme { Page("Analyse fictive") { TechnicalAnalysisContent(report,"2","1") } } }
        compose.onNodeWithText("Points techniques appliqués · opportunité").assertExists()
        compose.onNodeWithText("Elliott · cinq vagues de hausse").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Comprendre Vagues d’Elliott").performScrollTo().performClick()
        compose.onNodeWithText(FinancialTerm.ELLIOTT.definition,substring=true).assertIsDisplayed()
        compose.onNodeWithText("Compris").performClick()
        compose.onNodeWithText("Figure connue à partir de").performScrollTo().assertIsDisplayed()
        val folder=File("build/outputs/screenshots").apply{mkdirs()}
        compose.onRoot().captureRoboImage(File(folder,"technique-elliott-score.png").path)
    }
    @Test fun staleInputsExplicitlyShowNoTechnicalContribution(){
        val report=demoTechnical("NVDA","1d",100.0).apply{addProperty("usable",false);addProperty("status","STALE")}
        compose.setContent { BourseTheme { Page("Analyse") { TechnicalAnalysisContent(report,"0","0") } } }
        compose.onNodeWithText("Données anciennes",substring=true).assertExists()
        compose.onNodeWithText("Points techniques appliqués · risque").assertExists()
    }
}
