package com.ali.menbaradkshk.util

import java.net.URLDecoder

// 🧠 استخراج عنوان نظيف من اسم ملف — منقول حرفياً من smart_title.dart في الأصل.
// يعالج أنماط المسجّلات وواتساب ومواقع التنزيل، ويعيد '' للأسماء الآلية البحتة.

private val autoPatterns = listOf(
    Regex("^(AUD|VID|PTT|IMG|DOC|GIF)[-_]\\d{8}[-_]WA\\d+", RegexOption.IGNORE_CASE),
    Regex("^WhatsApp\\s+(Audio|Video|Ptt|Image)\\b", RegexOption.IGNORE_CASE),
    Regex("^(new\\s+)?record(ing)?[-_\\s]*[\\d_.\\-\\s]*$", RegexOption.IGNORE_CASE),
    Regex(
        "^(voice|audio|video|sound|note|memo|track|file|item|untitled" +
            "|تسجيل|مقطع|ملف|صوت|بدون\\s*عنوان)[-_\\s]*[\\d٠-٩_.\\-\\s]*$",
        RegexOption.IGNORE_CASE,
    ),
    Regex("^[\\d٠-٩\\s_.\\-()~]+$"),
    Regex("^(REC|MIC|ZOOM|DS|DM|VN)[-_]?\\d+$", RegexOption.IGNORE_CASE),
)

private val junkInside = Regex(
    "(www\\.|https?:|\\.com|\\.net|\\.org|\\.info|kbps|kb/s|\\d{3,4}p\\b" +
        "|mp3|mp4|m4a|wav|flac|\\bhd\\b|\\bhq\\b|\\b4k\\b|official|lyrics" +
        "|audio\\s*only|youtube|download|free|copy|نسخة|تحميل|موقع|بجودة|حصري)",
    RegexOption.IGNORE_CASE,
)

fun smartTitleFromFileName(fileName: String): String {
    var s = fileName.trim()
    if (s.isEmpty()) return ""

    s = s.split(Regex("[/\\\\]")).last()
    val dot = s.lastIndexOf('.')
    if (dot > 0) s = s.substring(0, dot)

    if (s.contains('%')) {
        s = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrElse { s.replace("%20", " ") }
    }

    for (pattern in autoPatterns) {
        if (pattern.containsMatchIn(s) && pattern.matchAt(s, 0) != null) return ""
    }

    // أقواس بمحتوى دعائي/تقني تُحذف كاملة؛ الأقواس ذات النص العادي تبقى.
    s = Regex("[\\[({]([^\\])}]*)[\\])}]").replace(s) { match ->
        val inner = match.groupValues[1]
        if (junkInside.containsMatchIn(inner)) " " else match.value
    }

    // بصمة موقع طليقة في الاسم.
    s = Regex("(^|\\s)(www\\.)?[\\w-]+\\.(com|net|org|info|me|tv|cc)(?=\\s|$)", RegexOption.IGNORE_CASE)
        .replace(s, " ")

    // ترقيم تسلسلي في البداية.
    val leadingIndex = Regex("^[\\s\\-–—ـ_.]*[(\\[]?\\s*[0-9٠-٩]{1,4}\\s*[)\\]]?[\\s\\-–—ـ_.]+")
    repeat(2) {
        if (leadingIndex.containsMatchIn(s) && leadingIndex.matchAt(s, 0) != null) {
            s = leadingIndex.replaceFirst(s, "")
        }
    }

    s = s.replace(Regex("[_~•·]+"), " ")
    s = s.replace(Regex("(?<=\\S)\\.(?=\\S)"), " ")
    s = s.replace(Regex("\\b(64|96|128|192|256|320)\\s?kbps\\b", RegexOption.IGNORE_CASE), " ")

    if (!s.contains(' ') && s.contains('-')) s = s.replace('-', ' ')

    s = s.replace(Regex("\\s+"), " ").trim()
    s = s.replace(Regex("^[\\s\\-–—ـ_.,،؛;:]+|[\\s\\-–—ـ_.,،؛;:]+$"), "").trim()

    if (!Regex("[A-Za-z؀-ۿ]").containsMatchIn(s)) return ""
    if (s.length < 2) return ""
    return s
}
