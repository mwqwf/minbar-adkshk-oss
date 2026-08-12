@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ali.menbaradkshk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MicExternalOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.data.Category
import com.ali.menbaradkshk.data.ContentState
import com.ali.menbaradkshk.data.Lesson
import com.ali.menbaradkshk.data.Subcategory
import com.ali.menbaradkshk.media.PlaybackUiState
import com.ali.menbaradkshk.util.normalizeArabic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ----------------------------------------------------------------------------
// الرئيسية
// ----------------------------------------------------------------------------

@Composable
fun HomeScreen(
    vm: AppViewModel,
    state: ContentState,
    playback: PlaybackUiState,
) {
    val revision by vm.store.revision.collectAsState()

    // الريلات تُحسب هنا (سياق قابل للتركيب) ثم تُعرض داخل القائمة — نمط الأصل
    // حيث ContentRepository يعيد حساب الريلات ويُخطر الشاشات.
    val ward = remember(revision, state.lessons) { vm.content.dailyWard() }
    val rails = remember(revision, state.lessons) {
        listOf(
            Triple("مختارات المنبر ⭐", vm.content.featured(), false),
            Triple("تابع الاستماع", vm.content.continueListening(), true),
            Triple("لم تُكمله بعد", vm.content.unfinished(), true),
            Triple("الأكثر استماعاً هذا الأسبوع 🔥", vm.content.trending(), false),
            Triple("الأكثر استماعاً", vm.content.mostListened(), false),
            Triple("الأحدث", vm.content.newest(), false),
            Triple("استكمل قسمك", vm.content.continueSection(), false),
            Triple("قسم اليوم", vm.content.randomSectionToday(), false),
        )
    }
    // بلا أي سجل تكون «مقترح لك» نسخة طبق الأصل من «الأحدث» (ثالث قائمة
    // متطابقة للوافد الجديد)، فتُستبدل بـ«ابدأ من هنا»: محطّة الدروس القصيرة.
    val hasHistory = remember(revision, state.lessons) { vm.content.hasHistory() }
    val feed = remember(revision, state.lessons, hasHistory) {
        if (hasHistory) vm.content.recommended() else vm.content.shortStation()
    }
    val feedTitle = if (hasHistory) "مقترح لك" else "ابدأ من هنا"

    // لا يتكرّر درس واحد بين الريلات: أوّل ريل يظهر فيه يحتفظ به، وما بعده
    // يسقطه — كي لا يرى المستخدم القائمة نفسها ثلاث مرات.
    val deduped = remember(rails, feed) {
        val seen = mutableSetOf<String>()
        val uniqueRails = rails.map { (title, lessons, showProgress) ->
            Triple(title, lessons.filter { seen.add(it.id) }, showProgress)
        }
        uniqueRails to feed.filter { it.id !in seen }
    }
    val visibleRails = deduped.first
    val visibleFeed = deduped.second

    PullToRefreshBox(
        isRefreshing = state.syncing,
        onRefresh = { vm.content.requestDeepRefresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (state.offline && state.error != null) {
                item { OfflineBanner(state.error) { vm.content.requestDeepRefresh() } }
            }

            // إجراءات سريعة: إذاعة منبر، وضع القيادة، حصادك.
            item { QuickActions(vm) }

            // تلميحات إرشادية للمستخدم الجديد (تظهر مرة واحدة).
            item {
                HintCard(
                    vm = vm,
                    hintKey = "library_tab",
                    icon = Icons.Filled.Folder,
                    text = "كل الأقسام والسلاسل تجدها في تبويب «المكتبة» أسفل الشاشة.",
                    actionLabel = "فتح المكتبة",
                    onAction = { vm.openRoot(Route.Library) },
                )
            }
            item {
                HintCard(
                    vm = vm,
                    hintKey = "contribute_fab",
                    icon = Icons.Filled.MicExternalOn,
                    text = "لديك تسجيل مفيد؟ شاركه من زر «شارك درساً» وسيُنشر بعد موافقة المشرفين.",
                )
            }

            if (ward != null) {
                item { DailyWardCard(vm, ward, feed) }
            }

            visibleRails.forEach { (title, lessons, showProgress) ->
                railItem(vm, title, lessons, playback, showProgress = showProgress)
            }

            if (visibleFeed.isNotEmpty()) {
                item { RailHeader(feedTitle) }
                items(visibleFeed, key = { "feed-${it.id}-$revision" }) { lesson ->
                    AudioItem(vm, lesson, visibleFeed, playback, showActions = false)
                }
            }

            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.lessons.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 120.dp, start = 24.dp, end = 24.dp)) {
                        Text(
                            "يجب الاتصال بالإنترنت أول مرة لتحميل المحتوى. بعد ذلك يمكنك استخدام التطبيق دون إنترنت.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.railItem(
    vm: AppViewModel,
    title: String,
    lessons: List<Lesson>,
    playback: PlaybackUiState,
    showProgress: Boolean = false,
) {
    if (lessons.isEmpty()) return
    item(key = "rail-$title") {
        Column(horizontalAlignment = Alignment.Start) {
            RailHeader(title)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                items(lessons, key = { "$title-${it.id}" }) { lesson ->
                    AudioCard(vm, lesson, lessons, playback, showProgress = showProgress)
                }
            }
        }
    }
}

/// حالة «القائمة فارغة» مفرّقة إلى ثلاث: أثناء التحميل مؤشّر دوّار، وعند انقطاع
/// الاتصال رسالة الاتصال مع «إعادة المحاولة»، وغير ذلك رسالة الفراغ الصحيحة —
/// كي لا يُنسب الفراغ للإنترنت وهو محمّل أصلاً أو ما زال قيد التحميل.
@Composable
private fun EmptyOrLoadingState(
    loading: Boolean,
    offline: Boolean,
    offlineMessage: String,
    emptyMessage: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        when {
            loading -> CircularProgressIndicator()
            offline -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    offlineMessage,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
            }

            else -> Text(
                emptyMessage,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun RailHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun QuickActions(vm: AppViewModel) {
    LazyRow(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { QuickChip(Icons.Filled.Radio, "إذاعة منبر", Teal) { vm.open(Route.Radio) } }
        item { QuickChip(Icons.Filled.DirectionsCar, "وضع القيادة", BlueBrand) { vm.open(Route.Car) } }
        item { QuickChip(Icons.Filled.Insights, "حصادك", OrangeBrand) { vm.open(Route.Stats) } }
    }
}

@Composable
private fun QuickChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) },
    )
}

