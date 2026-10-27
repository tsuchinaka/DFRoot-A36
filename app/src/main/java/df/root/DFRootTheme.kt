package df.root

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val Violet = Color(0xFF5B3DF5)
private val Pink = Color(0xFFE0457B)
private val Orange = Color(0xFFFF7A2F)

private val LightScheme = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF1B0B5E),
    secondary = Pink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E4),
    onSecondaryContainer = Color(0xFF3C0021),
    tertiary = Orange,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE0CC),
    onTertiaryContainer = Color(0xFF331200),
    background = Color(0xFFFDF8FF),
    onBackground = Color(0xFF1B1029),
    surface = Color(0xFFFDF8FF),
    onSurface = Color(0xFF1B1029),
    surfaceVariant = Color(0xFFE8E0F0),
    onSurfaceVariant = Color(0xFF4A4458),
    outline = Color(0xFF7B7488),
    outlineVariant = Color(0xFFCCC4D8),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFC9B8FF),
    onPrimary = Color(0xFF2C1178),
    primaryContainer = Color(0xFF432C8F),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFFFFB0C9),
    onSecondary = Color(0xFF5E1136),
    secondaryContainer = Color(0xFF7D294D),
    onSecondaryContainer = Color(0xFFFFD9E4),
    tertiary = Color(0xFFFFB68C),
    onTertiary = Color(0xFF542100),
    tertiaryContainer = Color(0xFF7A3500),
    onTertiaryContainer = Color(0xFFFFDCC7),
    background = Color(0xFF120C1E),
    onBackground = Color(0xFFE8E0F0),
    surface = Color(0xFF120C1E),
    onSurface = Color(0xFFE8E0F0),
    surfaceVariant = Color(0xFF4A4458),
    onSurfaceVariant = Color(0xFFCCC4D8),
    outline = Color(0xFF958E9F),
    outlineVariant = Color(0xFF4A4458),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val DfrootTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Black,
        fontSize = 64.sp,
        lineHeight = 60.sp,
        letterSpacing = (-0.04).em,
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Black,
        fontSize = 48.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.03).em,
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 38.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.02).em,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.02).em,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.01.em,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.04.em,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.06.em,
    ),
)

private val DfrootShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(36.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DFRootTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = when {
        dynamic && dark -> runCatching { dynamicDarkColorScheme(context) }.getOrElse { DarkScheme }
        dynamic -> runCatching { dynamicLightColorScheme(context) }.getOrElse { LightScheme }
        dark -> DarkScheme
        else -> LightScheme
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = DfrootTypography,
        shapes = DfrootShapes,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
