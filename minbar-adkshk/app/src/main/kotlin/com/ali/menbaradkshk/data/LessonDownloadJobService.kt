package com.ali.menbaradkshk.data

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/// وظيفة «نقل بيانات بمبادرة المستخدم» (User-Initiated Data Transfer) —
/// المسار المعتمد من أندرويد 14+ لتنزيل يبدأه المستخدم. يستمر مع إغلاق
/// الشاشة والخروج من التطبيق، ويعرض النظام إشعاره الخاص، **دون** الحاجة
/// إلى إذن FOREGROUND_SERVICE_DATA_SYNC إطلاقاً.
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class LessonDownloadJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val processor = DownloadQueueProcessor(applicationContext)
        // النظام يشترط إشعاراً ظاهراً طوال عمل وظيفة النقل.
        runCatching {
            setNotification(
                params,
                DownloadQueueProcessor.NOTIFICATION_ID,
                processor.build("جارٍ تجهيز التحميل…", ongoing = true),
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        }
        work = scope.launch {
            val result = runCatching {
                processor.run { notification ->
                    runCatching {
                        setNotification(
                            params,
                            DownloadQueueProcessor.NOTIFICATION_ID,
                            notification,
                            JOB_END_NOTIFICATION_POLICY_REMOVE,
                        )
                    }
                }
            }.getOrDefault(DownloadRunResult.NEEDS_RETRY)
            // الوظيفة أُلغيت من onStopJob: النظام تولّى إعادة الجدولة بنفسه،
            // وإعلان الانتهاء بعدها يخصّ وظيفة لم تعد قائمة.
            if (!isActive) return@launch
            jobFinished(params, result == DownloadRunResult.NEEDS_RETRY)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        // أعِد الجدولة: الطابور محفوظ والملف الجزئي يُستأنف من موضعه.
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