@Composable
private fun DailyWardCard(vm: AppViewModel, ward: Lesson, feed: List<Lesson>) {
    Box(
        modifier = Modifier
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp)
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(listOf(Teal, Slate)),
                RoundedCornerShape(12.dp),
            )
            .clickable { vm.openPlayer(ward, listOf(ward) + feed) }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.WbSunny, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("وِرد اليوم", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    ward.displayTitle,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Icon(Icons.Filled.PlayCircleFilled, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}

// ----------------------------------------------------------------------------
// المكتبة (الأقسام) — التغيير الوحيد المُبقى: الأقسام صفحة مستقلة عن الرئيسية.
// ----------------------------------------------------------------------------

@Composable
fun LibraryScreen(vm: AppViewModel, state: ContentState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.categories, key = Category::id) { category ->
            val count = state.subcategories.count { it.categoryId == category.id }
            SectionCard(
                categoryId = category.id,
                name = category.name,
                subtitle = if (count > 0) "$count أقسام فرعية" else null,
                onClick = { vm.open(Route.Category(category.id)) },
            )
        }
        if (!state.loading && state.categories.isEmpty()) {
            item { EmptyState("لا توجد أقسام", "اسحب للتحديث أو تحقق من الاتصال.") }
        }
    }
}

// ----------------------------------------------------------------------------
// شاشة الأقسام الفرعية (SubcategoriesScreen في الأصل)
// ----------------------------------------------------------------------------

