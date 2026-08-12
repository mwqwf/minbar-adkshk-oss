package com.ali.menbaradkshk.data

import android.content.Context
import com.ali.menbaradkshk.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/**
 * 🔔 تذكير التحديث.
 *
 * وثيقة واحدة (`app_config/android`) يضبطها المالك من اللوحة، ويقارنها
 * التطبيق برقم إصداره:
 *  - أقدم من `latestVersionCode` ⇒ تذكير لطيف يمكن صرفه.
 *  - أقدم من `minSupportedVersionCode` ⇒ تذكير أقوى يتكرّر كل تشغيل.
 *
 * **لماذا وثيقة على الخادم لا مكتبة تحديث من Play؟** الوزن والتحكّم:
 * لا تبعيّة جديدة في الحزمة، والنصّ عربيّ نكتبه نحن، وتعمل الآلية أيّاً كان
 * مصدر التثبيت. والكلفة الشبكيّة صفر عمليّاً: قراءة وثيقة واحدة صغيرة **مرّة
 * كل ست ساعات على الأكثر**، تُقرأ من الكاش أوّلاً وتُحفَظ محليّاً فتعمل
 * المقارنة بلا اتصال أصلاً.
 *
 * ولا تحجب الآلية التطبيق أبداً: الدروس المنزَّلة يجب أن تبقى قابلة للاستماع
 * بلا إنترنت ومهما قدُم الإصدار — التذكير دعوة لا بوّابة.
 */
class AppConfigRepository private constructor(context: Context) {

    private val app = context.applicationContext
    private val db = FirebaseFirestore.getInstance()
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** ما يُعرض للمستخدم — أو `None` حين لا شيء يُذكَر به. */
    sealed interface Status {
        data object None : Status

        /** نسخة أحدث متاحة — تذكير يمكن صرفه. */
        data class Optional(val latest: Int, val message: String, val storeUrl: String) : Status

        /** الإصدار دون الحدّ المدعوم — تذكير يتكرّر كل تشغيل. */
        data class Required(val latest: Int, val message: String, val storeUrl: String) : Status
    }

    /**
     * يقرأ الإعداد (بحدّ أدنى ست ساعات بين قراءتين) ثم يقارن.
     * أي فشل ⇒ آخر قيم محفوظة، وإلا `None` — التذكير لا يُزعج بلا يقين.
     */
    suspend fun status(): Status {
        refreshIfStale()
        val latest = prefs.getInt(KEY_LATEST, 0)
        val minSupported = prefs.getInt(KEY_MIN, 0)
        val message = prefs.getString(KEY_MESSAGE, "").orEmpty()
        val storeUrl = prefs.getString(KEY_STORE, "").orEmpty().ifBlank { PLAY_URL }
        val current = BuildConfig.VERSION_CODE
        return when {
            current < minSupported -> Status.Required(latest, message, storeUrl)
            current < latest -> Status.Optional(latest, message, storeUrl)
            else -> Status.None
        }
    }

    /**
     * هل نعرض شاشة التذكير الآن؟
     *
     * التوازن المقصود: التذكير يغطّي الشاشة كي **يُرى فعلاً** (أغلب الناس لا
     * ينظرون إلى الإشعارات)، لكنّه لا يتكرّر أكثر من مرّة كل ٢٤ ساعة للنسخة
     * الاختياريّة، ولا يعود بعد صرفه لتلك النسخة بعينها. أمّا حين يهبط
     * الإصدار دون الحدّ المدعوم فيظهر كل تشغيل — لأنّ الرسالة عندئذ ليست
     * «تتوفّر نسخة أحدث» بل «دعم نسختك يوشك أن يتوقّف».
     */
    fun shouldPrompt(status: Status): Boolean = when (status) {
        is Status.None -> false
        is Status.Required -> true
        is Status.Optional -> {
            val dismissed = prefs.getInt(KEY_DISMISSED, 0) >= status.latest
            val since = System.currentTimeMillis() - prefs.getLong(KEY_PROMPTED, 0L)
            !dismissed && since >= PROMPT_INTERVAL_MS
        }
    }

    fun markPrompted() {
        prefs.edit().putLong(KEY_PROMPTED, System.currentTimeMillis()).apply()
    }

    fun dismiss(latest: Int) {
        prefs.edit().putInt(KEY_DISMISSED, latest).apply()
    }

