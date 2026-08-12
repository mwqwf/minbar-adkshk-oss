package com.ali.menbaradkshk.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ali.menbaradkshk.data.Lesson
import com.ali.menbaradkshk.media.PlaybackUiState
import com.ali.menbaradkshk.util.formatDuration
import com.ali.menbaradkshk.util.lessonShareText
import kotlinx.coroutines.launch

/// أحمر المفضّلة على السطح الفاتح: النسبة السابقة (0xFFD84343) كانت 4.25
/// وهي دون حدّ التباين، فرُفعت إلى 6.07 بلا تغيير في هوية اللون.
private val FavoriteRed = Color(0xFFB32F2F)

/// صف درس كامل بنمط الأصل: زر تشغيل دائري ملوّن، عنوان، متحدث، مدّة،
/// وأزرار المفضّلة/التنزيل(بنسبة)/المشاركة، وشريط تقدّم محفوظ أسفل البطاقة.
@Composable
fun AudioItem(
    vm: AppViewModel,
    lesson: Lesson,
    playlist: List<Lesson>,
    playback: PlaybackUiState,
    showActions: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by vm.store.revision.collectAsState()
    val progressMap by vm.downloads.progress.collectAsState()
    var downloading by remember { mutableStateOf(false) }

    val active = playback.mediaId == lesson.id && lesson.id.isNotBlank()
    val playing = active && playback.playing
    val durMs = lesson.durationMs.takeIf { it > 0L } ?: vm.store.duration(lesson.id)
    val favorite = remember(revision, lesson.id) { vm.store.isFavorite(lesson.id) }
    val downloaded = remember(revision, lesson.id) { vm.downloads.isDownloaded(lesson.id) }
    val savedProgress = if (durMs > 0L) {
        (vm.store.position(lesson.id).toFloat() / durMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val liveProgress = if (active && playback.durationMs > 0L) {
        (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
    } else {
        savedProgress
    }
    val accent = colorForCategory(lesson.categoryId)
    val percent = progressMap[lesson.id]?.percent ?: 0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clickable { vm.openPlayer(lesson, playlist) },
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(if (playing) GreenBrand else accent, CircleShape)
                        .clickable { vm.playback.play(lesson, playlist) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "إيقاف" else "تشغيل",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        lesson.displayTitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = if (active) Teal else MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                    if (lesson.speaker.isNotBlank()) {
                        Text(
                            lesson.speaker,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (durMs > 0L) {
                        Text(
                            formatDuration(durMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showActions) {
                    IconButton(onClick = { vm.toggleFavorite(lesson.id) }) {
                        Icon(
                            if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (favorite) "إزالة من المفضّلة" else "إضافة للمفضّلة",
                            tint = if (favorite) FavoriteRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DownloadButton(vm, lesson, size = 38.dp)
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { shareLesson(context, vm, lesson) }) {
                        Icon(Icons.Filled.Share, contentDescription = "مشاركة", tint = Teal)
                    }
                }
            }
            if (liveProgress > 0.02f) {
                Spacer(Modifier.height(8.dp))
                ClassicLinearProgress(
                    progress = liveProgress,
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
        }
    }
}

/// بطاقة أفقية للريلات في الرئيسية (نمط الأصل _AudioCard): تدرّج لون القسم،
/// أيقونة القسم، شارة المدّة، وشريط تقدّم اختياري، ثم العنوان.
@Composable
fun AudioCard(
    vm: AppViewModel,
    lesson: Lesson,
    playlist: List<Lesson>,
    playback: PlaybackUiState,
    showProgress: Boolean = false,
) {
    val accent = colorForCategory(lesson.categoryId)
    val durMs = lesson.durationMs.takeIf { it > 0L } ?: vm.store.duration(lesson.id)
    val active = playback.mediaId == lesson.id
    val liveProgress = if (active && playback.durationMs > 0L) {
        (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
    } else if (durMs > 0L) {
        (vm.store.position(lesson.id).toFloat() / durMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        modifier = Modifier
            .width(150.dp)
            .clickable { vm.openPlayer(lesson, playlist) },
    ) {
        Box {
            Box(
                modifier = Modifier
                    .height(96.dp)
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(listOf(accent, Slate)),
                        RoundedCornerShape(16.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    iconForCategory(lesson.categoryId),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }
            if (durMs > 0L) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color(0x8A000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        formatDuration(durMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        if (showProgress && liveProgress > 0f) {
            Spacer(Modifier.height(4.dp))
            ClassicLinearProgress(
                progress = liveProgress,
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            lesson.displayTitle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

/// بطاقة قسم في «المكتبة» — تدرّج لون القسم وأيقونته واسمه.
@Composable
fun SectionCard(
    categoryId: String,
    name: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val color = colorForCategory(categoryId)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(color, Slate)))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                iconForCategory(categoryId),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
fun MiniPlayer(
    state: PlaybackUiState,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit = {},
    onDismissError: () -> Unit = {},
) {
    if (state.mediaId.isBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Column {
            // شريط خطأ ثابت فوق المشغّل: الفشل خارج شاشة المشغّل كان صامتاً تماماً.
            state.error?.let { error ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(start = 12.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        error,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
                    IconButton(onClick = onDismissError) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "إخفاء التنبيه",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (state.loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Teal,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Teal, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Headphones,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                    )
                    val progress = if (state.durationMs > 0L) {
                        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    ClassicLinearProgress(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (state.playing) Icons.Filled.PauseCircleFilled else Icons.Filled.PlayCircleFilled,
                        contentDescription = if (state.playing) "إيقاف مؤقت" else "تشغيل",
                        tint = OrangeBrand,
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = onNext, enabled = state.hasNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "التالي", tint = BlueBrand)
                }
            }
        }
    }
}

/// زر التحميل الموحّد الجذاب: دائرة برتقالية خفيفة بسهم، تتحول أثناء
/// التحميل إلى حلقة تقدّم حول النسبة، وعند الاكتمال إلى دائرة خضراء بعلامة ✓.
/// الضغط يضيف الدرس إلى طابور التحميل الخلفي (يستمر مع إغلاق التطبيق).
@Composable
fun DownloadButton(
    vm: AppViewModel,
    lesson: Lesson,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val revision by vm.store.revision.collectAsState()
    val progressMap by vm.downloads.progress.collectAsState()
    val paused by vm.downloadPaused.collectAsState()
    val downloaded = remember(revision, lesson.id) { vm.downloads.isDownloaded(lesson.id) }
    val queued = remember(revision, lesson.id) { lesson.id in vm.store.downloadQueue() }
    val active = progressMap[lesson.id]
    // قائمة التحكّم: تفتح بضغطة على المؤشّر نفسه — مكان لا يحتاج المستخدم
    // البحث عنه، وهو نفسه في كل شاشة يظهر فيها المؤشّر.
    var menu by remember { mutableStateOf(false) }

    /// قائمة «إيقاف/استئناف/إلغاء» — واحدة لكل حالات التحميل الجاري
    /// والمنتظر، فلا يختلف السلوك بين شاشة وأخرى.
    @Composable
    fun ControlMenu() {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (paused) {
                DropdownMenuItem(
                    text = { Text("استئناف التحميل") },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    onClick = {
                        menu = false
                        vm.resumeDownloads()
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("إيقاف مؤقّت") },
                    leadingIcon = { Icon(Icons.Filled.Pause, contentDescription = null) },
                    onClick = {
                        menu = false
                        vm.pauseDownloads()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("إلغاء التحميل", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menu = false
                    vm.cancelDownload(lesson.id)
                },
            )
        }
    }

    when {
        downloaded -> Box(
            modifier = Modifier.size(size).background(GreenBrand.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.DownloadDone,
                contentDescription = "تم التحميل",
                tint = GreenBrand,
                modifier = Modifier.size(size * 0.55f),
            )
        }
        // جارٍ الآن — الضغط يفتح التحكّم (إيقاف/إلغاء) بدل ألّا يفعل شيئاً.
        active != null -> Box(
            modifier = Modifier.size(size).clickable { menu = true },
            contentAlignment = Alignment.Center,
        ) {
            val percent = active.percent.coerceAtLeast(0)
            CircularProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.size(size),
                strokeWidth = 3.dp,
                color = OrangeBrand,
                trackColor = OrangeBrand.copy(alpha = 0.2f),
            )
            Text(
                "$percent",
                style = MaterialTheme.typography.labelSmall,
                color = OrangeBrand,
            )
            ControlMenu()
        }
        queued -> Box(
            modifier = Modifier
                .size(size)
                .background(OrangeBrand.copy(alpha = 0.1f), CircleShape)
                .clickable { menu = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                // موقوف ⇒ سهم استئناف صريح، وإلّا ساعة الانتظار.
                if (paused) Icons.Filled.PlayArrow else Icons.Filled.HourglassTop,
                contentDescription = if (paused) "التحميل موقوف مؤقّتاً" else "بانتظار التحميل",
                tint = OrangeBrand,
                modifier = Modifier.size(size * 0.5f),
            )
            ControlMenu()
        }
        else -> Box(
            modifier = Modifier
                .size(size)
                .background(OrangeBrand.copy(alpha = 0.14f), CircleShape)
                .clickable {
                    if (lesson.audioUrl.isBlank()) {
                        vm.showMessage("لا يمكن التحميل: لا يتوفر رابط صوتي.")
                    } else {
                        vm.downloadLessons(lesson.displayTitle, listOf(lesson))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Download,
                contentDescription = "تحميل للاستماع دون إنترنت",
                tint = OrangeBrand,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/// أزرار التحكّم في طابور التحميل: إيقاف مؤقّت/استئناف وإلغاء الكل.
///
/// مكوّن واحد مشترك بين كل شاشة تعرض تقدّم الطابور (الأقسام، القسم الفرعي،
/// «تنزيلاتي») كي لا يختلف التحكّم من موضع لآخر. الإلغاء يمرّ بتأكيد: هو
/// يحذف الملفات الجزئية بلا تراجع، بخلاف الإيقاف الذي يُبقي كلّ شيء.
@Composable
fun DownloadQueueControls(vm: AppViewModel) {
    val paused by vm.downloadPaused.collectAsState()
    var confirmCancelAll by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (paused) vm.resumeDownloads() else vm.pauseDownloads() }) {
            Icon(
                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (paused) "استئناف التحميل" else "إيقاف التحميل مؤقّتاً",
                tint = Teal,
            )
        }
        IconButton(onClick = { confirmCancelAll = true }) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "إلغاء كل التحميلات",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (confirmCancelAll) {
        AlertDialog(
            onDismissRequest = { confirmCancelAll = false },
            title = { Text("إلغاء التحميلات المتبقّية؟") },
            text = {
                Text(
                    "سيتوقّف ما يجري الآن ويخرج ما بقي من الطابور، وتُحذف " +
                        "الأجزاء المنزَّلة منها. الدروس المحمَّلة كاملةً لا تُمَسّ. " +
                        "إن أردت التوقّف مؤقّتاً فقط فاستعمل زرّ الإيقاف.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCancelAll = false
                        vm.cancelAllDownloads()
                    },
                ) {
                    Text("إلغاء الكل", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancelAll = false }) { Text("تراجع") }
            },
        )
    }
}

/// حوار تأكيد التحميل الجماعي — **إلزاميّ قبل أيّ تحميل قسم كامل**.
///
/// ضغطة واحدة كانت تُنزّل عشرات الدروس (مئات الميغابايتات) على بيانات
/// الجوّال بلا سؤال، وهي ضغطة سهلة الوقوع بالخطأ. الحوار يذكر العدد
/// **الفعليّ** المتبقّي (لا عدد دروس القسم) وتقديراً للحجم.
@Composable
fun ConfirmBulkDownloadDialog(
    label: String,
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تحميل «$label»؟") },
        text = {
            Text(
                buildString {
                    append("سيبدأ تحميل $count ")
                    append(if (count == 1) "درساً" else "درساً")
                    append(" للاستماع دون إنترنت")
                    // تقدير محافظ (≈8 ميغابايت للدرس) — الغرض تنبيه لا دقّة.
                    append("، بحجم تقريبيّ ${count * 8} ميغابايت.\n")
                    append("يستمر التحميل في الخلفية، ويمكنك إيقافه أو إلغاؤه ")
                    append("في أيّ لحظة. إن كنت على بيانات الجوّال فانتبه للاستهلاك.")
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("تحميل") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("تراجع") }
        },
    )
}

/// كبسولة «تنزيل الكل» المتدرّجة (تركواز → أخضر) — الزر البارز للتحميل الجماعي.
@Composable
fun DownloadAllButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (enabled) Brush.horizontalGradient(listOf(Teal, GreenBrand))
                else Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.DownloadForOffline,
                contentDescription = null,
                tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/// خط تقدّم كلاسيكي مستمر (بلا فجوة المقبض ولا نقطة النهاية في M3 الجديد) —
/// مطابق لشكل LinearProgressIndicator في التطبيق الأصلي.
@Composable
fun ClassicLinearProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = GreenBrand,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/// بطاقة تلميح إرشادي تظهر مرة واحدة للمستخدم الجديد، مع زر إغلاق وإجراء اختياري.
@Composable
fun HintCard(
    vm: AppViewModel,
    hintKey: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val revision by vm.store.revision.collectAsState()
    val seen = remember(revision) { vm.store.hintSeen(hintKey) }
    if (seen) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Gold, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (actionLabel != null && onAction != null) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            vm.store.markHintSeen(hintKey)
                            onAction()
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text(actionLabel) }
                }
            }
            IconButton(onClick = { vm.store.markHintSeen(hintKey) }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "فهمت",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/// شريط تنبيه «دون اتصال» — يوضّح للمستخدم أنه يرى نسخة محفوظة.
@Composable
fun OfflineBanner(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(OrangeBrand.copy(alpha = 0.14f))
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = OrangeBrand,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
    }
}

@Composable
fun EmptyState(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (detail.isNotBlank()) {
            Text(
                detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun shareLesson(
    context: android.content.Context,
    vm: AppViewModel,
    lesson: Lesson,
) {
    val state = vm.content.state.value
    val text = lessonShareText(
        lesson,
        state.categoryById[lesson.categoryId]?.name,
        state.subcategoryById[lesson.subcategoryId]?.name,
    )
    shareLessonPayload(context, vm, lesson, text)
}

/**
 * مشاركة الدرس بسلوك الأصل حرفياً: **محمّل ⇒ نشارك الملف الصوتي نفسه**،
 * وغير محمّل ⇒ نشارك النصّ والرابط. مصدر الحقيقة للتحميل هو نفسه الذي
 * يستعمله المشغّل (`store.localAudioPath`)، ووجود الملف على القرص يُتحقَّق
 * منه فعلياً قبل المشاركة.
 *
 * كل خطوة قد تفشل (مسار خارج `file_paths`، ملف مُزال، مستقبِل معطوب)
 * مُغلَّفة بـ`runCatching` مع سقوط إلى مشاركة النصّ — زرّ المشاركة لا ينهار.
 */
fun shareLessonPayload(
    context: android.content.Context,
    vm: AppViewModel,
    lesson: Lesson,
    text: String,
) {
    fun textIntent() = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }

    val fileIntent = runCatching {
        val path = vm.store.localAudioPath(lesson.id) ?: return@runCatching null
        val file = java.io.File(path)
        if (!file.isFile || file.length() <= 0L) return@runCatching null
        // الملفّ المخزَّن اسمه معرّف داخلي بامتداد قد يكون غريباً (`.ogx`
        // مثلاً، وهو Ogg لكن واتساب لا يعرفه فيعامله ملفّاً عامّاً). نضع نسخةً
        // للمشاركة باسم الدرس وامتداد قياسيّ — المستقبِل يرى اسماً مفهوماً
        // ويتعرّف على الصوت.
        val shared = shareableCopy(context, file, lesson.displayTitle) ?: file
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            shared,
        )
        Intent(Intent.ACTION_SEND).apply {
            type = audioMimeOf(shared.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, lesson.displayTitle)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrNull()

    val chooser = Intent.createChooser(fileIntent ?: textIntent(), "مشاركة الدرس")
    runCatching { context.startActivity(chooser) }.onFailure {
        runCatching {
            context.startActivity(Intent.createChooser(textIntent(), "مشاركة الدرس"))
        }
    }
}

/// امتداد قياسيّ يعرفه المستقبِلون. Firebase تعطي أحياناً `.ogx`
/// (Ogg مُتعدِّد) و`.bin` وما شابه، وواتساب لا يعدّها صوتاً.
private fun standardAudioExtension(raw: String): String =
    when (raw.lowercase()) {
        "mp3", "m4a", "aac", "wav", "flac", "amr", "opus", "ogg" -> raw.lowercase()
        "ogx", "oga", "ogv" -> "ogg"
        "m4b", "mp4" -> "m4a"
        "3gp", "3gpp" -> "3gp"
        else -> "mp3"
    }

/// نسخة للمشاركة باسم الدرس وامتداد قياسيّ داخل `cache/share`.
/// تُستبدَل في كل مشاركة فلا تتراكم، وتُنظَّف بكاش التطبيق تلقائياً.
private fun shareableCopy(
    context: android.content.Context,
    source: java.io.File,
    title: String,
): java.io.File? = runCatching {
    val extension = standardAudioExtension(source.name.substringAfterLast('.', ""))
    val safeTitle = title
        .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
        .ifBlank { "درس" }
    val directory = java.io.File(context.cacheDir, "share").apply {
        mkdirs()
        // لا نُراكم نسخاً: كل مشاركة تستبدل ما قبلها.
        listFiles()?.forEach { it.delete() }
    }
    val target = java.io.File(directory, "$safeTitle.$extension")
    source.inputStream().use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
    target.takeIf { it.length() > 0L }
}.getOrNull()

/// نوع MIME من امتداد الملف — بعض التطبيقات المستقبِلة ترفض "audio/*".
private fun audioMimeOf(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "m4b", "mp4", "aac" -> "audio/mp4"
        "ogg", "oga", "ogx", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "amr" -> "audio/amr"
        "3gp", "3gpp" -> "audio/3gpp"
        "mkv" -> "audio/x-matroska"
        else -> "audio/*"
    }