@Composable
fun CategoryScreen(vm: AppViewModel, categoryId: String, state: ContentState) {
    val revision by vm.store.revision.collectAsState()
    val subs = remember(revision, state.subcategories) { vm.content.subcategoriesForCategory(categoryId) }
    var certificateFor by remember { mutableStateOf<Subcategory?>(null) }

    if (subs.isEmpty()) {
        EmptyOrLoadingState(
            loading = state.loading,
            // رسالة الاتصال لا تصحّ إلا حين لا نسخة محفوظة أصلاً؛ ومع وجود نسخة
            // يكون الفراغ فراغ قسم لا انقطاع شبكة.
            offline = state.offline && state.subcategories.isEmpty(),
            offlineMessage = "يجب الاتصال بالإنترنت أول مرة لتحميل الأقسام. بعد ذلك يمكنك التصفّح دون إنترنت.",
            emptyMessage = "لا توجد أقسام فرعية في هذا القسم.",
            onRetry = { vm.content.requestDeepRefresh() },
        )
        return
    }

    val accent = colorForCategory(categoryId)
    val bulk by vm.bulkDownload.collectAsState()
    LazyColumn(
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HintCard(
                vm = vm,
                hintKey = "follow_subcategory",
                icon = Icons.Filled.NotificationsActive,
                text = "اضغط جرس أي قسم فرعي لمتابعته — يصلك إشعار بكل درس جديد فيه.",
            )
        }
        // تحميل القسم الرئيسي كاملاً (بكل أقسامه الفرعية) دفعة واحدة.
        item {
            val categoryLessons = remember(revision, state.lessons) {
                vm.content.lessonsForCategory(categoryId)
            }
            val active = bulk != null
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (active) {
                    Text("${bulk?.done}/${bulk?.total}", style = MaterialTheme.typography.bodyMedium)
                    // موقوف ⇒ لا دوّار: الدوّار المتحرّك بلا تقدّم يوحي بعمل جارٍ.
                    if (bulk?.paused == true) {
                        Icon(
                            Icons.Filled.PauseCircleFilled,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        bulk?.label.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    DownloadQueueControls(vm)
                } else {
                    DownloadAllButton(
                        text = "تحميل القسم كاملاً (${categoryLessons.size} درساً)",
                        enabled = categoryLessons.isNotEmpty(),
                        onClick = {
                            val name = state.categoryById[categoryId]?.name ?: "القسم"
                            vm.requestBulkDownload(name, categoryLessons)
                        },
                    )
                }
            }
        }
        items(subs, key = { "${it.id}-$revision" }) { sub ->
            val (done, total) = vm.content.seriesProgress(sub.id)
            val complete = total > 0 && done >= total
            val ratio = if (total > 0) done.toFloat() / total else 0f
            val following = vm.store.isFollowingSubcategory(sub.id)
            Box(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(accent, Slate)), RoundedCornerShape(12.dp)),
            ) {
                Column {
                    ListItem(
                        modifier = Modifier.clickable { vm.open(Route.Subcategory(sub.id)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Icon(
                                iconForCategory(categoryId),
                                contentDescription = null,
                                tint = if (complete) PositiveOnDark else GoldOnDark,
                            )
                        },
                        headlineContent = {
                            Text(sub.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        },
                        supportingContent = if (total > 0) {
                            {
                                Text(
                                    if (complete) "أتممت السلسلة ✓" else "$done من $total",
                                    color = Color.White.copy(alpha = 0.75f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            null
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    vm.toggleFollow(sub.id)
                                    vm.showMessage(
                                        if (following) "أُلغيت متابعة «${sub.name}»"
                                        else "ستصلك إشعارات دروس «${sub.name}» الجديدة",
                                    )
                                }) {
                                    Icon(
                                        if (following) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone,
                                        contentDescription = if (following) "إلغاء متابعة القسم" else "متابعة القسم",
                                        tint = Color.White,
                                    )
                                }
                                if (complete) {
                                    IconButton(onClick = { certificateFor = sub }) {
                                        Icon(
                                            Icons.Filled.WorkspacePremium,
                                            contentDescription = "شهادة الإتمام",
                                            tint = GoldOnDark,
                                        )
                                    }
                                }
                            }
                        },
                    )
                    if (total > 0) {
                        ClassicLinearProgress(
                            progress = ratio,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                                .height(6.dp),
                            color = if (complete) PositiveOnDark else GoldOnDark,
                            trackColor = Color.White.copy(alpha = 0.24f),
                        )
                    }
                }
            }
        }
    }

    certificateFor?.let { sub ->
        CompletionCertificateDialog(
            vm = vm,
            subcategory = sub,
            onDismiss = { certificateFor = null },
        )
    }
}

