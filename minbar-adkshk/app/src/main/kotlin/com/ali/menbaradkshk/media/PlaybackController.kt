package com.ali.menbaradkshk.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ali.menbaradkshk.data.DownloadRepository
import com.ali.menbaradkshk.data.Lesson
import com.ali.menbaradkshk.data.LocalStore
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackUiState(
    val connected: Boolean = false,
    val mediaId: String = "",
    val title: String = "",
    val speaker: String = "",
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val sleepEndsAtMs: Long? = null,
    val autoplay: Boolean = true,
    val error: String? = null,
)

/// «تشغيل تلقائي للتالي» — قيمة جلسة (تعود true عند إعادة تشغيل التطبيق) كما في الأصل.
object AutoplayState {
    @Volatile var enabled: Boolean = true
}

class PlaybackController(context: Context) {
    private val appContext = context.applicationContext
    private val store = LocalStore.get(context)
    private val downloads = DownloadRepository.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(
        PlaybackUiState(
            speed = store.playbackSpeed().toFloat(),
            // مؤقّت نوم قائم من جلسة سابقة يظهر فور إعادة فتح التطبيق.
            sleepEndsAtMs = store.sleepEndsAtMs().takeIf { it > System.currentTimeMillis() },
        ),
    )
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var released = false
    private var pendingPlay: (() -> Unit)? = null
    private var sleepJob: Job? = null
    // آخر طلب تشغيل — ملاذ إعادة البناء حين تُفرَّغ قائمة المشغّل (خدمة قُتلت)
    // فلا ينفع prepare() وحده.
    private var lastLesson: Lesson? = null
    private var lastQueue: List<Lesson> = emptyList()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)

        override fun onPlayerError(error: PlaybackException) {
            // بعد أيّ خطأ يعود المشغّل إلى STATE_IDLE؛ نُصفّر مؤشّرات الحالة كي لا
            // تبقى عالقة على «جارٍ التحميل» فتُعطَّل أزرار التشغيل.
            _state.value = _state.value.copy(
                error = messageFor(error),
                playing = false,
                loading = false,
            )
        }

        // أوّل تشغيل ناجح يمسح خطأ المحاولة السابقة كي لا يبقى معلّقاً أبداً.
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) clearError()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) clearError()
        }
    }

    init {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }.onSuccess { mediaController ->
                    // قد يكتمل الربط بعد release() — نحرّر فوراً كي لا يتسرّب متحكم حيّ.
                    if (released) {
                        mediaController.release()
                        return@onSuccess
                    }
                    controller = mediaController
                    mediaController.addListener(listener)
                    publish(mediaController)
                    pendingPlay?.invoke()
                    pendingPlay = null
                }.onFailure {
                    // لا مشغّل ⇒ النداء المؤجّل لن يُنفَّذ أبداً؛ نحرّره كي لا
                    // يحتجز الدرس وقائمة التشغيل طوال عمر المتحكّم.
                    pendingPlay = null
                    _state.value = _state.value.copy(error = "تعذّر الاتصال بمشغل الصوت.")
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
        scope.launch {
            while (isActive) {
                delay(500L)
                // نبض الموضع لازم أثناء التشغيل/التخزين المؤقّت فقط؛ بقيّة
                // التغيّرات تصل عبر onEvents — فلا داعي لإيقاظ الواجهة كل نصف
                // ثانية والمشغّل متوقّف.
                controller
                    ?.takeIf { it.isPlaying || it.playbackState == Player.STATE_BUFFERING }
                    ?.let(::publish)
            }
        }
        restoreSleepTimer()
    }

    /// يستعيد مؤقّت النوم المحفوظ بعد إتلاف النشاط. الخدمة هي من تضمن الإيقاف
    /// في الموعد فعلاً؛ هذا لتحديث الواجهة وإيقاف فوريّ ما دامت حيّة.
    private fun restoreSleepTimer() {
        val end = store.sleepEndsAtMs()
        if (end <= 0L) return
        if (end <= System.currentTimeMillis()) {
            store.clearSleepTimer()
            return
        }
        _state.value = _state.value.copy(sleepEndsAtMs = end)
        armSleepJob(end)
    }

    private fun armSleepJob(endsAtMs: Long) {
        sleepJob?.cancel()
        sleepJob = scope.launch {
            delay((endsAtMs - System.currentTimeMillis()).coerceAtLeast(0L))
            controller?.pause()
            store.clearSleepTimer()
            _state.value = _state.value.copy(sleepEndsAtMs = null)
        }
    }

    fun play(lesson: Lesson, queue: List<Lesson>, startAtMs: Long? = null, restart: Boolean = false) {
        val player = controller
        if (player == null) {
            // نداء وصل قبل اكتمال ربط MediaController (إقلاع بارد/رابط عميق) — يُنفَّذ عند الجاهزية.
            if (!released) pendingPlay = { play(lesson, queue, startAtMs, restart) }
            return
        }
        if (!restart && player.currentMediaItem?.mediaId == lesson.id && startAtMs == null) {
            // نفس الدرس ⇒ تبديل تشغيل/إيقاف عبر toggle() لا نداء player.play() مباشرةً،
            // كي تُعاد التهيئة إن كان المشغّل في STATE_IDLE بعد خطأ سابق.
            toggle()
            return
        }
        // الفحص قبل بناء القائمة: ifEmpty تُعيد الدرس نفسه فتُخفي غياب الصوت.
        if (lesson.audioUrl.isBlank() && !downloads.isDownloaded(lesson.id)) {
            _state.value = _state.value.copy(error = "هذا الدرس لا يحتوي ملفاً صوتياً.")
            return
        }
        // الدرس المطلوب يتصدّر القائمة دائماً إن رشّحه المرشّح خارجها، كي لا
        // يسقط الفهرس إلى 0 فيُشغَّل درس آخر بموضع الدرس المطلوب.
        val filtered = queue.filter { it.audioUrl.isNotBlank() || downloads.isDownloaded(it.id) }
        val playable = if (filtered.any { it.id == lesson.id }) filtered else listOf(lesson) + filtered
        val index = playable.indexOfFirst { it.id == lesson.id }.coerceAtLeast(0)
        // نستأنف من الموضع المحفوظ فقط إن تجاوز 3 ثوانٍ (نمط الأصل).
        val position = startAtMs
            ?: store.position(lesson.id).takeIf { it > 3_000L }
            ?: 0L
        lastLesson = lesson
        lastQueue = queue
        // محاولة جديدة ⇒ خطأ المحاولة السابقة لم يعد يمثّل الحالة.
        _state.value = _state.value.copy(error = null)
        player.setMediaItems(playable.map(::toMediaItem), index, position)
        player.prepare()
        player.play()
    }

    fun toggle() {
        val player = controller
        if (player == null) {
            // لا متحكّم بعد ⇒ نُعيد بناء آخر طلب (يُؤجَّل حتى اكتمال الربط).
            replayLast()
            return
        }
        if (player.isPlaying) {
            player.pause()
            return
        }
        // قائمة فارغة في STATE_IDLE (خدمة قُتلت) ⇒ prepare() لا يجد ما يُهيّئه.
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount == 0) {
            replayLast()
            return
        }
        prepareIfIdle(player)
        player.play()
    }

    /// إعادة التهيئة قبل أيّ استئناف: بعد أيّ خطأ يبقى المشغّل في STATE_IDLE،
    /// و`play()` عليه لا يفعل شيئاً — فتموت أزرار التشغيل إلى الأبد بلا هذا.
    private fun prepareIfIdle(player: Player) {
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            _state.value = _state.value.copy(error = null)
            player.prepare()
        }
    }

    /// إعادة بناء قائمة التشغيل من آخر طلب — restart كي لا يعود إلى فرع التبديل.
    private fun replayLast() {
        val lesson = lastLesson ?: return
        play(lesson, lastQueue, restart = true)
    }

    /// إعادة المحاولة بعد فشل التشغيل — يستدعيها شريط الخطأ في الواجهة.
    fun retry() {
        val player = controller
        if (player == null || player.mediaItemCount == 0) {
            replayLast()
            return
        }
        _state.value = _state.value.copy(error = null)
        player.prepare()
        player.play()
    }

    /// رسالة عربية مناسبة لسبب الفشل — الشبكة أشيع الأسباب.
    private fun messageFor(error: PlaybackException): String {
        val network = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        val missing = error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        return when {
            network -> "تعذّر تشغيل الصوت — تحقّق من الاتصال ثم أعد المحاولة."
            missing -> "الملف الصوتي غير متاح الآن. أعد المحاولة لاحقاً."
            else -> "تعذّر تشغيل الصوت. أعد المحاولة."
        }
    }

    fun seekTo(milliseconds: Long) {
        controller?.seekTo(milliseconds.coerceAtLeast(0L))
    }

    fun skipForward() {
        val seconds = store.skipSeconds()
        controller?.let {
            val target = it.currentPosition + seconds * 1_000L
            // المدة غير معروفة أثناء التحميل (سالبة) فلا تصلح سقفاً.
            val cap = it.duration.takeIf { duration -> duration > 0L }
            it.seekTo(if (cap != null) target.coerceAtMost(cap) else target)
        }
    }

    fun skipBackward() {
        val seconds = store.skipSeconds()
        controller?.let { it.seekTo((it.currentPosition - seconds * 1_000L).coerceAtLeast(0L)) }
    }

    fun next() {
        val player = controller ?: return
        prepareIfIdle(player)
        player.seekToNextMediaItem()
    }

    fun previous() {
        val player = controller ?: return
        prepareIfIdle(player)
        player.seekToPreviousMediaItem()
    }

    fun setSpeed(speed: Float) {
        val safe = speed.coerceIn(0.75f, 2f)
        store.setPlaybackSpeed(safe.toDouble())
        controller?.setPlaybackSpeed(safe)
        _state.value = _state.value.copy(speed = safe)
    }

    fun setSleepTimer(minutes: Int) {
        val end = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        // الموعد يُحفظ ليقرأه `PlaybackService`: هذا النطاق يُحرَّر مع
        // `AppViewModel.onCleared`، فكان سحب التطبيق يقتل المؤقّت بينما يستمرّ
        // التشغيل عبر الخدمة الأمامية فيعمل الصوت طوال الليل.
        store.setSleepEndsAtMs(end)
        _state.value = _state.value.copy(sleepEndsAtMs = end)
        armSleepJob(end)
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        store.clearSleepTimer()
        _state.value = _state.value.copy(sleepEndsAtMs = null)
    }

    fun setAutoplay(enabled: Boolean) {
        AutoplayState.enabled = enabled
        _state.value = _state.value.copy(autoplay = enabled)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun toMediaItem(lesson: Lesson): MediaItem =
        mediaItemFor(lesson, downloads.localPath(lesson.id))

    private fun publish(player: Player) {
        val metadata = player.mediaMetadata
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        _state.value = _state.value.copy(
            connected = true,
            mediaId = player.currentMediaItem?.mediaId.orEmpty(),
            title = metadata.title?.toString().orEmpty(),
            speaker = metadata.artist?.toString().orEmpty(),
            playing = player.isPlaying,
            loading = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            speed = player.playbackParameters.speed,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            autoplay = AutoplayState.enabled,
        )
    }

    fun release() {
        released = true
        pendingPlay = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        scope.cancel()
    }

    companion object {
        /// بناء عنصر التشغيل من الدرس — يستعمله أيضاً استئناف الجلسة في `PlaybackService`.
        fun mediaItemFor(lesson: Lesson, localPath: String?): MediaItem {
            val uri = if (localPath != null) Uri.fromFile(File(localPath)) else Uri.parse(lesson.audioUrl)
            return MediaItem.Builder()
                .setMediaId(lesson.id)
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(lesson.displayTitle)
                        .setArtist(lesson.speaker.ifBlank { "منبر ادكصهك" })
                        .setIsPlayable(true)
                        .build(),
                )
                .build()
        }
    }
}
