package lu.bourse.mobile.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

val Rising = Color(0xFF37E5AE)
val Falling = Color(0xFFFF8694)
val RiskAccent = Color(0xFFFFCD72)
val ResearchAccent = Color(0xFFCEAFFF)

private val NightMarket = darkColorScheme(
    background = Color(0xFF061321), surface = Color(0xFF0C2033),
    surfaceContainer = Color(0xFF10283E), surfaceContainerHigh = Color(0xFF15314A),
    primary = Color(0xFF5AE4FF), onPrimary = Color(0xFF002834),
    primaryContainer = Color(0xFF103C57), onPrimaryContainer = Color(0xFFACF2FF),
    secondaryContainer = Color(0xFF17334D), onSecondaryContainer = Color(0xFFD5E9FA),
    tertiary = ResearchAccent, tertiaryContainer = Color(0xFF302551),
    onTertiaryContainer = Color(0xFFF0DDFF),
    onBackground = Color(0xFFF4F8FF), onSurface = Color(0xFFF4F8FF),
    onSurfaceVariant = Color(0xFFB9CCDF), outline = Color(0xFF6989A6),
    outlineVariant = Color(0xFF2A4964), error = Falling
)

private val baseType = Typography()
private val MarketType = baseType.copy(
    headlineLarge = baseType.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
    headlineMedium = baseType.headlineMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
    titleLarge = baseType.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = baseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = baseType.labelLarge.copy(fontWeight = FontWeight.SemiBold)
)

@Composable
fun BourseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NightMarket, typography = MarketType, content = content)
}