@Composable
private fun CompletionCertificateDialog(vm: AppViewModel, subcategory: Subcategory, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = GoldOnDark, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text("شهادة إتمام", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "أتممت الاستماع إلى سلسلة\n«${subcategory.name}»",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(6.dp))
                Text("نسأل الله لك العلم النافع 🌿", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                context.startActivity(
                    android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                android.content.Intent.EXTRA_TEXT,
                                // سطر التطبيق يجعل الشهادة دعوةً لمن يقرأها.
                                "أتممتُ سلسلة «${subcategory.name}» في تطبيق «منبر ادكصهك» 🎓\n" +
                                    "استمع إليه في تطبيق منبر ادكصهك — " +
                                    "https://play.google.com/store/apps/details?id=com.ali.menbaradkshk",
                            )
                        },
                        "مشاركة",
                    ),
                )
            }) { Text("مشاركة") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        },
    )
}

// ----------------------------------------------------------------------------
// شاشة دروس قسم فرعي (LessonsScreen في الأصل)
// ----------------------------------------------------------------------------

@Composable
fun LessonsScreen(vm: AppViewModel, subcategoryId: String, playback: PlaybackUiState) {
    val revision by vm.store.revision.collectAsState()
    val content by vm.content.state.collectAsState()
    val sub = content.subcategoryById[subcategoryId]
    val lessons = remember(revision, content.lessons) { vm.content.lessonsForSubcategory(subcategoryId) }
    val bulk by vm.bulkDownload.collectAsState()
    var newestFirst by remember { mutableStateOf(false) }
    val ordered = remember(lessons, newestFirst) { if (newestFirst) lessons.reversed() else lessons }
    val downloadedCount = remember(revision, lessons) { lessons.count { vm.downloads.isDownloaded(it.id) } }

    LazyColumn(contentPadding = PaddingValues(vertical = 10.dp)) {
        item {
            HintCard(
                vm = vm,
                hintKey = "bulk_download",
                icon = Icons.Filled.DownloadForOffline,
                text = "يمكنك تنزيل كل دروس هذا القسم دفعة واحدة للاستماع دون إنترنت — من الزر أدناه.",
            )
        }
        // تشغيل القسم كاملاً، تشغيل عشوائي، وفرز الدروس.
        if (ordered.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = { vm.openPlayer(ordered.first(), ordered) },
                        leadingIcon = { Icon(Icons.Filled.PlayCircleFilled, null, tint = Teal, modifier = Modifier.size(20.dp)) },
                        label = { Text("تشغيل الكل") },
                    )
                    AssistChip(
                        onClick = {
                            val shuffled = ordered.shuffled()
                            vm.openPlayer(shuffled.first(), shuffled)
                        },
                        leadingIcon = { Icon(Icons.Filled.Shuffle, null, tint = BlueBrand, modifier = Modifier.size(20.dp)) },
                        label = { Text("عشوائي") },
                    )
                    Spacer(Modifier.weight(1f))
                    AssistChip(
                        onClick = { newestFirst = !newestFirst },
                        leadingIcon = { Icon(Icons.Filled.SwapVert, null, tint = OrangeBrand, modifier = Modifier.size(20.dp)) },
                        label = { Text(if (newestFirst) "الأحدث" else "الأقدم") },
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val active = bulk != null
                if (active) {
                    Text("${bulk?.done}/${bulk?.total}", style = MaterialTheme.typography.bodyMedium)
                    if (bulk?.paused == true) {
                        Icon(
                            Icons.Filled.PauseCircleFilled,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        bulk?.label.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    DownloadQueueControls(vm)
                } else {
                    DownloadAllButton(
                        text = if (downloadedCount > 0) "تنزيل الكل ($downloadedCount/${lessons.size} محمّل)"
                        else "تنزيل كل دروس القسم",
                        enabled = lessons.isNotEmpty(),
                        onClick = { vm.requestBulkDownload(sub?.name ?: "القسم", lessons) },
                    )
                    Spacer(Modifier.weight(1f))
                }
                IconButton(onClick = {
                    sub?.categoryId?.takeIf { it.isNotBlank() }?.let { vm.open(Route.Category(it)) }
                }) {
                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "القسم الرئيسي")
                }
            }
        }
        if (lessons.isEmpty()) {
            item {
                EmptyOrLoadingState(
                    loading = content.loading,
                    // مع وجود دروس محفوظة يكون القسم فارغاً فعلاً لا الاتصال منقطعاً.
                    offline = content.offline && content.lessons.isEmpty(),
                    offlineMessage = "يجب الاتصال بالإنترنت أول مرة لتحميل الدروس. بعد ذلك يمكنك الاستماع دون إنترنت.",
                    emptyMessage = "لا توجد دروس في هذا القسم.",
                    onRetry = { vm.content.requestDeepRefresh() },
                )
            }
        } else {
            items(ordered, key = { "${it.id}-$revision" }) { lesson ->
                AudioItem(vm, lesson, ordered, playback)
            }
        }
    }
}