    /// هل يُرسَل إشعار التحديث اليوميّ؟ مرّة كل ٢٤ ساعة على الأكثر، ويسكت
    /// تماماً لنسخة صرف المستخدم شاشتَها (صرفُها إجابة صريحة: «ليس الآن»).
    fun shouldNotify(latest: Int): Boolean {
        if (prefs.getInt(KEY_DISMISSED, 0) >= latest) return false
        // إصدار أحدث ممّا أُشعِر به آخر مرّة ⇒ أشعِر فوراً بلا انتظار الخانق.
        if (prefs.getInt(KEY_NOTIFIED_FOR, 0) < latest) return true
        val since = System.currentTimeMillis() - prefs.getLong(KEY_NOTIFIED, 0L)
        return since >= PROMPT_INTERVAL_MS
    }

    fun markNotified(latest: Int) {
        prefs.edit()
            .putLong(KEY_NOTIFIED, System.currentTimeMillis())
            .putInt(KEY_NOTIFIED_FOR, latest)
            .apply()
    }

    /// فحص فوريّ يتجاوز خانق الست ساعات — للفحص الدوريّ اليوميّ وللعودة
    /// إلى التطبيق بعد غياب: التذكير الذي يتأخّر ست ساعات عن الإصدار
    /// الجديد يفوّت أوّل يوم كامل من عمره.
    suspend fun statusForcingRefresh(): Status {
        prefs.edit().putLong(KEY_CHECKED, 0L).apply()
        return status()
    }

    private suspend fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_CHECKED, 0L) < CHECK_INTERVAL_MS) return
        val reference = db.collection("app_config").document("android")
        // ⚠️ الخادم أوّلاً دائماً: تقديم الكاش كان يجمّد القيم على أوّل قراءة
        // إلى الأبد (الوثيقة تصير مخزَّنة فلا يُسأل الخادم بعدها قطّ، فلا يصل
        // تذكير أيّ إصدار لاحق). الكاش هنا خطّة بديلة عند فشل الشبكة فقط.
        val doc = runCatching { reference.get(Source.SERVER).await() }.getOrNull()
            ?: runCatching { reference.get(Source.CACHE).await() }
                .getOrNull()
                ?.takeIf { it.exists() }
            ?: return
        if (!doc.exists()) {
            // لا وثيقة إعداد ⇒ لا تذكير. نُثبّت الختم كي لا نسأل كل مرّة.
            prefs.edit().putLong(KEY_CHECKED, now).apply()
            return
        }
        val latest = (doc.getLong("latestVersionCode") ?: 0L).toInt()
        val minSupported = (doc.getLong("minSupportedVersionCode") ?: 0L).toInt()
        prefs.edit()
            .putInt(KEY_LATEST, latest)
            .putInt(KEY_MIN, minSupported)
            .putString(KEY_MESSAGE, doc.getString("message").orEmpty())
            .putString(KEY_STORE, doc.getString("storeUrl").orEmpty())
            .putLong(KEY_CHECKED, now)
            .apply()
    }

    companion object {
        /** معرّف حزمة نسخة المتجر — ثابت لا يتبع لاحقة `.dev` في التطوير. */
        const val STORE_PACKAGE = "com.ali.menbaradkshk"

        const val PLAY_URL =
            "https://play.google.com/store/apps/details?id=$STORE_PACKAGE"

        private const val PREFS = "minbar_app_config"
        private const val KEY_LATEST = "latest_version_code"
        private const val KEY_MIN = "min_supported_version_code"
        private const val KEY_MESSAGE = "message"
        private const val KEY_STORE = "store_url"
        private const val KEY_CHECKED = "checked_at_ms"
        private const val KEY_DISMISSED = "dismissed_for"
        private const val KEY_PROMPTED = "prompted_at_ms"
        private const val KEY_NOTIFIED = "notified_at_ms"
        private const val KEY_NOTIFIED_FOR = "notified_for"
        /// لا تتكرّر شاشة التذكير الاختياريّة قبل يوم كامل.
        private const val PROMPT_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        @Volatile
        private var instance: AppConfigRepository? = null

        fun get(context: Context): AppConfigRepository =
            instance ?: synchronized(this) {
                instance ?: AppConfigRepository(context).also { instance = it }
            }
    }
}
