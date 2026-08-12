package com.ali.menbaradkshk.media

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ali.menbaradkshk.MainActivity
import com.ali.menbaradkshk.MinbarApplication
import com.ali.menbaradkshk.data.ContentRepository
import com.ali.menbaradkshk.data.LocalStore
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var store: LocalStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trackingJob: Job? = null
    private var sleepJob: Job? = null
    private var trackedLessonId = ""
    private var lastTrackedPositionMs = 0L
    /// استماع فعليّ متراكم للدرس الحالي — أساس احتساب «استُمع إليه».
    private var listenedMs = 0L
    private var playCounted = false

    override fun onCreate() {
        super.onCreate()
        store = LocalStore.get(this)
        // الملفات المحلّية (`file://`) تُقرأ مباشرةً بـ`FileDataSource` عبر
        // `DefaultDataSource`، فلا تدخل الكاش أبداً: «تنزيلاتي» فهرس منفصل.
        val networkSources = CacheDataSource.Factory()
            .setCache(MinbarApplication.mediaCache(this))
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setUserAgent("MinbarAdkassahk/${com.ali.menbaradkshk.BuildConfig.VERSION_NAME}")
                    .setAllowCrossProtocolRedirects(true),
            )
            // فشل الكاش لا يجوز أن يُفشِل التشغيل — نتابع من الشبكة.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        player = ExoPlayer.Builder(this)
            // ⚠️ إلزاميّ: بلا سمات صوت صريحة وطلب البؤرة الصوتيّة يستمرّ الدرس
            // فوق مكالمة أو تطبيق آخر، وتخفض بعض واجهات المصنّعين صوت التطبيق.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // إيقاف تلقائي عند نزع السمّاعة بدل بثّ الدرس على مكبّر الجهاز.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, networkSources)),
            )
            .build()
        player.apply {
            setPlaybackSpeed(store.playbackSpeed().toFloat())
            addListener(
                object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        trackedLessonId = mediaItem?.mediaId.orEmpty()
                        lastTrackedPositionMs = currentPosition.coerceAtLeast(0L)
                        // درس جديد ⇒ عدّاد الاستماع يبدأ من الصفر، والاحتساب
                        // يُلغى إن غادر المستخدم قبل اكتمال المدّة المطلوبة.
                        listenedMs = 0L
                        playCounted = false
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                            // «تشغيل تلقائي للتالي» معطّل → نقف عند نهاية الدرس كما في الأصل.
                            if (!AutoplayState.enabled) pause()
                            // استئناف الدرس التالي من موضعه المحفوظ (نمط playLesson الأصلي).
                            val saved = store.position(trackedLessonId)
                            if (saved > 3_000L) seekTo(saved)
                        }
                        if (trackedLessonId.isNotBlank()) {
                            // السجل يقصد الفتح فعلاً فيبقى فوريّاً؛ أمّا عدّاد
                            // الاستماع والمشاهدة فيؤجَّلان إلى استماع فعليّ
                            // (انظر [countPlayIfListenedEnough]) — كانا يُحتسبان
                            // بمجرّد صيرورة الدرس حالياً، وعليهما تُبنى ريلات
                            // «الأكثر استماعاً» و«الرائج» وتحليلات اللوحة كلّها.
                            store.addRecentPlayed(trackedLessonId)
                            com.ali.menbaradkshk.widget.NowPlayingWidget
                                .refresh(this@PlaybackService)
                        }
                    }

                    /// موضع الدرس المغادَر يُحفظ من oldPosition لأن المشغّل صار على الجديد.
                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) {
                        val oldId = oldPosition.mediaItem?.mediaId.orEmpty()
                        if (oldId.isBlank() || oldId == newPosition.mediaItem?.mediaId) return
                        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                            store.markCompleted(oldId)
                        } else {
                            store.setPosition(oldId, oldPosition.positionMs)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY && duration > 0L) {
                            store.setDuration(currentMediaItem?.mediaId.orEmpty(), duration)
                        }
                        if (playbackState == Player.STATE_ENDED) {
                            currentMediaItem?.mediaId?.takeIf(String::isNotBlank)
                                ?.let(store::markCompleted)
                        }
                    }
                },
            )
        }
        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(activityIntent)
            .setCallback(resumptionCallback)
            .build()
        trackingJob = scope.launch {
            while (isActive) {
                delay(TRACK_INTERVAL_MS)
                if (player.isPlaying) {
                    val current = player.currentPosition.coerceAtLeast(0L)
                    val delta = (current - lastTrackedPositionMs).coerceIn(0L, TRACK_INTERVAL_MS * 2)
                    if (delta > 0L) {
                        store.addListenSeconds(delta / 1_000L)
                        // سلسلة الاستماع تتقدّم بالاستماع الفعلي لا بإتمام الدرس فقط.
                        store.recordDailyListen()
                        listenedMs += delta
                        countPlayIfListenedEnough()
                    }
                    lastTrackedPositionMs = current
                    persistPosition()
                }
            }
        }
        sleepJob = scope.launch {
            // مؤقّت النوم مسؤوليّة الخدمة لا نطاق الواجهة: سحب التطبيق من
            // التطبيقات الحديثة كان يقتل المؤقّت ويترك الصوت يعمل طوال الليل.
            while (isActive) {
                val endsAt = store.sleepEndsAtMs()
                if (endsAt <= 0L) {
                    delay(SLEEP_POLL_MS)
                    continue
                }
                val remaining = endsAt - System.currentTimeMillis()
                if (remaining > 0L) {
                    delay(remaining.coerceAtMost(SLEEP_POLL_MS))
                    continue
                }
                store.clearSleepTimer()
                player.pause()
                persistPosition()
            }
        }
    }

    /// يحتسب الاستماع بعد [COUNT_AFTER_MS] من السماع الفعليّ لا بمجرّد الفتح.
    private fun countPlayIfListenedEnough() {
        val id = trackedLessonId
        if (playCounted || id.isBlank() || listenedMs < COUNT_AFTER_MS) return
        playCounted = true
        store.incrementPlayCount(id)
        scope.launch { ContentRepository.get(this@PlaybackService).incrementView(id) }
    }

    /// استئناف التشغيل بعد موت العملية (زر التشغيل في الودجت/السماعة).
    private val resumptionCallback = object : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val lessonId = store.recentPlayedIds().firstOrNull().orEmpty()
            val lesson = store.lessons().firstOrNull { it.id == lessonId }
            val local = lessonId.takeIf(String::isNotBlank)?.let(store::localAudioPath)
            if (lesson == null || (local == null && lesson.audioUrl.isBlank())) {
                return Futures.immediateFailedFuture(
                    UnsupportedOperationException("لا يوجد درس سابق للاستئناف"),
                )
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(PlaybackController.mediaItemFor(lesson, local)),
                    0,
                    store.position(lessonId),
                ),
            )
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    private fun persistPosition() {
        val id = trackedLessonId.takeIf(String::isNotBlank) ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        if (duration > 0L && position >= duration - 3_000L) {
            store.markCompleted(id)
        } else {
            store.setPosition(id, position)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistPosition()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persistPosition()
        trackingJob?.cancel()
        sleepJob?.cancel()
        mediaSession.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TRACK_INTERVAL_MS = 5_000L
        /// دورة فحص مؤقّت النوم حين لا يكون قريباً؛ عند اقترابه ننام مدّته بالضبط.
        private const val SLEEP_POLL_MS = 15_000L
        /// 30 ثانية استماع فعليّ قبل احتساب الدرس «مسموعاً».
        private const val COUNT_AFTER_MS = 30_000L
    }
}