// ----------------------------------------------------------------------------
// قائمة دروس عامة (المفضّلة/السجل/القوائم/التنزيلات/نتائج البحث)
// ----------------------------------------------------------------------------

@Composable
fun LessonListScreen(
    vm: AppViewModel,
    lessons: List<Lesson>,
    playback: PlaybackUiState,
    emptyTitle: String,
    emptyDetail: String = "لم تُضف عناصر هنا بعد.",
) {
    val revision by vm.store.revision.collectAsState()
    if (lessons.isEmpty()) {
        EmptyState(emptyTitle, emptyDetail)
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(lessons, key = { "${it.id}-$revision" }) { lesson ->
            AudioItem(vm, lesson, lessons, playback)
        }
    }
}

// ----------------------------------------------------------------------------
// البحث — يطابق ContentSearchDelegate الأصلي حرفياً (أقسام + فرعية + دروس)
// ----------------------------------------------------------------------------

@Composable
fun SearchScreen(vm: AppViewModel, initial: String, playback: PlaybackUiState) {
    var query by rememberSaveable(initial) { mutableStateOf(initial) }
    val content by vm.content.state.collectAsState()
    val revision by vm.store.revision.collectAsState()
    val q = query.trim()

    // حفظ الاستعلام في السجل بعد تأخير (400ms) إن كان طوله ≥ 2 — كما في الأصل.
    LaunchedEffect(q) {
        if (q.length >= 2) {
            delay(400)
            vm.store.addSearchQuery(q)
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            label = { Text("ابحث في الدروس والأقسام...") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "مسح")
                    }
                }
            },
            keyboardOptions = KeyboardOptions.Default,
        )

        if (q.isEmpty()) {
            val history = remember(revision) { vm.store.searchHistory() }
            if (history.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                    Text("ابحث في العناوين، الأقسام، وأسماء الشيوخ", modifier = Modifier.padding(top = 24.dp))
                }
            } else {
                LazyColumn {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("عمليات بحث سابقة", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { vm.store.clearSearchHistory() }) { Text("مسح") }
                        }
                    }
                    items(history, key = { it }) { entry ->
                        ListItem(
                            modifier = Modifier.clickable { query = entry },
                            headlineContent = { Text(entry) },
                            leadingContent = { Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                    }
                }
            }
        } else {
            val categories = content.categories
            val subcategories = content.subcategories
            // بحث بالكلمات لا بالجملة الحرفية: **كل كلمة** في الاستعلام يجب أن
            // ترد في العنوان أو المتحدّث أو اسم القسم — فـ«ابن باز الصيام» تجد
            // درساً عنوانه «الصيام» لمتحدّث «ابن باز» ولو اختلف الترتيب.
            val words = remember(q) {
                normalizeArabic(q).split(' ', '\n', '\t').filter(String::isNotBlank)
            }
            val catRes = remember(words, categories) {
                categories.filter { category ->
                    val hay = normalizeArabic(category.name)
                    words.all { hay.contains(it) }
                }
            }
            val subRes = remember(words, subcategories) {
                subcategories.filter { sub ->
                    val hay = normalizeArabic(sub.name)
                    words.all { hay.contains(it) }
                }
            }
            // فهرس مطبَّع يُبنى مرّة لكل تغيّر محتوى لا مع كل ضغطة مفتاح:
            // كان normalizeArabic يمرّ على كل الدروس عند كل حرف يُكتب.
            val lessonIndex = remember(content.lessons, categories, subcategories) {
                val categoryNames = categories.associate { it.id to it.name }
                val subcategoryNames = subcategories.associate { it.id to it.name }
                content.lessons.map { lesson ->
                    lesson to normalizeArabic(
                        buildString {
                            append(lesson.displayTitle)
                            append(' ')
                            append(lesson.speaker)
                            append(' ')
                            append(categoryNames[lesson.categoryId].orEmpty())
                            append(' ')
                            append(subcategoryNames[lesson.subcategoryId].orEmpty())
                        },
                    )
                }
            }
            val lesRes = remember(words, lessonIndex) {
                lessonIndex.asSequence()
                    .filter { (_, hay) -> words.all { word -> hay.contains(word) } }
                    .map { it.first }
                    .take(80)
                    .toList()
            }

            if (catRes.isEmpty() && subRes.isEmpty() && lesRes.isEmpty()) {
                // بدل شاشة فارغة: اقتراح «الأكثر استماعاً» ليبقى للمستخدم مخرج.
                val fallback = remember(revision, content.lessons) { vm.content.mostListened() }
                LazyColumn {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("لا توجد نتائج لـ«$q»", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "جرّب كلمة أقصر أو اسم المتحدّث.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    if (fallback.isNotEmpty()) {
                        item {
                            Text(
                                "الأكثر استماعاً",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 4.dp,
                                    bottom = 8.dp,
                                ),
                            )
                        }
                        items(fallback, key = { "top-${it.id}" }) { lesson ->
                            AudioItem(vm, lesson, fallback, playback, showActions = false)
                        }
                    }
                }
            } else {
                LazyColumn {
                    items(catRes, key = { "cat-${it.id}" }) { c ->
                        ListItem(
                            modifier = Modifier.clickable { vm.open(Route.Category(c.id)) },
                            leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null, tint = Teal) },
                            headlineContent = { Text(c.name) },
                        )
                    }
                    items(subRes, key = { "sub-${it.id}" }) { s ->
                        ListItem(
                            modifier = Modifier.clickable { vm.open(Route.Subcategory(s.id)) },
                            leadingContent = { Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = BlueBrand) },
                            headlineContent = { Text(s.name) },
                        )
                    }
                    items(lesRes, key = { "les-${it.id}" }) { l ->
                        ListItem(
                            modifier = Modifier.clickable { vm.openPlayer(l, lesRes) },
                            leadingContent = { Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Gold) },
                            headlineContent = { Text(l.displayTitle) },
                            supportingContent = if (l.speaker.isNotBlank()) {
                                { Text(l.speaker) }
                            } else {
                                null
                            },
                        )
                    }
                    if (lesRes.size >= 80) {
                        item {
                            Text(
                                "عرض أول 80 نتيجة — حدّد البحث أكثر",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
