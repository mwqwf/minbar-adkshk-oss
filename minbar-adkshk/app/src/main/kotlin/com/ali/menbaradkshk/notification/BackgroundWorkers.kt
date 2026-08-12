package com.ali.menbaradkshk.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ali.menbaradkshk.MainActivity
import com.ali.menbaradkshk.R
import com.ali.menbaradkshk.data.ContentRepository
import com.ali.menbaradkshk.data.DownloadRepository
import com.ali.menbaradkshk.data.LocalStore
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class ContinueReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = LocalStore.get(applicationContext)
        if (!store.notificationsEnabled() || !store.continueReminderEnabled()) return Result.success()
        val completed = store.completedIds().toSet()
        val candidate = store.positions()
            .filter { (id, position) -> position > 3_000L && id !in completed }
            .maxByOrNull { it.value }
            ?.key
            ?: return Result.success()
        val lesson = ContentRepository.get(applicationContext).state.value.lessonById[candidate]
            ?: return Result.success()
        NotificationPublisher.show(
            applicationContext,
            id = 1,
            title = "تابع الاستماع",
            body = "لديك درس لم تكمله — ${lesson.displayTitle}",
            destination = "https://minbar-adkassahk.vercel.app/lesson/${lesson.id}",
            channel = NotificationChannels.CONTENT,
        )
        return Result.success()
    }
}

class WardWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = LocalStore.get(applicationContext)
        if (!store.notificationsEnabled() || !store.wardEnabled()) return Result.success()
        val lesson = ContentRepository.get(applicationContext).dailyWard() ?: return Result.success()
        NotificationPublisher.show(
            applicationContext,
            id = 700,
            title = "وِرد اليوم 🌿",
            body = lesson.displayTitle,
            destination = "https://minbar-adkassahk.vercel.app/lesson/${lesson.id}",
            channel = NotificationChannels.WARD,
        )
        return Result.success()
    }
}

class AutoDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = LocalStore.get(applicationContext)
        if (!store.autoDownloadEnabled()) return Result.success()
        val content = ContentRepository.get(applicationContext)
        // فشل المزامنة لا يمنع تحميل ما في الكاش، أمّا إيقاف العمل من
        // WorkManager فيجب أن يُنهيه فوراً لا أن يمضي في جدولة تنزيلات.
        runCatching { content.refresh(false) }.exceptionOrNull()?.let { failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
        }
        val downloads = DownloadRepository.get(applicationContext)
        // الهدف كما في الأصل: 'recent' = أحدث الدروس، 'main' = الخلاصة المقترحة.
        val target = store.autoDownloadTarget() ?: "recent"
        val lessons = if (target == "main") {
            content.recommended(MAX_PER_RUN)
        } else {
            content.newest(MAX_PER_RUN)
        }
        val missing = lessons
            .filter { it.audioUrl.isNotBlank() && !downloads.isDownloaded(it.id) }
            .map { it.id }
        if (missing.isEmpty()) return Result.success()
        // علم إلغاء قديم لدرس ألغاه المستخدم يدويّاً كان يُسقط إدراجه هنا
        // بصمت — والتحميل التلقائي لا واجهة له تُظهر ما سقط.
        missing.forEach { downloads.clearCancel(it) }
        // يمرّ عبر طابور التحميل الخلفي نفسه ليستفيد من الاستئناف عند
        // انقطاع الشبكة وإعادة المحاولة التلقائية وإشعار التقدّم.
        store.addToDownloadQueue(
            missing,
            if (target == "main") "خلاصتك المقترحة" else "أحدث الدروس",
            wifiOnly = store.autoDownloadWifiOnly(),
        )
        com.ali.menbaradkshk.data.DownloadScheduler.enqueue(applicationContext)
        return Result.success()
    }

    companion object {
        private const val MAX_PER_RUN = 30
    }
}

