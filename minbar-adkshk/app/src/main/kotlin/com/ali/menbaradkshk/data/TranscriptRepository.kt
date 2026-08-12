package com.ali.menbaradkshk.data

import android.content.Context
import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** النص المشروح المعتمد لدرس (المتن/المقطع الذي تشرحه الصوتية). */
data class LessonTranscript(
    val lessonId: String,
    val text: String,
    val bookTitle: String,
    val sourceRef: String,
    val imageUrls: List<String>,
    val contributorName: String,
)

/** مرفق «النص المشروح» الاختياري داخل مساهمة درس صوتي («شارك درساً»). */
data class TranscriptExtras(
    val text: String = "",
    val bookTitle: String = "",
    val sourceRef: String = "",
    val images: List<Uri> = emptyList(),
) {
    val isEmpty: Boolean get() = text.trim().length < 10 && images.isEmpty()
}

/** مسودة اقتراح نص مشروح من المستمع. */
data class TranscriptDraft(
    val lessonId: String,
    val text: String,
    val bookTitle: String,
    val sourceRef: String,
    val note: String,
    val submitterName: String,
    val images: List<Uri>,
)

/** عنصر «مساهماتي» لاقتراح نص (نظير LessonSubmission للدروس الصوتية). */
data class TranscriptSubmissionItem(
    val id: String,
    val lessonId: String,
    val lessonTitle: String,
    val status: String,
    val rejectReason: String,
    val hasImages: Boolean,
    val createdAtMs: Long,
    val decidedAtMs: Long,
) {
    val isPending: Boolean get() = status == "pending"
}

/**
 * 📖 «النص المشروح»: جلب النص المعتمد للدرس عند فتح المشغّل فقط (وثيقة
 * واحدة، فلا يُثقل مزامنة الدروس)، وإرسال اقتراحات المستمعين (نص و/أو
 * صور صفحات الكتاب) إلى transcript_submissions بنفس دورة «شارك درساً».
 */
class TranscriptRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = LocalStore.get(context)
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()
    private val storage = FirebaseStorage.getInstance()

    // كاش جلسة بسيط: يمنع إعادة الجلب عند كل إعادة تركيب/عودة لنفس الدرس.
    // وهو الطبقة الأولى فوق كاش القرص أدناه لا بديلاً عنه.
    private val cache = ConcurrentHashMap<String, Pair<Long, LessonTranscript?>>()

    // 💾 كاش قرصي مستقلّ بالمستودع: الدرس المنزَّل كان يعمل بلا نت ونصّه لا،
    // لأن الكاش كان في الذاكرة فقط ويضيع بموت العملية — فيظهر «جارٍ التحميل»
    // ثم دعوة المساهمة كأن الدرس بلا نص أصلاً.
    private val diskCache = appContext.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE)

    /** النص المعتمد للدرس أو null. force=true بعد إرسال اقتراح مقبول مثلاً. */
    suspend fun fetch(lessonId: String, force: Boolean = false): LessonTranscript? {
        if (lessonId.isBlank()) return null
        if (!force) {
            cache[lessonId]?.let { memory ->
                if (isFresh(memory)) return memory.second
            }
            readDisk(lessonId)?.let { disk ->
                if (isFresh(disk)) {
                    cache[lessonId] = disk
                    return disk.second
                }
            }
        }
        val transcript = try {
            val document = db.collection(TRANSCRIPTS).document(lessonId).get().await()
            if (!document.exists()) {
                null
            } else {
                LessonTranscript(
                    lessonId = lessonId,
                    text = document.getString("text").orEmpty(),
                    bookTitle = document.getString("bookTitle").orEmpty(),
                    sourceRef = document.getString("sourceRef").orEmpty(),
                    imageUrls = (document.get("images") as? List<*>).orEmpty()
                        .mapNotNull { item ->
                            (item as? Map<*, *>)?.get("url")?.toString()
                                ?.takeIf { it.isNotBlank() }
                        },
                    contributorName = document.getString("contributorName").orEmpty(),
                )
            }
        } catch (failure: Throwable) {
            // بلا اتصال: آخر نسخة محفوظة — ولو انتهت صلاحيتها — خيرٌ من لا شيء.
            // وإن لم تكن هناك نسخة أصلاً لا نبتلع الفشل، كي تميّز الواجهة بين
            // «لا نص لهذا الدرس» و«لم يُجلب بعد».
            val stale = cache[lessonId] ?: readDisk(lessonId)?.also { cache[lessonId] = it }
            if (stale != null) return stale.second
            throw failure
        }
        val now = System.currentTimeMillis()
        cache[lessonId] = now to transcript
        writeDisk(lessonId, transcript, now)
        return transcript
    }

    /**
     * صلاحية المدخل: أسبوع للنص الموجود، ويوم واحد للنتيجة الفارغة كي يظهر
     * نصٌّ اعتُمد حديثاً في وقت معقول. (تخزين النتيجة الفارغة مقصود: الدرس
     * الذي لا نص له لا يُعاد استعلامه عند كل فتح للمشغّل.)
     */
    private fun isFresh(entry: Pair<Long, LessonTranscript?>): Boolean {
        val age = System.currentTimeMillis() - entry.first
        if (age < 0L) return false
        return age < if (entry.second == null) EMPTY_TTL_MS else TEXT_TTL_MS
    }

    private fun entryKey(lessonId: String) = ENTRY_PREFIX + lessonId

    private fun stampKey(lessonId: String) = STAMP_PREFIX + lessonId

    /** قراءة مدخل القرص كما هو (بلا فحص صلاحية) أو null إن غاب أو تلف. */
    private fun readDisk(lessonId: String): Pair<Long, LessonTranscript?>? {
        val savedAtMs = diskCache.getLong(stampKey(lessonId), 0L)
        if (savedAtMs <= 0L) return null
        val raw = diskCache.getString(entryKey(lessonId), null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!json.optBoolean("found", false)) return savedAtMs to null
        val images = json.optJSONArray("images")
        val urls = (0 until (images?.length() ?: 0)).mapNotNull { index ->
            images?.optString(index)?.takeIf(String::isNotBlank)
        }
        return savedAtMs to LessonTranscript(
            lessonId = lessonId,
            text = json.optString("text"),
            bookTitle = json.optString("bookTitle"),
            sourceRef = json.optString("sourceRef"),
            imageUrls = urls,
            contributorName = json.optString("contributorName"),
        )
    }

    private fun writeDisk(lessonId: String, transcript: LessonTranscript?, savedAtMs: Long) {
        val json = JSONObject()
        json.put("found", transcript != null)
        if (transcript != null) {
            json.put("text", transcript.text)
            json.put("bookTitle", transcript.bookTitle)
            json.put("sourceRef", transcript.sourceRef)
            json.put("contributorName", transcript.contributorName)
            json.put("images", JSONArray(transcript.imageUrls))
        }
        diskCache.edit()
            .putString(entryKey(lessonId), json.toString())
            .putLong(stampKey(lessonId), savedAtMs)
            .apply()
        pruneDisk()
    }

    /** سقف 200 مدخل: يُسقط الأقدم أولاً (الأختام وحدها تُقرأ للترتيب). */
    private fun pruneDisk() {
        val stamps = diskCache.all.entries.mapNotNull { entry ->
            val value = entry.value
            if (entry.key.startsWith(STAMP_PREFIX) && value is Long) entry.key to value else null
        }
        if (stamps.size <= MAX_DISK_ENTRIES) return
        val editor = diskCache.edit()
        stamps.sortedBy { it.second }
            .take(stamps.size - MAX_DISK_ENTRIES)
            .forEach { (key, _) ->
                editor.remove(key)
                editor.remove(ENTRY_PREFIX + key.removePrefix(STAMP_PREFIX))
            }
        editor.apply()
    }

    /**
     * إرسال اقتراح: يرفع الصور (إن وُجدت) إلى مجلد المساهمة ثم يستدعي
     * createTranscriptSubmission. يعيد معرّف المساهمة.
     */
    suspend fun submit(draft: TranscriptDraft, onProgress: (Int) -> Unit = {}): String {
        require(draft.lessonId.isNotBlank()) { "الدرس غير محدد." }
        require(
            draft.text.trim().length >= 10 || draft.images.isNotEmpty(),
        ) { "أدخل نص المقطع أو أرفق صورة صفحة واحدة على الأقل." }
        // تحقّق من المجموعة كاملة قبل أول رفع، كي لا نرفع صوراً ثم نحذفها
        // لمجرّد أن صورة لاحقة كبيرة أو ليست صورة.
        val validatedImages = draft.images.take(MAX_IMAGES).mapIndexed { index, uri ->
            val size = appContext.contentResolver.openAssetFileDescriptor(uri, "r")
                ?.use { it.length } ?: -1L
            require(size in 1..MAX_IMAGE_BYTES) {
                "حجم الصورة ${index + 1} يتجاوز 10 ميجابايت."
            }
            val contentType = appContext.contentResolver.getType(uri) ?: "image/jpeg"
            require(contentType.startsWith("image/")) { "الملف المرفق ليس صورة." }
            uri to contentType
        }
        val user = auth.currentUser ?: auth.signInAnonymously().await().user
        requireNotNull(user) { "تعذّر إنشاء الهوية الآمنة." }
        if (draft.submitterName.isNotBlank()) store.setSubmitterName(draft.submitterName)

        val id = "tsub_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val uploadedPaths = mutableListOf<String>()
        var callableStarted = false
        try {
            validatedImages.forEachIndexed { index, (uri, contentType) ->
                val path = "transcript_submissions/${user.uid}/$id/${index}_page.jpg"
                storage.reference.child(path).putFile(
                    uri,
                    StorageMetadata.Builder().setContentType(contentType).build(),
                ).await()
                uploadedPaths.add(path)
                onProgress(((index + 1) * 100) / draft.images.size.coerceAtLeast(1))
            }
            val fcmToken = if (store.notificationsEnabled()) {
                runCatching { FirebaseMessaging.getInstance().token.await() }.getOrDefault("")
            } else {
                ""
            }
            val payload = mapOf(
                "submissionId" to id,
                "lessonId" to draft.lessonId,
                "text" to draft.text.trim(),
                "bookTitle" to draft.bookTitle.trim(),
                "sourceRef" to draft.sourceRef.trim(),
                "note" to draft.note.trim(),
                "submitterName" to draft.submitterName.trim(),
                "imagePaths" to uploadedPaths,
                "fcmToken" to fcmToken,
            )
            callableStarted = true
            val result = runCatching {
                functions.getHttpsCallable("createTranscriptSubmission").call(payload).await()
            }.getOrElse {
                functions.getHttpsCallable("createTranscriptSubmission").call(payload).await()
            }
            val returned = (result.data as? Map<*, *>)?.get("id")?.toString().orEmpty()
            check(returned.isNotBlank()) { "استجابة الخادم غير مكتملة." }
            return returned
        } catch (failure: Throwable) {
            // كان التنظيف مشروطاً بـ«لم يبدأ الاستدعاء» بينما العلم يُرفع **قبل**
            // الاستدعاء، فأي فشل بعده (تجاوز حدّ المساهمات اليومي، أو الفاصل
            // الأدنى بين مساهمتين، أو فشل App Check، أو «الدرس غير موجود»، أو
            // رفض تحقّق الصور) يترك الصور يتيمة بلا مهمّة تنظّفها. الآن نحذف في
            // **كل** مسار لم تُنشأ فيه وثيقة، ونمتنع حين يتعذّر التحقّق أصلاً.
            val lookup = if (callableStarted) {
                findMySubmission(id, user.uid)
            } else {
                Result.success<DocumentSnapshot?>(null)
            }
            // وثيقة موجودة: المساهمة نجحت فعلاً وضاع ردّ الخادم فقط.
            if (lookup.getOrNull() != null) return id
            if (lookup.isSuccess) {
                uploadedPaths.forEach { path ->
                    runCatching { storage.reference.child(path).delete().await() }
                }
            }
            throw failure
        }
    }

    /**
     * تبحث عن وثيقة الاقتراح بعد فشلٍ ما. نستعمل استعلاماً مقيَّداً بـuid لا
     * `get` مباشراً على الوثيقة: قواعد الأمان ترفض قراءة وثيقة غير موجودة
     * أصلاً، فيلتبس «لم تُنشأ» بـ«تعذّر السؤال» ويضيع قرار حذف الصور اليتيمة.
     * نجاح ومعه وثيقة = أُنشئت، ونجاح بلا وثيقة = لم تُنشأ، وفشل = لا نعرف.
     */
    private suspend fun findMySubmission(id: String, uid: String): Result<DocumentSnapshot?> =
        runCatching {
            db.collection(COLLECTION)
                .whereEqualTo("uid", uid)
                .whereEqualTo(FieldPath.documentId(), id)
                .limit(1)
                .get(Source.SERVER)
                .await()
                .documents
                .firstOrNull()
        }

    fun mine(): Flow<List<TranscriptSubmissionItem>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val registration = db.collection(COLLECTION)
            .whereEqualTo("uid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val list = snapshot?.documents.orEmpty().map { document ->
                    TranscriptSubmissionItem(
                        id = document.id,
                        lessonId = document.getString("lessonId").orEmpty(),
                        lessonTitle = document.getString("lessonTitle").orEmpty(),
                        status = document.getString("status").orEmpty().ifBlank { "pending" },
                        rejectReason = document.getString("rejectReason").orEmpty(),
                        hasImages = (document.get("imagePaths") as? List<*>)
                            .orEmpty().isNotEmpty(),
                        createdAtMs = document.getLong("createdAtMs")
                            ?: (document.get("createdAtTs") as? Timestamp)?.toDate()?.time
                            ?: 0L,
                        decidedAtMs = (document.get("decidedAtTs") as? Timestamp)?.toDate()?.time
                            ?: 0L,
                    )
                }.sortedByDescending(TranscriptSubmissionItem::createdAtMs)
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    suspend fun deletePending(item: TranscriptSubmissionItem) {
        if (!item.isPending) return
        functions.getHttpsCallable("deleteMyTranscriptSubmission")
            .call(mapOf("submissionId" to item.id)).await()
    }

    /** تفريغ كاش درس — الطبقتين معاً (بعد اعتماد اقتراح مثلاً ليظهر فوراً). */
    fun invalidate(lessonId: String) {
        cache.remove(lessonId)
        diskCache.edit()
            .remove(entryKey(lessonId))
            .remove(stampKey(lessonId))
            .apply()
    }

    companion object {
        const val MAX_IMAGES = 4
        const val MAX_IMAGE_BYTES = 10L * 1_024L * 1_024L
        const val MAX_TEXT_CHARS = 20_000
        private const val TEXT_TTL_MS = 7L * 24 * 60 * 60 * 1000L
        private const val EMPTY_TTL_MS = 24L * 60 * 60 * 1000L
        private const val MAX_DISK_ENTRIES = 200
        private const val CACHE_FILE = "minbar_transcript_cache"
        private const val ENTRY_PREFIX = "t_"
        private const val STAMP_PREFIX = "ts_"
        private const val COLLECTION = "transcript_submissions"
        private const val TRANSCRIPTS = "lesson_transcripts"
        @Volatile private var instance: TranscriptRepository? = null
        fun get(context: Context): TranscriptRepository = instance ?: synchronized(this) {
            instance ?: TranscriptRepository(context).also { instance = it }
        }
    }
}
