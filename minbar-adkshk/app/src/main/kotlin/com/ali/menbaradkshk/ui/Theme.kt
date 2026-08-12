package com.ali.menbaradkshk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.ali.menbaradkshk.R

// هوية «منبر» — مشتقّة **فعلياً من أيقونة التطبيق** (لا من theme.dart القديم):
// حبر ليليّ (خلفية الأيقونة #020E1D) + سُلّم ذهبيّ + جوهر سماويّ.
// الرموز أدناه مقيسة من الأيقونة نفسها، ومنها تُبنى كل ألوان الواجهة.

// ---- الحبر (الخلفيات والأسطح الداكنة) ----
val Ink900 = Color(0xFF01050B)
val Ink800 = Color(0xFF020E1D) // ★ خلفية الأيقونة نفسها
val Ink700 = Color(0xFF031623)
val Ink600 = Color(0xFF05232C)
val Ink500 = Color(0xFF072A33)

// ---- الذهب (سُلّم الأيقونة) ----
// ⚠️ قاعدة إلزامية: Gold100–Gold800 للوضع الداكن والرسوم الكبيرة فقط.
// وحده Gold900 يصلح **نصّاً على خلفية فاتحة** (6.19 مقابل 3.69 لـGold800).
val Gold100 = Color(0xFFFEE469)
val Gold200 = Color(0xFFFDD459)
val Gold300 = Color(0xFFF9C549)
val Gold400 = Color(0xFFF4BB44)
val Gold500 = Color(0xFFE8A837)
val Gold600 = Color(0xFFD7962B)
val Gold700 = Color(0xFFC88825)
val Gold800 = Color(0xFFB8771B)
val Gold900 = Color(0xFF885618)

// ---- الجوهر السماوي ----
// ⚠️ #068F93 يعبر 4.5:1 على Ink800 وInk700 فقط (يهبط إلى 3.86 على Ink500):
// نصّاً على الخلفية والسطح الأساس فقط، وأيقونات/حدوداً على الأسطح المرتفعة.
val Gem100 = Color(0xFF068F93)
val Gem200 = Color(0xFF02858E)
val Gem300 = Color(0xFF027983)
val Gem400 = Color(0xFF036E79)
val Gem500 = Color(0xFF025864)

// أسماء الهوية القديمة تبقى كما هي (يستوردها بقيّة الملفات) — تغيّرت قيمها فقط
// لتصير مشتقّة من الأيقونة.
val Teal = Gem500
val Slate = Ink600
val Gold = Gold900
val BlueBrand = Color(0xFF14405E)
val GreenBrand = Color(0xFF186B65)
val OrangeBrand = Color(0xFF7A4514)
val SecondaryGold = Gold400
val PositiveOnDark = Color(0xFF67AF96)
val GoldOnDark = Gold200

// أسطح داكنة/فاتحة: الداكنة حبر الأيقونة نفسه، والفاتحة رماديّ أزرق محايد.
val DarkAppBar = Ink700
val DarkScaffold = Ink800
val DarkSurface = Ink700
val LightScaffold = Color(0xFFF4F7FA)
val LightSurface = Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = Gem500,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EBEC),
    onPrimaryContainer = Color(0xFF034853),
    secondary = Gold900,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBEFCF),
    onSecondaryContainer = Color(0xFF4A3208),
    tertiary = Color(0xFF085956),
    onTertiary = Color.White,
    background = LightScaffold,
    onBackground = Ink800,
    surface = LightSurface,
    onSurface = Ink800,
    surfaceVariant = Color(0xFFE3EAF2),
    onSurfaceVariant = Color(0xFF46586A),
    surfaceContainer = Color(0xFFE9EFF5),
    surfaceContainerHigh = Color(0xFFDFE7F0),
    outline = Color(0xFF6E7F90),
    outlineVariant = Color(0xFFC3CFDB),
    error = Color(0xFFA62A1E),
    onError = Color.White,
    errorContainer = Color(0xFFFBDCD7),
    onErrorContainer = Color(0xFF5B1109),
)

private val DarkColors = darkColorScheme(
    primary = Gold400,
    onPrimary = Ink800,
    primaryContainer = Color(0xFF4A3208),
    onPrimaryContainer = Color(0xFFFBEFCF),
    secondary = PositiveOnDark,
    onSecondary = Ink800,
    secondaryContainer = Color(0xFF123B33),
    onSecondaryContainer = Color(0xFFC7E7DA),
    tertiary = Gem100,
    onTertiary = Ink800,
    background = Ink800,
    onBackground = Color(0xFFE6EDF5),
    surface = Ink700,
    onSurface = Color(0xFFE6EDF5),
    surfaceVariant = Color(0xFF0E2231),
    onSurfaceVariant = Color(0xFFA6B8C9),
    surfaceContainer = Ink600,
    surfaceContainerHigh = Ink500,
    outline = Color(0xFF6B7D8F),
    outlineVariant = Color(0xFF1A2B3A),
    error = Color(0xFFFF9E90),
    onError = Ink800,
    errorContainer = Color(0xFF5B221A),
    onErrorContainer = Color(0xFFFFD9D2),
)

/// ألوان شريط التطبيق العلوي (AppBar) — داكن في الوضعين بنصّ أبيض، كي تبقى
/// أيقونات شريط الحالة فاتحة دائماً كما يفترض MainActivity.
val AppBarBackgroundLight = Gem500
val AppBarBackgroundDark = DarkAppBar
val AppBarForeground = Color.White

private val Amiri = FontFamily(
    Font(R.font.amiri, FontWeight.Normal),
    Font(R.font.amiri_bold, FontWeight.Bold),
)
private val MinbarTypography = Typography(
    displaySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = Amiri,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
    ),
    headlineMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = Amiri,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = Amiri,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
    ),
    titleMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = Amiri,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
    ),
    bodyLarge = androidx.compose.ui.text.TextStyle(fontFamily = Amiri, fontSize = 18.sp),
    bodyMedium = androidx.compose.ui.text.TextStyle(fontFamily = Amiri, fontSize = 16.sp),
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = Amiri,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
    ),
)

/// هل الوضع الحالي داكن؟ يُستخدم لاختيار درجات الإبراز على الأسطح الداكنة.
@Composable
fun isDarkTheme(themeMode: String): Boolean = when (themeMode) {
    "dark" -> true
    "system" -> androidx.compose.foundation.isSystemInDarkTheme()
    else -> false
}

@Composable
fun MinbarTheme(themeMode: String, fontScale: Float, content: @Composable () -> Unit) {
    val dark = isDarkTheme(themeMode)
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontScale),
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = MinbarTypography,
            content = content,
        )
    }
}
