package com.ali.menbaradkshk.data

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ali.menbaradkshk.MainActivity
import com.ali.menbaradkshk.notification.NotificationChannels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex

/// نتيجة معالجة طابور التحميل.
enum class DownloadRunResult { FINISHED, NEEDS_RETRY }

/// منطق تحميل طابور الدروس، مشترك بين مسارَي التشغيل:
/// وظيفة «نقل بيانات بمبادرة المستخدم» (أندرويد 14+) وعمل الخلفية العادي.
/// يعالج الطابور تسلسلياً، ويحدّث إشعار التقدّم، ويطلب إعادة المحاولة عند
/// انقطاع الشبكة مع إبقاء الملف الجزئي ليُستأنف من موضعه.
class DownloadQueueProcessor(private val context: Context) {

    private val store = LocalStore.get(context)
    private val downloads = DownloadRepository.get(context)
    private val content = ContentRepository.get(context)

    /// [onProgressNotification] يسمح لمُشغّل UIDT بتمرير الإشعار إلى النظام.
    ///
    /// المعالجة **مفردة على مستوى العملية**: مسارا التشغيل (وظيفة UIDT وعمل
    /// WorkManager) قد يُطلَقان معاً، وحين يتشابكان يعملان على `queue.first()`
    /// نفسه (المعرّف لا يُحذف إلا بعد الاكتمال) فيكتبان الملف الجزئي نفسه
    /// بإزاحتين مستقلّتين فيتلف. المتأخّر ينسحب فوراً ويُعاد جدولته.
    suspend fun run(onProgressNotification: (Notification) -> Unit = {}): DownloadRunResult {
        if (!runLock.tryLock()) return DownloadRunResult.NEEDS_RETRY
        try {
            return runExclusively(onProgressNotification)
        } finally {
            runLock.unlock()
        }
    }

    private suspend fun runExclusively(
        onProgressNotification: (Notification) -> Unit,
    ): DownloadRunResult {
        var queue = store.downloadQueue()
        if (queue.isEmpty()) {
            downloads.queueState.value = null
            return DownloadRunResult.FINISHED
        }
        // ⏸ موقوف بطلب المستخدم: ننسحب بنجاح بلا إعادة جدولة — الطابور
        // والملفات الجزئية باقية، ولا يوقظنا إلا زرّ «استئناف».
        if (downloads.paused.value) {
            publishPaused()
            return DownloadRunResult.FINISHED
        }
        notify("جارٍ تجهيز التحميل…", onProgressNotification)

        var failures = 0
        // عناصر مقيَّدة بالواي فاي صادفناها على شبكة محدودة: تبقى في الطابور
        // ونتجاوزها في هذه الجولة، ثم نجدول لها عملاً بقيد شبكة غير محدودة.
        val deferred = mutableSetOf<String>()
        while (true) {
            if (downloads.paused.value) {
                publishPaused()
                return DownloadRunResult.FINISHED
            }
            queue = store.downloadQueue()
            val id = queue.firstOrNull { it !in deferred } ?: break
            val lesson = content.state.value.lessonById[id]
            if (lesson == null || lesson.audioUrl.isBlank() || downloads.isDownloaded(id)) {
                store.removeFromDownloadQueue(id)
                continue
            }
            // أُلغي قبل أن يبدأ دوره: يخرج بلا محاولة ولا رسالة فشل.
            if (downloads.isCancelRequested(id)) {
                downloads.clearCancel(id)
                store.removeFromDownloadQueue(id)
                continue
            }
            // القيد لكل عنصر لا حالة عامّة: ضغطة تحميل يدويّة لم تعد تُنزّل
            // دفعةً تلقائية اختار لها المستخدم «واي فاي فقط» على بيانات الجوّال.
            if (id in store.downloadQueueWifiOnlyIds() && onMeteredNetwork()) {
                deferred += id
                continue
            }
            val label = store.downloadQueueLabel()
            val total = store.downloadQueueTotal().coerceAtLeast(1)
            val done = (total - queue.size).coerceAtLeast(0)
            downloads.queueState.value = DownloadQueueState(label, done, total, lesson.displayTitle)
            notify("($done/$total) ${lesson.displayTitle}", onProgressNotification)

            try {
                // مراقب التقدّم: يقرأ بايتات الملفّ الجاري ويحدّث الحالة
                // والإشعار مرّة كل ثانية. بدونه كان الاثنان يعرضان `done/total`
                // فقط — أي صفراً ثابتاً طوال تحميل درس واحد.
                coroutineScope {
                    val watcher = launch {
                        var lastShown = -1
                        while (currentCoroutineContext().isActive) {
                            val p = downloads.progress.value[id]
                            if (p != null && p.percent != lastShown) {
                                lastShown = p.percent
                                downloads.queueState.value = DownloadQueueState(
                                    label = label,
                                    done = done,
                                    total = total,
                                    currentTitle = lesson.displayTitle,
                                    filePercent = p.percent,
                                    fileDownloadedBytes = p.downloadedBytes,
                                    fileTotalBytes = p.totalBytes,
                                )
                                notify(
                                    buildString {
                                        append("($done/$total) ${lesson.displayTitle}")
                                        if (p.percent in 0..100) append(" — ${p.percent}%")
                                    },
                                    onProgressNotification,
                                    percent = p.percent,
                                )
                            }
                            delay(PROGRESS_TICK_MS)
                        }
                    }
                    try {
                        downloads.download(lesson)
                    } finally {
                        watcher.cancel()
                    }
                }
                store.removeFromDownloadQueue(id)
            } catch (paused: DownloadPausedException) {
                // الدرس يبقى في رأس الطابور وملفّه الجزئي محفوظ.
                publishPaused(lesson.displayTitle)
                return DownloadRunResult.FINISHED
            } catch (cancelled: DownloadCancelledException) {
                downloads.clearCancel(id)
                store.removeFromDownloadQueue(id)
            } catch (retryable: RetryableDownloadException) {
                // انقطاع اتصال: يبقى الدرس في الطابور ويُستأنف الملف الجزئي لاحقاً.
                downloads.queueState.value = DownloadQueueState(
                    label,
                    done,
                    total,
                    lesson.displayTitle,
                    waitingForNetwork = true,
                )
                notify("بانتظار عودة الاتصال للاستئناف…", onProgressNotification)
                return DownloadRunResult.NEEDS_RETRY
            } catch (cancelled: CancellationException) {
                // ❗ لا تُبتلع أبداً: ابتلاعها كان يُسقط الدرس من الطابور كـ«فاشل»،
                // ثم يرمي كل تكرار تالٍ فوراً فيُمحى الطابور كلّه صامتاً ويُعرض
                // إشعار «اكتمل التحميل». الطابور محفوظ كما يَعِد onStopJob.
                downloads.queueState.value = null
                throw cancelled
            } catch (permanent: Throwable) {
                failures++
                store.removeFromDownloadQueue(id)
            }
        }

        downloads.queueState.value = null
        if (deferred.isNotEmpty()) {
            // لا نمسح الطابور: المؤجَّل ينتظر شبكة غير محدودة بعمل مستقلّ.
            DownloadScheduler.enqueueUnmetered(context)
            showDone(
                "بانتظار الواي فاي لإكمال ${deferred.size} درساً" +
                    if (failures == 0) "." else " (تعذّر $failures).",
            )
            return DownloadRunResult.FINISHED
        }
        store.clearDownloadQueueIfEmpty()
        showDone(
            if (failures == 0) "اكتمل تحميل الدروس للاستماع دون إنترنت."
            else "اكتمل التحميل مع تعذّر $failures درساً.",
        )
        return DownloadRunResult.FINISHED
    }

