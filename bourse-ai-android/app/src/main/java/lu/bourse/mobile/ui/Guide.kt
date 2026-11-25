package lu.bourse.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

@Composable
fun FieldHelp(topic: String, explanation: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Default.HelpOutline, "Comprendre $topic") }
    if (open) AlertDialog(
        onDismissRequest = { open = false }, title = { Text(topic) },
        text = { Text(explanation) },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Compris") } }
    )
}

private val guideSteps = listOf(
    "Votre tableau de bord" to "Retrouvez la valeur de votre portefeuille et les actifs que vous suivez.",
    "Les graphiques et les analyses" to "Dans Marchés, ouvrez une action pour voir son cours, la moyenne mobile, le MACD et le RSI. Touchez la courbe ou utilisez le curseur pour lire une date.",
    "Vos limites de risque" to "Dans Portefeuille, indiquez votre perte maximale et consultez le budget restant.",
)

@Composable
fun WelcomeGuide(close: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val (title, description) = guideSteps[step]
    val accent = when (step) {
        0 -> MaterialTheme.colorScheme.primary
        1 -> lu.bourse.mobile.ui.theme.ResearchAccent
        else -> lu.bourse.mobile.ui.theme.RiskAccent
    }
    Page("Bienvenue sur Bourse AI", "Étape ${step + 1} sur ${guideSteps.size}") {
        LinearProgressIndicator(progress = { (step + 1f) / guideSteps.size },
            modifier = Modifier.fillMaxWidth(), color = accent)
        Box(Modifier.fillMaxWidth().height(114.dp).background(
            Brush.horizontalGradient(listOf(accent.copy(alpha = .30f), MaterialTheme.colorScheme.surfaceContainerHigh)),
            RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.ShowChart, contentDescription = null, modifier = Modifier.size(64.dp), tint = accent)
        }
        Panel(title, accent = accent) { Text(description) }
        Button(onClick = { if (step == guideSteps.lastIndex) close() else step++ },
            modifier = Modifier.fillMaxWidth()) {
            Text(if (step == guideSteps.lastIndex) "Ouvrir le tableau de bord" else "Continuer")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { step-- }, enabled = step > 0) { Text("Précédent") }
            TextButton(onClick = close) { Text("Passer") }
        }
    }
}
