@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ali.menbaradkshk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.PublishedWithChanges
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.data.Lesson
import com.ali.menbaradkshk.data.LessonSubmission
import com.ali.menbaradkshk.data.NotificationItem
import com.ali.menbaradkshk.data.TranscriptSubmissionItem
import com.ali.menbaradkshk.media.PlaybackUiState
import com.ali.menbaradkshk.util.formatDuration
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

// ----------------------------------------------------------------------------
// «إذاعة منبر» — 6 محطات كما في radio_screen.dart.
// ----------------------------------------------------------------------------

private data class Station(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color,
    val sleepMinutes: Int? = null,
    val build: () -> List<Lesson>,
)

@Composable
fun RadioScreen(vm: AppViewModel) {
    val content by vm.content.state.collectAsState()
    val stations = remember(content.lessons) {
        listOf(
            Station("محطتك المخصّصة", "مختارة حسب استماعك", Icons.Filled.AutoAwesome, Teal) {
                vm.content.recommended(200)
            },
            Station("جديد المنبر", "أحدث الدروس أولاً", Icons.Filled.FiberNew, BlueBrand) {
                vm.content.newest(10_000)
            },
            Station("الأكثر استماعاً", "ما يستمع إليه الناس", Icons.AutoMirrored.Filled.TrendingUp, OrangeBrand) {
                vm.content.mostListened(100)
            },
            Station("القصيرة", "دروس أقل من ١٠ دقائق", Icons.Filled.Timer, GreenBrand) {
                vm.content.shortStation()
            },
            Station("عشوائية", "اكتشف من كل الأقسام", Icons.Filled.Shuffle, Gold) {
                vm.content.randomStation()
            },
            Station("قبل النوم", "استماع هادئ مع مؤقّت ٣٠ دقيقة", Icons.Filled.Bedtime, Slate, sleepMinutes = 30) {
                vm.content.randomStation()
            },
        )
    }

    if (content.lessons.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("لا يوجد محتوى بعد.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        items(stations, key = Station::title) { station ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                ListItem(
                    modifier = Modifier.clickable {
                        val queue = station.build()
                        if (queue.isEmpty()) {
                            vm.showMessage("لا توجد دروس كافية لهذه المحطة بعد.")
                            return@clickable
                        }
                        vm.playback.setAutoplay(true)
                        station.sleepMinutes?.let(vm.playback::setSleepTimer)
                        vm.playback.play(queue.first(), queue, restart = true)
                        vm.open(Route.Lesson(queue.first().id))
                    },
                    leadingContent = {
                        Box(
                            modifier = Modifier.size(52.dp).background(station.color, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(station.icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                    },
                    headlineContent = {
                        Text(station.title, style = MaterialTheme.typography.titleMedium)
                    },
                    supportingContent = { Text(station.subtitle) },
                    trailingContent = {
                        Icon(
                            Icons.Filled.PlayCircleFilled,
                            contentDescription = "تشغيل",
                            tint = Teal,
                            modifier = Modifier.size(34.dp),
                        )
                    },
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------
// وضع القيادة — شاشة سوداء بأزرار عملاقة (car_mode_screen.dart).
// ----------------------------------------------------------------------------

@Composable
fun CarScreen(vm: AppViewModel, playback: PlaybackUiState) {
    LaunchedEffect(Unit) { vm.playback.setAutoplay(true) }
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp)) {
            IconButton(onClick = { vm.back() }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "خروج",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.DirectionsCar,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.24f),
            modifier = Modifier.size(54.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            playback.title.ifBlank { "اختر درساً للتشغيل" },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "${formatDuration(playback.positionMs).ifBlank { "0:00" }} / ${formatDuration(playback.durationMs).ifBlank { "0:00" }}",
            color = Color.White.copy(alpha = 0.54f),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm.playback::skipBackward, modifier = Modifier.size(88.dp)) {
                Icon(Icons.Filled.Replay30, "رجوع", tint = Color.White, modifier = Modifier.size(64.dp))
            }
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .background(GreenBrand, CircleShape)
                    .clickable { vm.playback.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playback.playing) "إيقاف" else "تشغيل",
                    tint = Color.White,
                    modifier = Modifier.size(90.dp),
                )
            }
            IconButton(onClick = vm.playback::skipForward, modifier = Modifier.size(88.dp)) {
                Icon(Icons.Filled.Forward30, "تقديم", tint = Color.White, modifier = Modifier.size(64.dp))
            }
        }
        Spacer(Modifier.height(30.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = vm.playback::previous, modifier = Modifier.size(80.dp)) {
                Icon(Icons.Filled.SkipPrevious, "السابق", tint = Color.White, modifier = Modifier.size(56.dp))
            }
            IconButton(onClick = vm.playback::next, modifier = Modifier.size(80.dp)) {
                Icon(Icons.Filled.SkipNext, "التالي", tint = Color.White, modifier = Modifier.size(56.dp))
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

// ----------------------------------------------------------------------------
// «حصادك» — ملخّص استماع شخصي (wrapped_screen.dart).
// ----------------------------------------------------------------------------

/// عدد اللحظات المعروضة في «حصادك» قبل السطر الملخِّص للباقي.
private const val MOMENTS_PREVIEW = 20

private fun fmtSeconds(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "$h س $m د" else "$m دقيقة"
}

@Composable
fun StatsScreen(vm: AppViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val revision by vm.store.revision.collectAsState()
    val content by vm.content.state.collectAsState()
    val total = remember(revision) { vm.store.totalSeconds() }
    val week = remember(revision) { vm.store.weekSeconds() }
    val today = remember(revision) { vm.store.todaySeconds() }
    val moments = remember(revision) { vm.store.allBookmarks() }
    val completed = remember(revision) { vm.store.completedIds().size }
    val streak = remember(revision) { vm.store.streakDays() }
    val played = remember(revision) { vm.store.playCounts().size }
    val goalMin = remember(revision) { vm.store.weeklyGoalMinutes() }
    val topCat = remember(revision, content.categories) {
        vm.store.categoryVisits().maxByOrNull { it.value }
            ?.let { content.categoryById[it.key]?.name } ?: "—"
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(Teal, Slate)), RoundedCornerShape(20.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Insights, null, tint = Color.White, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Text("حصاد استماعك", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("إجمالي ${fmtSeconds(total)} من الاستماع", color = Color.White.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(16.dp))
        val stats = listOf(
            Triple("هذا الأسبوع", fmtSeconds(week), Icons.Filled.CalendarToday to BlueBrand),
            Triple("دروس أكملتها", "$completed", Icons.Filled.Verified to GreenBrand),
            Triple("سلسلة الأيام", "$streak", Icons.Filled.LocalFireDepartment to OrangeBrand),
            Triple("دروس استمعت لها", "$played", Icons.Filled.Headphones to Teal),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            stats.take(2).forEach { (label, value, iconColor) ->
                StatTile(Modifier.weight(1f), label, value, iconColor.first, iconColor.second)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            stats.drop(2).forEach { (label, value, iconColor) ->
                StatTile(Modifier.weight(1f), label, value, iconColor.first, iconColor.second)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            ListItem(
                leadingContent = { Icon(Icons.Filled.Category, null, tint = Teal) },
                headlineContent = { Text("أكثر قسم تابعته") },
                trailingContent = { Text(topCat, fontWeight = FontWeight.Bold) },
            )
        }
        if (goalMin > 0) {
            Spacer(Modifier.height(12.dp))
            val done = (week / 60).toInt()
            val ratio = (done.toFloat() / goalMin).coerceIn(0f, 1f)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Flag, null, tint = OrangeBrand)
                        Spacer(Modifier.width(8.dp))
                        Text("هدفك الأسبوعي: $goalMin دقيقة", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    ClassicLinearProgress(
                        progress = ratio,
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = if (ratio >= 1f) GreenBrand else OrangeBrand,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (ratio >= 1f) "أحسنت! بلغت هدفك 🎉 ($done/$goalMin د)" else "$done من $goalMin دقيقة",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // بطاقة «اليوم»: ما استمعتَه اليوم وتقدّمه نحو حصّة يوميّة = الهدف ÷ ٧.
        Spacer(Modifier.height(12.dp))
        val todayMinutes = (today / 60).toInt()
        val dailyGoal = if (goalMin > 0) (goalMin / 7).coerceAtLeast(1) else 0
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Today, null, tint = Teal)
                    Spacer(Modifier.width(8.dp))
                    Text("اليوم: ${fmtSeconds(today)}", fontWeight = FontWeight.Bold)
                }
                if (dailyGoal > 0) {
                    val todayRatio = (todayMinutes.toFloat() / dailyGoal).coerceIn(0f, 1f)
                    Spacer(Modifier.height(10.dp))
                    ClassicLinearProgress(
                        progress = todayRatio,
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = if (todayRatio >= 1f) GreenBrand else Teal,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (todayRatio >= 1f) {
                            "أتممت حصّة اليوم 🎉 ($todayMinutes/$dailyGoal د)"
                        } else {
                            "$todayMinutes من $dailyGoal دقيقة اليوم"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "حدّد هدفاً أسبوعياً من الإعدادات ليظهر تقدّمك اليومي هنا.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // «لحظاتك المحفوظة»: كانت اللحظات مدفونة داخل كل درس على حدة —
        // هنا تُجمَع كلها، والنقر يفتح الدرس من موضع اللحظة تماماً.
        if (moments.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bookmark, null, tint = Gold)
                Spacer(Modifier.width(8.dp))
                Text("لحظاتك المحفوظة", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            moments.take(MOMENTS_PREVIEW).forEach { moment ->
                val lesson = content.lessonById[moment.lessonId]
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    ListItem(
                        modifier = Modifier.clickable {
                            if (lesson == null) {
                                vm.showMessage("الدرس لم يعد متاحاً — حدّث المحتوى.")
                            } else {
                                vm.playback.play(
                                    lesson,
                                    listOf(lesson) + vm.content.similarTo(lesson),
                                    moment.positionMs,
                                    restart = true,
                                )
                                vm.open(Route.Lesson(lesson.id))
                            }
                        },
                        leadingContent = {
                            Icon(Icons.Filled.PlayCircleOutline, null, tint = Teal)
                        },
                        headlineContent = {
                            Text(
                                lesson?.displayTitle ?: "درس لم يعد متاحاً",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                formatDuration(moment.positionMs) +
                                    moment.note.takeIf(String::isNotBlank)
                                        ?.let { " — $it" }.orEmpty(),
                            )
                        },
                    )
                }
            }
            if (moments.size > MOMENTS_PREVIEW) {
                Text(
                    "و${moments.size - MOMENTS_PREVIEW} لحظة أخرى محفوظة داخل الدروس.",
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        FilledTonalButton(
            onClick = {
                context.startActivity(
                    android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                android.content.Intent.EXTRA_TEXT,
                                "📿 حصاد استماعي في «منبر ادكصهك»:\n" +
                                    "• إجمالي: ${fmtSeconds(total)}\n" +
                                    "• هذا الأسبوع: ${fmtSeconds(week)}\n" +
                                    "• دروس أكملتها: $completed\n" +
                                    "• سلسلة استماع: $streak يوماً\n" +
                                    "انضمّ إليّ في الاستماع 🎧",
                            )
                        },
                        "مشاركة",
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Share, null)
            Text(" شارك حصادك")
        }
    }
}

@Composable
private fun StatTile(
    modifier: Modifier,
    label: String,
    value: String,
    icon: ImageVector,
    color: Color,
) {
    Column(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(value, color = color, style = MaterialTheme.typography.titleLarge)
        Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
}

// ----------------------------------------------------------------------------
// الإشعارات — بثّ حي بثلاثة مصادر + سحب للحذف (notifications_screen.dart).
// ----------------------------------------------------------------------------

private fun agoText(ms: Long): String {
    if (ms <= 0L) return ""
    val diff = System.currentTimeMillis() - ms
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "الآن"
        minutes < 60 -> "قبل $minutes د"
        minutes < 24 * 60 -> "قبل ${minutes / 60} س"
        else -> "قبل ${minutes / (24 * 60)} ي"
    }
}

private fun notificationIcon(type: String): ImageVector = when (type) {
    "lesson" -> Icons.Filled.Audiotrack
    "category" -> Icons.Filled.Folder
    "subcategory" -> Icons.Filled.FolderOpen
    "book" -> Icons.Filled.MenuBook
    "submission" -> Icons.Filled.HowToVote
    "transcript" -> Icons.Filled.MenuBook
    else -> Icons.Filled.Campaign
}

/// نافذة عرض الإشعارات: آخر 30 يوماً فقط — يمنع تحوّل الشاشة إلى سجل
/// لا متناهٍ من الإشعارات القديمة (طلب المستخدم 2026-07-23).
private const val NOTIFICATIONS_WINDOW_MS = 30L * 24 * 60 * 60 * 1_000

/// الفلتر المعتمد للإشعارات المعروضة — تستعمله الشاشة وشارة الجرس معاً
/// كي لا يعدّ العدّاد ما لا يظهر فعلاً.
fun visibleNotifications(vm: AppViewModel, all: List<NotificationItem>): List<NotificationItem> {
    val dismissed = vm.store.dismissedNotificationIds().toSet()
    val cutoff = System.currentTimeMillis() - NOTIFICATIONS_WINDOW_MS
    return all.filterNot { it.id in dismissed }
        .filter { it.createdAtMs >= cutoff }
}

/// عدد الإشعارات الحديثة التي أخفاها المستخدم بنفسه («مسح الكل» أو الحذف
/// الفردي). تفرّق الشاشةُ بها بين «لم يصلك شيء» و«أنت مسحتَها» — بلا هذا
/// التمييز يبدو التطبيق وكأنه لا يستقبل الإشعارات أصلاً.
fun dismissedRecentCount(vm: AppViewModel, all: List<NotificationItem>): Int {
    val dismissed = vm.store.dismissedNotificationIds().toSet()
    val cutoff = System.currentTimeMillis() - NOTIFICATIONS_WINDOW_MS
    return all.count { it.id in dismissed && it.createdAtMs >= cutoff }
}

@Composable
fun NotificationsScreen(vm: AppViewModel) {
    val revision by vm.store.revision.collectAsState()
    val all by vm.notifications.collectAsState()
    val content by vm.content.state.collectAsState()
    val items = remember(all, revision) { visibleNotifications(vm, all) }
    // ختم «آخر ما رآه» قبل تصفير الشارة — يميّز الجديد بصرياً داخل الشاشة.
    val seenBefore by vm.notificationsSeenBefore.collectAsState()

    fun openTarget(n: NotificationItem) {
        when (n.type) {
            "submission", "transcript" -> vm.open(Route.MySubmissions)
            // الكتب أُزيلت من التطبيق عمداً — نوضّح ذلك بدل فتح شاشة خاطئة.
            "book" -> vm.showMessage("الكتب لم تعد ضمن التطبيق — المحتوى صوتيّ فقط.")
            // إشعار عام يدويّ بلا هدف: لا وجهة له أصلاً (صفّه غير قابل للنقر).
            "manual" -> Unit
            "lesson" -> {
                val lesson = content.lessonById[n.refId]
                if (lesson != null) {
                    val playlist = content.lessons.filter { it.subcategoryId == lesson.subcategoryId }
                    vm.openPlayer(lesson, playlist.ifEmpty { listOf(lesson) })
                } else {
                    vm.showMessage("العنصر غير متاح بعد — حدّث المحتوى.")
                }
            }
            "subcategory" -> {
                if (content.subcategoryById.containsKey(n.refId)) vm.open(Route.Subcategory(n.refId))
                else vm.showMessage("العنصر غير متاح بعد — حدّث المحتوى.")
            }
            "category" -> {
                if (content.categoryById.containsKey(n.refId)) vm.open(Route.Category(n.refId))
                else vm.showMessage("العنصر غير متاح بعد — حدّث المحتوى.")
            }
            else -> vm.showMessage("العنصر غير متاح بعد — حدّث المحتوى.")
        }
    }

    if (items.isEmpty()) {
        val hidden = remember(all, revision) { dismissedRecentCount(vm, all) }
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (hidden > 0) "مسحتَ إشعاراتك — لم يُفقد شيء." else "لا توجد إشعارات بعد.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hidden > 0) {
                    Text(
                        "الإشعارات الجديدة ستصلك هنا كالمعتاد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { vm.store.clearDismissedNotifications() }) {
                        Text("استعادة المُستبعَدة ($hidden)")
                    }
                }
            }
        }
        return
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { vm.store.dismissNotifications(items.map(NotificationItem::id)) }) {
                    Icon(Icons.Filled.ClearAll, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" مسح الكل")
                }
            }
        }
        items(items, key = NotificationItem::id) { n ->
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled) {
                        vm.store.dismissNotification(n.id)
                        true
                    } else {
                        false
                    }
                },
            )
            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(horizontal = 20.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "حذف", tint = Color.White)
                    }
                },
            ) {
                // الإشعار اليدوي العام بلا وجهة — لا يُعرض كصفّ قابل للنقر
                // كي لا ينتهي كل ضغط برسالة «العنصر غير متاح».
                val hasTarget = n.type != "manual"
                val isNew = n.createdAtMs > seenBefore
                Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                    ListItem(
                        modifier = if (hasTarget) {
                            Modifier.clickable { openTarget(n) }
                        } else {
                            Modifier
                        },
                        leadingContent = {
                            Box(
                                modifier = Modifier.size(40.dp).background(Teal, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(notificationIcon(n.type), null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        },
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isNew) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .background(OrangeBrand, CircleShape),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    n.title.ifBlank { "إشعار" },
                                    fontWeight = if (isNew) FontWeight.ExtraBold else FontWeight.SemiBold,
                                )
                            }
                        },
                        supportingContent = n.body.takeIf(String::isNotBlank)?.let { { Text(it) } },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    agoText(n.createdAtMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                IconButton(onClick = { vm.store.dismissNotification(n.id) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "حذف",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    )
                    androidx.compose.material3.HorizontalDivider()
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// «مساهماتي» — my_submissions_screen.dart.
// ----------------------------------------------------------------------------

@Composable
fun MySubmissionsScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val flow = remember { vm.submissions.mine().catch { emit(emptyList()) } }
    val submissions by flow.collectAsState(initial = null)
    // اقتراحات «النص المشروح» تُعرض بجانب الدروس الصوتية في نفس الشاشة.
    val transcriptsFlow = remember { vm.transcripts.mine().catch { emit(emptyList()) } }
    val transcriptSubmissions by transcriptsFlow.collectAsState(initial = emptyList())
    var withdrawTarget by remember { mutableStateOf<LessonSubmission?>(null) }
    var withdrawTranscript by remember { mutableStateOf<TranscriptSubmissionItem?>(null) }

    when {
        submissions == null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        submissions.orEmpty().isEmpty() && transcriptSubmissions.isEmpty() -> {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.HistoryEdu,
                        null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "لم ترسل أي مساهمة بعد.\nمن «شارك درساً» يمكنك اقتراح دروس صوتية، ومن شاشة التشغيل يمكنك المساهمة بالنص المشروح — تُنشر بعد موافقة المشرفين.",
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        else -> {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                items(transcriptSubmissions, key = { "t_${it.id}" }) { item ->
                    TranscriptSubmissionCard(item) { withdrawTranscript = item }
                }
                items(submissions.orEmpty(), key = LessonSubmission::id) { submission ->
                    val (color, icon, label) = when (submission.status) {
                        "approved" -> Triple(GreenBrand, Icons.Filled.CheckCircleOutline, "نُشرت كما هي")
                        "approved_edited" -> Triple(Teal, Icons.Filled.PublishedWithChanges, "نُشرت بعد تعديل")
                        "rejected" -> Triple(MaterialTheme.colorScheme.error, Icons.Filled.Cancel, "لم تُنشر")
                        else -> Triple(OrangeBrand, Icons.Filled.HourglassTop, "قيد المراجعة")
                    }
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    submission.title,
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.Bold,
                                )
                                Box(
                                    Modifier
                                        .background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${submission.categoryName} ← ${submission.subcategoryName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (submission.status == "rejected" && submission.rejectReason.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "السبب: ${submission.rejectReason}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (submission.status == "pending") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { withdrawTarget = submission }) {
                                        Icon(Icons.Filled.Undo, null, modifier = Modifier.size(18.dp))
                                        Text(" سحب الطلب")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    withdrawTarget?.let { submission ->
        AlertDialog(
            onDismissRequest = { withdrawTarget = null },
            title = { Text("سحب المساهمة؟") },
            text = { Text("سيُحذف الطلب قبل أن يراجعه المشرفون.") },
            confirmButton = {
                TextButton(onClick = {
                    withdrawTarget = null
                    scope.launch {
                        runCatching { vm.submissions.deletePending(submission) }
                            .onFailure { vm.showMessage("تعذّر حذف الطلب.") }
                    }
                }) { Text("سحب") }
            },
            dismissButton = {
                TextButton(onClick = { withdrawTarget = null }) { Text("إلغاء") }
            },
        )
    }

    withdrawTranscript?.let { item ->
        AlertDialog(
            onDismissRequest = { withdrawTranscript = null },
            title = { Text("سحب اقتراح النص؟") },
            text = { Text("سيُحذف الاقتراح قبل أن يراجعه المشرفون.") },
            confirmButton = {
                TextButton(onClick = {
                    withdrawTranscript = null
                    scope.launch {
                        runCatching { vm.transcripts.deletePending(item) }
                            .onFailure { vm.showMessage("تعذّر حذف الاقتراح.") }
                    }
                }) { Text("سحب") }
            },
            dismissButton = {
                TextButton(onClick = { withdrawTranscript = null }) { Text("إلغاء") }
            },
        )
    }
}

/// بطاقة اقتراح «نص مشروح» في «مساهماتي» — شارة 📖 تميّزها عن الدروس الصوتية.
@Composable
private fun TranscriptSubmissionCard(
    item: TranscriptSubmissionItem,
    onWithdraw: () -> Unit,
) {
    val (color, icon, label) = when (item.status) {
        "approved" -> Triple(GreenBrand, Icons.Filled.CheckCircleOutline, "اعتُمد النص")
        "approved_edited" -> Triple(Teal, Icons.Filled.PublishedWithChanges, "اعتُمد بعد تعديل")
        "rejected" -> Triple(MaterialTheme.colorScheme.error, Icons.Filled.Cancel, "لم يُعتمد")
        else -> Triple(OrangeBrand, Icons.Filled.HourglassTop, "قيد المراجعة")
    }
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    item.lessonTitle.ifBlank { "نص مشروح" },
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                )
                Box(
                    Modifier
                        .background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.MenuBook,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "نص مشروح" + if (item.hasImages) " + صور صفحات" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.status == "rejected" && item.rejectReason.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "السبب: ${item.rejectReason}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (item.isPending) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onWithdraw) {
                        Icon(Icons.Filled.Undo, null, modifier = Modifier.size(18.dp))
                        Text(" سحب الاقتراح")
                    }
                }
            }
        }
    }
}
