package com.ali.menbaradkshk

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import com.ali.menbaradkshk.data.LocalStore
import com.ali.menbaradkshk.notification.NotificationChannels
import com.ali.menbaradkshk.notification.BackgroundScheduler
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import androidx.work.Configuration
import java.io.File

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MinbarApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            // Keep WorkManager's IDs separate from DownloadScheduler.JOB_ID (4210).
            .setJobSchedulerJobIdRange(1_000, 3_000)
            .build()

    /// Coil 3 لا يُنشئ كاشاً قرصياً افتراضياً إطلاقاً، فكانت كل صورة تُنزَّل من
    /// جديد بعد كل إقلاع. المفاتيح روابط Storage الحاملة لـ`token` فالإبطال ذاتي.
    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "image_cache"))
                    .maxSizeBytes(IMAGE_CACHE_BYTES)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, 0.20).build()
            }
            .build()

    override fun onCreate() {
        super.onCreate()
        LocalStore.get(this)
        createNotificationChannels()
        initializeFirebase()
        BackgroundScheduler.scheduleAll(this)
        // استئناف طابور التحميل إن بقيت فيه دروس من جلسة سابقة.
        if (LocalStore.get(this).downloadQueue().isNotEmpty()) {
            com.ali.menbaradkshk.data.DownloadScheduler.enqueue(this)
        }
    }

    private fun initializeFirebase() {
        runCatching {
            val app = FirebaseApp.getApps(this).firstOrNull() ?: FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setApiKey("YOUR_FIREBASE_API_KEY")
                    .setApplicationId("YOUR_FIREBASE_APP_ID")
                    .setGcmSenderId("YOUR_SENDER_ID")
                    .setProjectId("mxqp-8d1e8")
                    .setStorageBucket("mxqp-8d1e8.firebasestorage.app")
                    .build(),
            )
            FirebaseAppCheck.getInstance(app)
                .installAppCheckProviderFactory(MinbarAppCheckProvider.factory())
            if (FirebaseAuth.getInstance(app).currentUser == null) {
                FirebaseAuth.getInstance(app).signInAnonymously()
            }
            if (LocalStore.get(this).notificationsEnabled()) {
                FirebaseMessaging.getInstance().subscribeToTopic("content")
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    NotificationChannels.CONTENT,
                    getString(R.string.content_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                NotificationChannel(
                    NotificationChannels.WARD,
                    getString(R.string.ward_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                NotificationChannel(
                    NotificationChannels.DOWNLOADS,
                    "تحميل الدروس",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            ),
        )
    }

    companion object {
        private const val MEDIA_CACHE_BYTES = 256L * 1_024 * 1_024
        private const val IMAGE_CACHE_BYTES = 64L * 1_024 * 1_024

        @Volatile
        private var cacheInstance: SimpleCache? = null

        /// كاش الوسائط: درس غير منزَّل كان يُنزَّل كاملاً من جديد في كل استماع.
        ///
        /// **مفرد حقيقي على مستوى العملية إلزاماً** — نسختا `SimpleCache` على
        /// المجلد نفسه ترميان استثناءً. وهو فهرس منفصل تماماً عن «تنزيلاتي»:
        /// لا يمسّ `pruneDownloads` ولا حذف التنزيل، والملف المنزَّل صراحةً
        /// يبقى مقدَّماً دائماً لأنّه يُقرأ بمسار محلّي لا يمرّ بالكاش أصلاً.
        @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
        fun mediaCache(context: Context): SimpleCache = cacheInstance ?: synchronized(this) {
            cacheInstance ?: SimpleCache(
                File(context.applicationContext.cacheDir, "media_cache").apply { mkdirs() },
                LeastRecentlyUsedCacheEvictor(MEDIA_CACHE_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cacheInstance = it }
        }
    }
}
