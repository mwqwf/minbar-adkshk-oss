package com.ali.menbaradkshk.data

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/// مسار التحميل على أندرويد 13 وما دون: عمل خلفية عادي عبر WorkManager.
/// لا يستخدم أي خدمة أمامية، ويعرض إشعار تقدّم عادياً، ويستأنف الملف الجزئي.
class LessonDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        when (DownloadQueueProcessor(applicationContext).run()) {
            DownloadRunResult.FINISHED -> Result.success()
            DownloadRunResult.NEEDS_RETRY -> Result.retry()
        }
}

object DownloadScheduler {
    private const val WORK_NAME = "lesson_downloads"
    /// اسم فريد مستقلّ للعناصر المؤجَّلة بقيد «واي فاي فقط»: لو استُعمل الاسم
    /// نفسه لأسقطته سياسة KEEP ما دام عمل التحميل العادي جارياً.
    private const val WIFI_WORK_NAME = "lesson_downloads_wifi"
    private const val JOB_ID = 4210

    /// ⛔ **لا تُعِد `ExistingWorkPolicy.KEEP`.** كان طلب تحميل جديد يُسقَط
    /// بصمت متى كان للاسم الفريد عملٌ قائم — وهو قائم في حالتين شائعتين:
    /// عامل يوشك على الانتهاء، أو محاولةٌ مؤجَّلة بمهلة تراجعيّة بعد انقطاع
    /// أو إلغاء. النتيجة بالضبط ما رآه المستخدم: رسالة «أُضيف إلى التحميل»
    /// ثم لا شيء يحدث إلى أن يُعاد تشغيل التطبيق (فيُوقظ الطابور من
    /// `MinbarApplication`). `APPEND_OR_REPLACE` يضمن جولة جديدة بعد
    /// الحالي دائماً، وتشغيلٌ زائد على طابور فارغ رخيص (يخرج فوراً).
    ///
    /// يشغّل معالجة الطابور بالمسار المناسب لنسخة النظام:
    /// أندرويد 14+ → وظيفة نقل بيانات بمبادرة المستخدم (بلا خدمة أمامية)،
    /// وما دونه → عمل خلفية عادي. يسقط تلقائياً إلى WorkManager إن تعذّرت
    /// جدولة وظيفة UIDT (مثلاً حين يُستدعى والتطبيق في الخلفية).
    fun enqueue(context: Context) {
        val store = LocalStore.get(context)
        val queue = store.downloadQueue()
        val restricted = store.downloadQueueWifiOnlyIds()
        // القيد صار لكل عنصر: نطلب شبكة غير محدودة فقط إن كان **كل** ما في
        // الطابور مقيَّداً، وإلا نعمل على أي شبكة ويتخطّى المعالج المقيَّد
        // ويجدول له عملاً بقيد «غير محدودة» في نهاية الجولة.
        val wifiOnly = queue.isNotEmpty() && queue.all { it in restricted }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (scheduleUserInitiated(context, wifiOnly)) return
        }
        enqueueBackgroundWork(context, wifiOnly)
    }

    /// يوقف كلّ ما هو مجدوَل — يُنادى عند إلغاء الطابور كلّه وحده: بلاه تبقى
    /// محاولة مؤجَّلة تستيقظ بعد دقائق فتُظهر إشعار تقدّم لطابور أُلغي.
    fun cancelScheduled(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(WORK_NAME)
        manager.cancelUniqueWork(WIFI_WORK_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
        }
    }

    /// عمل مؤجَّل للعناصر المقيَّدة بالواي فاي — يستأنفها فور توفّر شبكة غير
    /// محدودة، بلا أن يمسّ عمل التحميل الجاري.
    fun enqueueUnmetered(context: Context) {
        val request = OneTimeWorkRequestBuilder<LessonDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WIFI_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleUserInitiated(context: Context, wifiOnly: Boolean): Boolean = runCatching {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val network = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .apply {
                if (wifiOnly) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            .build()
        val info = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, LessonDownloadJobService::class.java),
        )
            .setUserInitiated(true)
            .setRequiredNetwork(network)
            // تقدير تقريبي (≈8 ميغابايت لكل درس) يساعد النظام على الجدولة
            // وعرض حجم النقل للمستخدم؛ لا يؤثر على صحة التحميل.
            .setEstimatedNetworkBytes(
                LocalStore.get(context).downloadQueue().size.coerceAtLeast(1) * 8L * 1_024 * 1_024,
                0L,
            )
            .build()
        scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }.getOrDefault(false)

    private fun enqueueBackgroundWork(context: Context, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<LessonDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                    )
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
