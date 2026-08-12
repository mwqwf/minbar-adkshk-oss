package com.ali.menbaradkshk.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class DownloadProgress(
    val lessonId: String,
    val percent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
)

/// حالة طابور التحميل الخلفي (تُحدَّث من عامل WorkManager وتُعرض في الواجهة).
data class DownloadQueueState(
    val label: String,
    val done: Int,
    val total: Int,
    val currentTitle: String = "",
    val waitingForNetwork: Boolean = false,
    /// أوقفه المستخدم — لا يُستأنف إلا بضغطة «استئناف».
    val paused: Boolean = false,
    /// نسبة الملفّ الجاري (0..100)، و‑1 حين لا يُعلن الخادم حجماً.
    /// بدونها كان الشريط مبنيّاً على `done/total` وحدها، فيبقى عند الصفر
    /// طوال تحميل درس واحد ويبدو كأنّه متجمّد.
    val filePercent: Int = -1,
    val fileDownloadedBytes: Long = 0L,
    val fileTotalBytes: Long = 0L,
) {
    /// تقدّم الطابور كاملاً: الدروس المكتملة + كسر الدرس الجاري.
    val overallFraction: Float
        get() {
            if (total <= 0) return 0f
            val fileFraction = if (filePercent in 0..100) filePercent / 100f else 0f
            return ((done + fileFraction) / total).coerceIn(0f, 1f)
        }
}

/// خطأ شبكة قابل لإعادة المحاولة (يستأنف WorkManager تلقائياً عند عودة الاتصال).
class RetryableDownloadException(cause: Throwable) : Exception(cause)

/// أوقف المستخدم التحميل مؤقّتاً — الملف الجزئي **يبقى** فيُستأنف من موضعه.
class DownloadPausedException : Exception("أُوقف التحميل مؤقّتاً.")

/// ألغى المستخدم تحميل هذا الدرس — الملف الجزئي يُحذف ويخرج من الطابور.
class DownloadCancelledException(val lessonId: String) : Exception("أُلغي التحميل.")

class DownloadRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = LocalStore.get(context)
    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    val queueState = MutableStateFlow<DownloadQueueState?>(null)

    /// قفل لكل درس: مسارا التحميل (وظيفة UIDT وعمل WorkManager) قد يتشابكان
    /// على المعرّف نفسه، فيفتح كلاهما `FileOutputStream` على الملف الجزئي
    /// `<safeId>.<ext>.part` نفسه بإزاحتين مستقلّتين فيتلف الملف — خاصّةً مع
    /// منطق الاستئناف بـRange.
    private val lessonLocks = mutableMapOf<String, Mutex>()

    private fun lockFor(lessonId: String): Mutex = synchronized(lessonLocks) {
        lessonLocks.getOrPut(lessonId) { Mutex() }
    }

    // ---- تحكّم المستخدم في النقل الجاري ----
    //
    /// إلغاءات مطلوبة لدروس بعينها. الفحص داخل حلقة القراءة لا خارجها:
    /// حذف المعرّف من الطابور وحده لا يوقف نقلاً بدأ فعلاً، فيظلّ يستهلك
    /// البيانات إلى آخر بايت ثم يُكتب الملف كأنّ الإلغاء لم يقع.
    private val cancelRequests = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /// هل الطابور موقوف مؤقّتاً؟ (مرآة في الذاكرة لعلم [LocalStore] كي
    /// تُقرأ في حلقة النقل بلا لمس القرص مع كل حزمة بايتات.)
    private val _paused = MutableStateFlow(store.downloadQueuePaused())
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    fun setPaused(value: Boolean) {
        _paused.value = value
        store.setDownloadQueuePaused(value)
    }

    fun requestCancel(lessonId: String) {
        cancelRequests += lessonId
    }

    fun clearCancel(lessonId: String) {
        cancelRequests -= lessonId
    }

    /// ⚠️ يُمسح **كل** علم إلغاء عند إلغاء الطابور كلّه. العلم لا معنى له
    /// بعد خروج الدرس من الطابور، وبقاؤه كان يعني أنّ تحميلاً لاحقاً لدرس
    /// أُلغي مرّة (يدويّاً أو من التحميل التلقائي) يُسقَط بصمت.
    fun clearAllCancels() {
        cancelRequests.clear()
    }

    fun isCancelRequested(lessonId: String): Boolean = cancelRequests.contains(lessonId)

    fun localPath(lessonId: String): String? = store.localAudioPath(lessonId)
    fun isDownloaded(lessonId: String): Boolean = localPath(lessonId) != null
    fun all(): Map<String, String> = store.downloads()

    suspend fun download(lesson: Lesson): String {
        require(lesson.id.isNotBlank()) { "معرّف الدرس مفقود." }
        return lockFor(lesson.id).withLock { downloadLocked(lesson) }
    }

    private suspend fun downloadLocked(lesson: Lesson): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(lesson.audioUrl)
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "رابط الصوت غير آمن أو غير صالح."
        }
        localPath(lesson.id)?.let { return@withContext it }

        val directory = File(appContext.filesDir, "lessons").apply { mkdirs() }
        // تطبيع الامتداد: التخزين يعطي أحياناً `.ogx` (Ogg مُتعدِّد) وما شابه،
        // وتطبيقات المراسلة لا تعدّها صوتاً فتُرسلها ملفّاً عامّاً.
        val extension = when (
            val raw = uri.lastPathSegment
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
        ) {
            "mp3", "m4a", "aac", "wav", "flac", "amr", "opus", "ogg" -> raw
            "ogx", "oga", "ogv" -> "ogg"
            "m4b", "mp4" -> "m4a"
            "3gp", "3gpp" -> "3gp"
            else -> "mp3"
        }
        val safeId = lesson.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val target = File(directory, "$safeId.$extension")
        val partial = File(directory, "$safeId.$extension.part")

        var connection: HttpURLConnection? = null
        try {
            // استئناف التنزيل من حيث توقف: نطلب المدى المتبقي إن وُجد ملف جزئي.
            val resumeFrom = partial.length().takeIf { it > 0L } ?: 0L
            connection = (URL(lesson.audioUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 45_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MinbarAdkassahk/${com.ali.menbaradkshk.BuildConfig.VERSION_NAME}")
                // بلا ضغط وسيط: gzip شفّاف يجعل Content-Length وحساب Range
                // غير مطابقَين للبايتات المكتوبة فيفسد الاستئناف والنِّسب.
                setRequestProperty("Accept-Encoding", "identity")
                if (resumeFrom > 0L) setRequestProperty("Range", "bytes=$resumeFrom-")
                connect()
            }
            val code = connection.responseCode
            // 206 = الخادم قبل الاستئناف؛ 200 مع طلب مدى = لا يدعمه فنبدأ من الصفر.
            val resuming = resumeFrom > 0L && code == 206
            if (resumeFrom > 0L && code == 200) partial.delete()
            // 416: الملف الجزئي تجاوز حجم الملف على الخادم (استُبدل الملف
            // غالباً) — نحذفه ونعيد المحاولة من الصفر بدل فشل دائم يُسقط الدرس.
            if (resumeFrom > 0L && code == 416) {
                partial.delete()
                throw RetryableDownloadException(java.io.IOException("HTTP 416"))
            }
            // أعطال الخادم/الازدحام المؤقّتة قابلة للإعادة تلقائياً — معاملتها
            // كفشل دائم كانت تُسقط الدرس من الطابور بلا أي محاولة ثانية
            // (شكوى مستخدمين فعلية). الملف الجزئي يبقى للاستئناف.
            if (code in intArrayOf(408, 425, 429, 500, 502, 503, 504)) {
                throw RetryableDownloadException(java.io.IOException("HTTP $code"))
            }
            require(code in 200..299) { "تعذّر تنزيل الملف ($code)." }
            if (resuming) {
                // تحقُّق من إزاحة الاستئناف: خادم يُعيد 206 بمدى لا يبدأ من
                // موضعنا كان سيُلحق بايتات بإزاحة خاطئة فيتلف الملف بصمت.
                val start = connection.getHeaderField("Content-Range")
                    ?.substringAfter("bytes ", "")
                    ?.substringBefore('-')
                    ?.trim()
                    ?.toLongOrNull()
                if (start != null && start != resumeFrom) {
                    partial.delete()
                    throw RetryableDownloadException(
                        java.io.IOException("مدى الاستئناف غير متطابق ($start ≠ $resumeFrom)"),
                    )
                }
            }
            val remaining = connection.getHeaderField("Content-Length")
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            val alreadyHave = if (resuming) resumeFrom else 0L
            val total = if (remaining > 0L) remaining + alreadyHave else 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial, resuming).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var received = alreadyHave
                    while (true) {
                        // إلغاء الكوروتين لا يقاطع خيط Dispatchers.IO: بلا هذا
                        // الفحص يستمرّ النقل حتى EOF بعد onStopJob، ثم يرمي
                        // `withContext` إلغاءً يُلتقط كفشل دائم فيُمحى الطابور.
                        // الرمي هنا يُغلق التيّارات (use) والاتصال (finally) فوراً.
                        ensureActive()
                        // تحكّم المستخدم يُفحص مع كل حزمة: الإيقاف والإلغاء
                        // يجب أن يوقفا استهلاك البيانات **فوراً** لا عند
                        // نهاية الملف. الرمي هنا يغلق التيّارات والاتصال.
                        if (_paused.value) throw DownloadPausedException()
                        if (cancelRequests.contains(lesson.id)) {
                            throw DownloadCancelledException(lesson.id)
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        val percent = if (total > 0L) ((received * 100L) / total).toInt() else -1
                        _progress.value = _progress.value + (
                            lesson.id to DownloadProgress(lesson.id, percent, received, total)
                        )
                    }
                    output.fd.sync()
                }
            }
            check(partial.length() > 0L) { "ملف الصوت فارغ." }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                // بعض الأنظمة ترفض rename رغم أنّ الوجهة في المجلد نفسه —
                // النسخ ثم الحذف خطة بديلة تُنقذ تنزيلاً اكتمل فعلاً.
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            store.setDownload(lesson.id, target.absolutePath)
            target.absolutePath
        } catch (cancelled: CancellationException) {
            // إلغاء (onStopJob/إغلاق العملية) ليس فشلاً: الملف الجزئي يبقى
            // للاستئناف، والإلغاء يُعاد رميه كما هو كي لا يُحذف الدرس من الطابور.
            throw cancelled
        } catch (paused: DownloadPausedException) {
            // ⏸ الملف الجزئي **يبقى**: الاستئناف يطلب المدى المتبقي بـRange
            // فيُكمل من البايت نفسه بلا إعادة تنزيل ما نزل.
            throw paused
        } catch (cancelled: DownloadCancelledException) {
            // ✕ إلغاء صريح: لا معنى لإبقاء نصف ملفّ لن يُستأنف.
            partial.delete()
            throw cancelled
        } catch (failure: java.io.IOException) {
            // انقطاع شبكة: نُبقي الملف الجزئي للاستئناف لاحقاً ونعلن قابلية الإعادة.
            throw RetryableDownloadException(failure)
        } catch (failure: Throwable) {
            partial.delete()
            throw failure
        } finally {
            connection?.disconnect()
            _progress.value = _progress.value - lesson.id
            // العلم أدّى غرضه (أوقف النقل) فيُمسح هنا لا في مكان بعيد:
            // بقاؤه يجعل أيّ محاولة تحميل تالية لهذا الدرس تُلغى بصمت.
            cancelRequests -= lesson.id
        }
    }

    suspend fun delete(lessonId: String) = withContext(Dispatchers.IO) {
        store.localAudioPath(lessonId)?.let { File(it).delete() }
        store.removeDownload(lessonId)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        store.downloads().keys.toList().forEach { delete(it) }
    }

    companion object {
        @Volatile private var instance: DownloadRepository? = null
        fun get(context: Context): DownloadRepository = instance ?: synchronized(this) {
            instance ?: DownloadRepository(context).also { instance = it }
        }
    }
}

