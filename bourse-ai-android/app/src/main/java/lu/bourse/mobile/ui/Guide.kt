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

@Composable
fun WelcomeGuide(close: () -> Unit) {
    Page("Bienvenue sur Bourse AI") {
        Box(Modifier.fillMaxWidth().height(114.dp).background(
            Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)),
            RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.ShowChart, contentDescription = null, modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        Panel("Votre tableau de bord") {
            Text("Retrouvez la valeur connue de votre portefeuille et les actifs que vous suivez.")
        }
        Panel("Les analyses et les prévisions") {
            Text("Choisissez un actif dans Intelligence. Les résultats présentent les observations historiques et les estimations disponibles.")
        }
        Panel("Vos limites de risque") {
            Text("Dans Portefeuille, indiquez votre perte maximale et consultez le budget restant.")
        }
        Button(close, Modifier.fillMaxWidth()) { Text("Commencer") }
        TextButton(close, Modifier.align(Alignment.CenterHorizontally)) { Text("Passer") }
    }
}