    /// حالة «موقوف مؤقّتاً» للواجهة والإشعار: تُبقي العنوان والنسبة ظاهرين
    /// فيعرف المستخدم من أين سيُستأنف، ويُزال الإشعار المُلازم لأنّ شيئاً
    /// لا يجري الآن — إشعار تقدّم متجمّد يوحي بعطل.
    private fun publishPaused(title: String = "") {
        val previous = downloads.queueState.value
        val queued = store.downloadQueue()
        downloads.queueState.value = DownloadQueueState(
            label = store.downloadQueueLabel(),
            done = (store.downloadQueueTotal() - queued.size).coerceAtLeast(0),
            total = store.downloadQueueTotal().coerceAtLeast(1),
            currentTitle = title.ifBlank { previous?.currentTitle.orEmpty() },
            paused = true,
            filePercent = previous?.filePercent ?: -1,
            fileDownloadedBytes = previous?.fileDownloadedBytes ?: 0L,
            fileTotalBytes = previous?.fileTotalBytes ?: 0L,
        )
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /// هل الشبكة الحالية محدودة (بيانات الجوّال)؟ غياب الشبكة يُعامَل كغير
    /// محدودة كي لا يُؤجَّل الدرس بلا داعٍ — فشل الاتصال يُعالَج بإعادة المحاولة.
    private fun onMeteredNetwork(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun notify(
        text: String,
        onProgressNotification: (Notification) -> Unit,
        percent: Int = -1,
    ) {
        val notification = build(text, ongoing = true, percent = percent)
        onProgressNotification(notification)
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun showDone(text: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(NOTIFICATION_ID)
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { manager.notify(NOTIFICATION_ID + 1, build(text, ongoing = false)) }
        }
    }

    fun build(text: String, ongoing: Boolean, percent: Int = -1): Notification =
        NotificationCompat.Builder(context, NotificationChannels.DOWNLOADS)
            // شريط تقدّم حقيقيّ: غير محدَّد ما دام الحجم مجهولاً، ثمّ
            // بالنسبة المئويّة. كان الإشعار نصّاً ثابتاً بلا أيّ شريط.
            .apply {
                if (ongoing) setProgress(100, percent.coerceIn(0, 100), percent !in 0..100)
            }
            .setSmallIcon(
                if (ongoing) android.R.drawable.stat_sys_download
                else android.R.drawable.stat_sys_download_done,
            )
            .setContentTitle("تحميل الدروس")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    91,
                    // وجهة صريحة: النقر كان يفتح الرئيسية لأن النيّة بلا `data`.
                    Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = Uri.parse(DOWNLOADS_DEEP_LINK)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(ongoing)
            // إشعار الاكتمال يختفي بالنقر؛ إشعار التقدّم يبقى حتى انتهاء العمل.
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        const val NOTIFICATION_ID = 90

        /// نبضة تحديث شريط التقدّم. البايتات تُحدَّث مع كل قراءة (~8KB)،
        /// فتحديث الإشعار بنفس الوتيرة يُغرق النظام — نقرأ آخر قيمة كل ثانية.
        private const val PROGRESS_TICK_MS = 1_000L

        /// رابط عميق لشاشة «تنزيلاتي» بنمط `minbar://<host>` نفسه المستعمل في
        /// إشعارات الخادم (`minbar://my-submissions`).
        const val DOWNLOADS_DEEP_LINK = "minbar://downloads"

        /// قفل معالجة الطابور — مفرد على مستوى العملية مهما تعدّد المُطلِقون.
        private val runLock = Mutex()
    }
}
