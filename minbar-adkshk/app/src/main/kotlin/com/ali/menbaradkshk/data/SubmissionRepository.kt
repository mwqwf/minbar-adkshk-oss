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
import java.util.UUID

data class SubmissionDraft(
    val audioUri: Uri,
    val fileName: String,
    val title: String,
    val category: Category,
    val subcategory: Subcategory,
    val submitterName: String,
    val note: String,
    val rightsConfirmed: Boolean,
    val contentPolicyAccepted: Boolean,
    // «النص المشروح» الاختياري المرافق — يُنشر مع الدرس عند اعتماد المساهمة.
    val transcript: TranscriptExtras = TranscriptExtras(),
)

class SubmissionRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = LocalStore.get(context)
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()
    private val storage = FirebaseStorage.getInstance()

    suspend fun submit(
        draft: SubmissionDraft,
        onProgress: (Int) -> Unit = {},
    ): String {
        // ⚠️ الإقرار **ليس شرطاً** للإرسال: المشرفون يتحقّقون من كل درس بأنفسهم
        // قبل النشر ولا يبنون قرارهم على ادّعاء المستخدم. كان هنا require يمنع
        // الإرسال بلا إقرار، فيبقى زرّ «إرسال للمراجعة» صامتاً بلا تفسير.
        // قيمتا الإقرار تُنقَلان كما اختارهما المستخدم ليراهما المشرف عند المراجعة.
        require(draft.title.isNotBlank()) { "أدخل عنوان الدرس." }
        val user = auth.currentUser ?: auth.signInAnonymously().await().user
        requireNotNull(user) { "تعذّر إنشاء الهوية الآمنة." }

        val size = appContext.contentResolver.openAssetFileDescriptor(draft.audioUri, "r")
            ?.use { it.length }
            ?: -1L
        // فصل السببين: حجم مجهول (وصول مُنتزَع/ملف حُذف) ليس «تجاوز الحدّ».
        require(size >= 0) { "تعذّر قراءة الملف المحدّد — أعد اختياره." }
        require(size <= MAX_FILE_BYTES) { "حجم الملف يتجاوز 100 ميجابايت." }
        // افحص كل صور النص قبل رفع الصوت؛ كان اكتشاف صورة كبيرة/غير صالحة
        // يحدث بعد رفع ملف صوتي قد يبلغ 100MB، فيُحذف ثم يعاد رفعه عند المحاولة.
        val validatedTranscriptImages = draft.transcript.images
            .take(TranscriptRepository.MAX_IMAGES)
            .mapIndexed { index, imageUri ->
                val imageSize = appContext.contentResolver
                    .openAssetFileDescriptor(imageUri, "r")?.use { it.length } ?: -1L
                require(imageSize in 1..TranscriptRepository.MAX_IMAGE_BYTES) {
                    "حجم صورة النص ${index + 1} يتجاوز 10 ميجابايت."
                }
                val imageType = appContext.contentResolver.getType(imageUri) ?: "image/jpeg"
                require(imageType.startsWith("image/")) { "مرفق النص ليس صورة." }
                imageUri to imageType
            }
        if (draft.submitterName.isNotBlank()) store.setSubmitterName(draft.submitterName)

        val id = "sub_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val safeName = draft.fileName.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(120)
        val storagePath = "submissions/${user.uid}/$id/$safeName"
        val reference = storage.reference.child(storagePath)
        val task = reference.putFile(
            draft.audioUri,
            StorageMetadata.Builder().setContentType(mimeFor(safeName)).build(),
        )
        task.addOnProgressListener { snapshot ->
            if (snapshot.totalByteCount > 0L) {
                onProgress(((snapshot.bytesTransferred * 100L) / snapshot.totalByteCount).toInt())
            }
        }

        var uploaded = false
        var callableStarted = false
        val transcriptImagePaths = mutableListOf<String>()
        try {
            task.await()
            uploaded = true
            val audioUrl = reference.downloadUrl.await().toString()
            // صور «النص المشروح» الاختيارية تُرفع لمساحة اقتراحات النصوص
            // (قواعد التخزين تسمح لصاحبها برفع الصور هناك) بنفس معرّف المساهمة.
            validatedTranscriptImages
                .forEachIndexed { index, (imageUri, imageType) ->
                    val imagePath = "transcript_submissions/${user.uid}/$id/lesson_${index}_page.jpg"
                    storage.reference.child(imagePath).putFile(
                        imageUri,
                        StorageMetadata.Builder().setContentType(imageType).build(),
                    ).await()
                    transcriptImagePaths.add(imagePath)
                }
            val fcmToken = if (store.notificationsEnabled()) {
                runCatching { FirebaseMessaging.getInstance().token.await() }.getOrDefault("")
            } else {
                ""
            }
            val payload = mapOf(
                "id" to id,
                "uid" to user.uid,
                "submitterName" to draft.submitterName.trim(),
                "title" to draft.title.trim(),
                "categoryId" to draft.category.id,
                "categoryName" to draft.category.name,
                "subcategoryId" to draft.subcategory.id,
                "subcategoryName" to draft.subcategory.name,
                "note" to draft.note.trim(),
                "audioUrl" to audioUrl,
                "storagePath" to storagePath,
                "fileName" to safeName,
                "fileSize" to size,
                "fcmToken" to fcmToken,
                // إقرار المستخدم كما اختاره فعلاً (لا قيمة ثابتة): المشرف يراه
                // عند المراجعة فيعرف هل أقرّ بالحقوق أم أرسل بلا إقرار.
                "rightsConfirmed" to draft.rightsConfirmed,
                "contentPolicyAccepted" to draft.contentPolicyAccepted,
                "contentPolicyVersion" to CONTENT_POLICY_VERSION,
                "termsAcceptedAt" to java.time.Instant.now().toString(),
                "transcriptText" to draft.transcript.text.trim(),
                "transcriptBookTitle" to draft.transcript.bookTitle.trim(),
                "transcriptSourceRef" to draft.transcript.sourceRef.trim(),
                "transcriptImagePaths" to transcriptImagePaths,
            )
            callableStarted = true
            val result = runCatching {
                functions.getHttpsCallable("createSubmission").call(payload).await()
            }.getOrElse { first ->
                // إعادة المحاولة للأعطال العابرة فقط (انقطاع/مهلة/عطل لحظي):
                // الرفض القاطع (حدّ يومي، فاصل أدنى، تحقّق) كان يُستدعى مرّتين
                // بلا جدوى ويؤخّر وصول رسالة الرفض للمستخدم.
                if (!isTransient(first)) throw first
                kotlinx.coroutines.delay(1_500)
                functions.getHttpsCallable("createSubmission").call(payload).await()
            }
            val returned = (result.data as? Map<*, *>)?.get("id")?.toString().orEmpty()
            check(returned.isNotBlank()) { "استجابة الخادم غير مكتملة." }
            return returned
        } catch (failure: Throwable) {
            // كان التنظيف مشروطاً بـ«لم يبدأ الاستدعاء» بينما العلم يُرفع **قبل**
            // الاستدعاء، فأي فشل بعده (تجاوز حدّ المساهمات اليومي، أو الفاصل
            // الأدنى بين مساهمتين، أو فشل App Check، أو رفض التحقق) يترك الملف
            // المرفوع يتيماً إلى الأبد. الآن نحذف في **كل** مسار لم تُنشأ فيه
            // وثيقة، ونمتنع عن الحذف حين يتعذّر التحقّق أصلاً.
            val lookup = if (callableStarted) {
                findMySubmission(id, user.uid)
            } else {
                Result.success<DocumentSnapshot?>(null)
            }
            val document = lookup.getOrNull()
            if (document != null) {
                // الوثيقة موجودة: المساهمة نجحت فعلاً وضاع ردّ الخادم فقط.
                if (document.getString("storagePath") == storagePath) return id
                throw failure
            }
            if (lookup.isSuccess) {
                if (uploaded) runCatching { reference.delete().await() }
                transcriptImagePaths.forEach { path ->
                    runCatching { storage.reference.child(path).delete().await() }
                }
            }
            throw failure
        }
    }

    /// هل الفشل عابر (شبكة/مهلة/عطل خادم لحظي) فيستحقّ محاولة ثانية؟
    private fun isTransient(failure: Throwable): Boolean {
        val functionsFailure = failure as? com.google.firebase.functions.FirebaseFunctionsException
            ?: failure.cause as? com.google.firebase.functions.FirebaseFunctionsException
        return when (functionsFailure?.code) {
            com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE,
            com.google.firebase.functions.FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
            com.google.firebase.functions.FirebaseFunctionsException.Code.INTERNAL,
            -> true
            // ليس خطأ دوالّ أصلاً: عابر فقط إن كان انقطاع إدخال/إخراج.
            null -> generateSequence(failure) { it.cause }.take(5).any { it is java.io.IOException }
            else -> false
        }
    }

    /**
     * تبحث عن وثيقة المساهمة بعد فشلٍ ما. نستعمل استعلاماً مقيَّداً بـuid لا
     * `get` مباشراً على الوثيقة: قواعد الأمان ترفض قراءة وثيقة غير موجودة
     * أصلاً، فيلتبس «لم تُنشأ» بـ«تعذّر السؤال» ويضيع قرار حذف الملف اليتيم.
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

    fun mine(): Flow<List<LessonSubmission>> = callbackFlow {
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
                    LessonSubmission(
                        id = document.id,
                        title = document.getString("title").orEmpty(),
                        categoryName = document.getString("categoryName").orEmpty(),
                        subcategoryName = document.getString("subcategoryName").orEmpty(),
                        status = document.getString("status").orEmpty().ifBlank { "pending" },
                        rejectReason = document.getString("rejectReason").orEmpty(),
                        storagePath = document.getString("storagePath").orEmpty(),
                        // الخادم يكتب createdAt نصاً ISO مع createdAtTs/createdAtMs — نقرأ المتاح.
                        createdAtMs = document.getLong("createdAtMs")
                            ?: (document.get("createdAtTs") as? Timestamp)?.toDate()?.time
                            ?: document.getString("createdAt")
                                ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                            ?: 0L,
                        decidedAtMs = (document.get("decidedAtTs") as? Timestamp)?.toDate()?.time
                            ?: document.getString("decidedAt")
                                ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                            ?: 0L,
                    )
                }.sortedByDescending(LessonSubmission::createdAtMs)
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    suspend fun deletePending(submission: LessonSubmission) {
        if (submission.status != "pending") return
        functions.getHttpsCallable("deleteMySubmission")
            .call(mapOf("submissionId" to submission.id)).await()
    }

    suspend fun deleteCloudIdentityData() {
        if (auth.currentUser == null) return
        functions.getHttpsCallable("deleteMyData").call().await()
        auth.signOut()
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "aac" -> "audio/aac"
        "m4a" -> "audio/mp4"
        "amr" -> "audio/amr"
        "flac" -> "audio/flac"
        else -> "audio/mpeg"
    }

    companion object {
        const val MAX_FILE_BYTES = 100L * 1_024L * 1_024L
        const val CONTENT_POLICY_VERSION = "2026-07-16"
        private const val COLLECTION = "lesson_submissions"
        @Volatile private var instance: SubmissionRepository? = null
        fun get(context: Context): SubmissionRepository = instance ?: synchronized(this) {
            instance ?: SubmissionRepository(context).also { instance = it }
        }
    }
}