/// 🔔 فحص التحديث اليوميّ — الطبقة التي كانت ناقصة.
///
/// شاشة التذكير لا تُرى إلا عند **فتح** التطبيق، ومن يفتحه نادراً يبقى على
/// نسخة قديمة أسابيع بلا أن يعلم. هذا العامل يقرأ وثيقة الإعداد مرّة كل
/// يوم ويُشعر صاحب النسخة الأقدم — الإشعار قابل للصرف كأيّ إشعار، ولا
/// يتكرّر لنسخة صُرفت شاشتُها.
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val config = com.ali.menbaradkshk.data.AppConfigRepository.get(applicationContext)
        val status = runCatching { config.statusForcingRefresh() }.getOrNull() ?: return Result.success()
        val (latest, message) = when (status) {
            is com.ali.menbaradkshk.data.AppConfigRepository.Status.Required ->
                status.latest to status.message
            is com.ali.menbaradkshk.data.AppConfigRepository.Status.Optional ->
                status.latest to status.message
            else -> return Result.success()
        }
        // خانق يوميّ خاصّ بالإشعار: لا يُزعج أكثر من مرّة في اليوم لنسخة
        // واحدة، ولا يُرسَل إن كان المستخدم قد صرف شاشة هذه النسخة أصلاً.
        if (!config.shouldNotify(latest)) return Result.success()
        config.markNotified(latest)
        NotificationPublisher.show(
            applicationContext,
            id = 950,
            title = "تتوفّر نسخة أحدث من منبر ادكصهك",
            body = message.ifBlank { "حدِّث التطبيق لتصلك المزايا والإصلاحات الجديدة." },
            destination = com.ali.menbaradkshk.data.AppConfigRepository.PLAY_URL,
            channel = NotificationChannels.CONTENT,
        )
        return Result.success()
    }
}

object BackgroundScheduler {
    private const val CONTINUE_WORK = "continue_reminder"
    private const val WARD_WORK = "daily_ward"
    private const val AUTO_DOWNLOAD_WORK = "auto_download"
    private const val UPDATE_CHECK_WORK = "update_check"

    fun scheduleAll(context: Context) {
        scheduleContinue(context)
        scheduleWard(context)
        scheduleAutoDownload(context)
        scheduleUpdateCheck(context)
    }

    /// غير مشروط بإعدادات الإشعارات الاختيارية: تذكير التحديث ليس محتوى
    /// ترويجياً بل شرط بقاء التطبيق سليماً. (وإذن الإشعارات نفسه يبقى
    /// حاكماً: بلا إذن لا يُعرض شيء.)
    fun scheduleUpdateCheck(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UPDATE_CHECK_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun scheduleContinue(context: Context) {
        val manager = WorkManager.getInstance(context)
        val store = LocalStore.get(context)
        if (!store.notificationsEnabled() || !store.continueReminderEnabled()) {
            manager.cancelUniqueWork(CONTINUE_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<ContinueReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayUntil(19, 0), TimeUnit.MILLISECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(
            CONTINUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun scheduleWard(context: Context) {
        val manager = WorkManager.getInstance(context)
        val store = LocalStore.get(context)
        if (!store.notificationsEnabled() || !store.wardEnabled()) {
            manager.cancelUniqueWork(WARD_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<WardWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(
                delayUntil(store.wardHour(), store.wardMinute()),
                TimeUnit.MILLISECONDS,
            )
            .build()
        manager.enqueueUniquePeriodicWork(WARD_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun scheduleAutoDownload(context: Context) {
        val manager = WorkManager.getInstance(context)
        val store = LocalStore.get(context)
        if (!store.autoDownloadEnabled()) {
            manager.cancelUniqueWork(AUTO_DOWNLOAD_WORK)
            return
        }
        val network = if (store.autoDownloadWifiOnly()) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        val request = PeriodicWorkRequestBuilder<AutoDownloadWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        manager.enqueueUniquePeriodicWork(
            AUTO_DOWNLOAD_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun delayUntil(hour: Int, minute: Int): Long {
        val now = ZonedDateTime.now()
        var due = now.withHour(hour.coerceIn(0, 23))
            .withMinute(minute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)
        if (!due.isAfter(now)) due = due.plusDays(1)
        return Duration.between(now, due).toMillis().coerceAtLeast(0L)
    }
}

private object NotificationPublisher {
    fun show(
        context: Context,
        id: Int,
        title: String,
        body: String,
        destination: String,
        channel: String,
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(destination)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
