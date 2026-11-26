package lu.bourse.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import lu.bourse.mobile.data.*
import lu.bourse.mobile.ui.theme.ResearchAccent
import java.time.Instant

/** Renders server evidence and effective score deltas; never computes a market signal locally. */
@Composable
fun TechnicalAnalysisContent(report: JsonObject, effectiveOpportunity: String? = null, effectiveRisk: String? = null) {
    val analysis = report.child("analysis")
    KeyValue("Intervalle analysé", display(report.text("timeframe")))
    KeyValue("Dernière observation analysée", dateLabel(report.text("referenceTime")))
    val usable = report.flag("usable") == true
    Text(if (report.text("status") == "DEMO_ONLY") "Exemples fictifs pour découvrir les indicateurs et les structures." else if (usable) "Analyse expérimentale sur les dernières bougies clôturées." else when(report.text("status")) {
        "STALE" -> "Données anciennes : aucune contribution technique appliquée au score."
        "CALENDAR_UNVERIFIED" -> "Calendrier non vérifié : aucune contribution technique appliquée."
        "HALTED" -> "Cotation suspendue : aucune contribution technique appliquée."
        else -> "Historique continu insuffisant : certaines figures restent indisponibles."
    })
    if (effectiveOpportunity != null && effectiveRisk != null) {
        KeyValue("Points techniques appliqués · opportunité", effectiveOpportunity)
        KeyValue("Points techniques appliqués · risque", effectiveRisk)
        Text("Contribution déjà incluse dans les scores affichés, après plafonds et adaptation à l’horizon. La fiabilité n’est pas augmentée par ces règles.", style = MaterialTheme.typography.bodySmall)
    }
    if (analysis == null) { Text("Détails techniques indisponibles."); return }
    val f = analysis.child("features")
    KeyValue("Bougies consécutives utilisables", analysis.id("continuousCandles")?.toString() ?: "Indisponible")
    f?.let {
        KeyValue("ATR · amplitude moyenne en %", it.decimal("atr14_pct")?.movePointRight(2)?.let { v -> amount(v) } ?: "Indisponible")
        KeyValue("Bandes de Bollinger · position", amount(it.decimal("bollinger_position")))
        KeyValue("Tendance SMA 20 / 50 · écart en %", it.decimal("sma20_50_gap")?.movePointRight(2)?.let { v -> amount(v) } ?: "Indisponible")
        KeyValue("Cassure Donchian · 20 périodes", when(it.decimal("donchian_breakout")?.toInt()) {
            1 -> "Clôture au-dessus des plus hauts précédents"
            -1 -> "Clôture sous les plus bas précédents"
            0 -> "Aucune cassure reconnue"
            else -> "Indisponible"
        })
    }
    val signals = analysis.list("signals")
    if (signals.isEmpty()) Text("Pas de structure triple ou Elliott reconnue sur la fenêtre disponible.")
    signals.take(3).forEach { signal ->
        val code = signal.text("code").orEmpty()
        val term = when {
            code == "TRIPLE_BOTTOM" -> FinancialTerm.TRIPLE_BOTTOM
            code == "TRIPLE_TOP" -> FinancialTerm.TRIPLE_TOP
            else -> FinancialTerm.ELLIOTT
        }
        val title = when(code) {
            "TRIPLE_BOTTOM" -> "Triple creux"
            "TRIPLE_TOP" -> "Triple sommet"
            "ELLIOTT_ABC_UP" -> "Elliott · correction A–B–C après une hausse"
            "ELLIOTT_ABC_DOWN" -> "Elliott · correction A–B–C après une baisse"
            "ELLIOTT_IMPULSE_UP" -> "Elliott · cinq vagues de hausse"
            "ELLIOTT_IMPULSE_DOWN" -> "Elliott · cinq vagues de baisse"
            else -> "Structure exploratoire"
        }
        Panel(title, accent = ResearchAccent, trailing = { FinancialHelp(term) }) {
            Text(when(signal.text("status")) {
                "BREAKOUT_OBSERVED" -> "Franchissement du niveau intermédiaire observé."
                "FORMING" -> "Figure en formation : franchissement encore attendu, aucun point ajouté pour cette figure."
                else -> "Comptage simplifié exploratoire ; une nouvelle hausse ou baisse n’est pas garantie."
            })
            KeyValue("Figure connue à partir de", dateLabel(signal.text("availableAt")))
            val points = signal.list("points")
            val dated = points.mapNotNull { p ->
                val time = runCatching { Instant.parse(p.required("time")) }.getOrNull()
                val price = p.decimal("price")
                if (time == null || price == null) null else Triple(time, price, p.text("label").orEmpty())
            }
            if (dated.isNotEmpty()) {
                Text("Repères observés : " + dated.joinToString(" · ") { it.third })
                MarketPlot("Structure observée", dated.map { it.first },
                    listOf(PlotLine("Sommets et creux", dated.map { it.second.toDouble() }, ResearchAccent)), report.text("currency").orEmpty())
                Text("Les repères sont reconnus deux bougies après leur extrême. La ligne relie les points observés ; elle ne prédit pas les prix futurs.", style = MaterialTheme.typography.bodySmall)
            }
            signal.decimal("support")?.let { KeyValue("Support observé", priceText(it, report.text("currency"))) }
            signal.decimal("resistance")?.let { KeyValue("Résistance observée", priceText(it, report.text("currency"))) }
        }
    }
    var details by remember { mutableStateOf(false) }
    TextButton(onClick = { details = !details }) { Text(if(details) "Masquer les contributions" else "Comprendre les contributions techniques") }
    if (details) analysis.list("contributions").forEach { row ->
        val title = when(row.text("code")) {
            "CANDLE_PATTERNS" -> "Marteau, doji et engloutissements"
            "DONCHIAN_20" -> "Cassures de Donchian"
            "ATR_14" -> "ATR · amplitude"
            "BOLLINGER_20" -> "Bandes de Bollinger"
            "SMA_20_50" -> "Tendance des moyennes mobiles"
            "TRIPLE_BOTTOM" -> "Triple creux"
            "TRIPLE_TOP" -> "Triple sommet"
            else -> "Vagues d’Elliott"
        }
        KeyValue(title, "Opportunité ${display(row.text("opportunity"))} · risque +${display(row.text("risk"))}")
        Text(display(row.text("explanation")), style = MaterialTheme.typography.bodySmall)
    }
    Text("Les contributions ci-dessus sont brutes. Leur total est plafonné avant utilisation et ne mesure pas une probabilité de gain.", style = MaterialTheme.typography.bodySmall)
}
