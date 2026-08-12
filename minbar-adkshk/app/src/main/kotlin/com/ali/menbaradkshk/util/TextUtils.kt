package com.ali.menbaradkshk.util

import com.ali.menbaradkshk.data.Lesson
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

private val tashkeel = Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
private val tatweel = Regex("\\u0640")

fun normalizeArabic(input: String): String {
    return Normalizer.normalize(input.trim().lowercase(Locale.ROOT), Normalizer.Form.NFKC)
        .replace(tashkeel, "")
        .replace(tatweel, "")
        .replace(Regex("[أإآٱ]"), "ا")
        .replace("ى", "ي")
        .replace("ة", "ه")
        .replace("ؤ", "و")
        .replace("ئ", "ي")
}

fun arabicContains(haystack: String, needle: String): Boolean =
    needle.isBlank() || normalizeArabic(haystack).contains(normalizeArabic(needle))

fun formatDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return ""
    val totalSeconds = milliseconds / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.ROOT, minutes, seconds)
    }
}

fun lessonShareLink(lesson: Lesson, startAtSeconds: Long? = null): String {
    val base = "https://minbar-adkassahk.vercel.app/lesson/${lesson.id}"
    return if ((startAtSeconds ?: 0L) > 0L) "$base?t=$startAtSeconds" else base
}

/// سطر ختامي يعرّف بالتطبيق ويقود إلى صفحته في المتجر — كل مستدعٍ
/// لـ`lessonShareText` يستفيد منه تلقائياً (المشاركة كانت بلا أي ذكر للتطبيق).
private const val shareFooter =
    "استمع إليه في تطبيق منبر ادكصهك\n" +
        "https://play.google.com/store/apps/details?id=com.ali.menbaradkshk"

/// [momentLabel] سطر «من الدقيقة …» الاختياري — يُدرج قبل السطر التعريفي
/// كي يبقى ختام الرسالة اسمَ التطبيق ورابط متجره.
fun lessonShareText(
    lesson: Lesson,
    categoryName: String? = null,
    subcategoryName: String? = null,
    startAtSeconds: Long? = null,
    momentLabel: String? = null,
): String = buildList {
    add(lesson.displayTitle)
    subcategoryName?.takeIf(String::isNotBlank)?.let { add("القسم: $it") }
    categoryName?.takeIf(String::isNotBlank)?.let { add("($it)") }
    add(lessonShareLink(lesson, startAtSeconds))
    momentLabel?.takeIf(String::isNotBlank)?.let { add(it) }
    add(shareFooter)
}.joinToString("\n")

fun progress(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toDouble() / max(1L, durationMs)).coerceIn(0.0, 1.0).toFloat()
}
