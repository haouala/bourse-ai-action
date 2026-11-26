package lu.bourse.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import lu.bourse.mobile.data.FinancialTerm

@Composable
fun FieldHelp(topic: String, explanation: String) {
    var open by rememberSaveable(topic) { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Default.Info, "Comprendre $topic", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (open) AlertDialog(
        onDismissRequest = { open = false }, title = { Text(topic) },
        text = { Text(explanation, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Compris") } }
    )
}

@Composable
fun FinancialHelp(term: FinancialTerm) {
    FieldHelp(term.title, financialExplanation(term))
}

private fun financialExplanation(term: FinancialTerm): String = buildString {
    append(term.definition)
    append("\n\nExemple\n"); append(term.example)
    if (term.limitation.isNotBlank()) { append("\n\nÀ retenir\n"); append(term.limitation) }
}

@Composable
fun FinancialGlossaryButton() {
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("Lexique financier") }
    if (open) Panel("Les termes expliqués") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Rechercher un terme") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("financial-glossary-search"))
            val terms = FinancialTerm.entries.filter { it.title.contains(query.trim(), true) || it.definition.contains(query.trim(), true) }
            if (terms.isEmpty()) Text("Aucun terme correspondant.")
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(terms, key = { it.name }) { term ->
                    Column {
                        TextButton(onClick = { selected = if (selected == term.name) null else term.name }, modifier = Modifier.fillMaxWidth()) { Text(term.title) }
                        if (selected == term.name) Text(financialExplanation(term))
                    }
                }
            }
        }
        TextButton(onClick = { open = false }) { Text("Fermer") }
    }
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
