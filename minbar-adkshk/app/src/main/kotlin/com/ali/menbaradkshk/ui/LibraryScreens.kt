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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.data.Playlist
import com.ali.menbaradkshk.media.PlaybackUiState
import com.ali.menbaradkshk.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// صفحة «قوائمي»: تجمع «السجل» و«قوائم التشغيل» بتبويبين — السجل هو
/// الافتراضي الذي يظهر أولاً (طلب المستخدم 2026-07-23).
@Composable
fun MyListsScreen(vm: AppViewModel, playback: PlaybackUiState) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                icon = { Icon(Icons.Filled.History, contentDescription = null) },
                text = { Text("السجل") },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                text = { Text("قوائم التشغيل") },
            )
        }
        when (tab) {
            0 -> HistoryTab(vm, playback)
            else -> PlaylistsTab(vm)
        }
    }
}

@Composable
private fun PlaylistsTab(vm: AppViewModel) {
    val revision by vm.store.revision.collectAsState()
    val playlists = remember(revision) { vm.store.playlists() }
    var createDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var renameText by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        if (playlists.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "لا توجد قوائم بعد.\nأنشئ قائمة وأضِف إليها دروسك المفضّلة.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp)) {
                items(playlists, key = Playlist::id) { playlist ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                        ListItem(
                            modifier = Modifier.clickable { vm.open(Route.Playlist(playlist.id)) },
                            leadingContent = {
                                Box(
                                    modifier = Modifier.size(40.dp).background(Teal, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Color.White)
                                }
                            },
                            headlineContent = { Text(playlist.name) },
                            supportingContent = { Text("${playlist.lessonIds.size} صوتية") },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = {
                                        renameTarget = playlist
                                        renameText = playlist.name
                                    }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "إعادة تسمية", tint = Teal)
                                    }
                                    IconButton(onClick = { deleteTarget = playlist }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { newName = ""; createDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("قائمة جديدة") },
        )
    }

    if (createDialog) {
        AlertDialog(
            onDismissRequest = { createDialog = false },
            title = { Text("قائمة جديدة") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("اسم القائمة") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isNotEmpty()) vm.store.createPlaylist(name)
                    createDialog = false
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { createDialog = false }) { Text("إلغاء") }
            },
        )
    }
    renameTarget?.let { playlist ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("إعادة تسمية القائمة") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    placeholder = { Text("اسم القائمة") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameText.trim()
                    if (name.isNotEmpty()) vm.store.renamePlaylist(playlist.id, name)
                    renameTarget = null
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("إلغاء") }
            },
        )
    }
    deleteTarget?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("حذف القائمة") },
            text = { Text("حذف \"${playlist.name}\"؟ (لن تُحذف الدروس نفسها)") },
            confirmButton = {
                TextButton(onClick = {
                    vm.store.deletePlaylist(playlist.id)
                    deleteTarget = null
                }) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("إلغاء") }
            },
        )
    }
}

/// رأس زمنيّ للسجل بحسب طابع آخر استماع.
private fun historyBucket(stampMs: Long, todayStartMs: Long): String = when {
    stampMs >= todayStartMs -> "اليوم"
    stampMs >= todayStartMs - DAY_MS -> "أمس"
    stampMs >= todayStartMs - 6 * DAY_MS -> "هذا الأسبوع"
    else -> "أقدم"
}

private const val DAY_MS = 24L * 60 * 60 * 1_000

@Composable
private fun HistoryTab(vm: AppViewModel, playback: PlaybackUiState) {
    val revision by vm.store.revision.collectAsState()
    val content by vm.content.state.collectAsState()
    // الإخفاء محليّ ولا يرفع `revision`، فيُحرّك إعادةَ التركيب عدّادٌ خاص.
    var hiddenTick by remember { mutableIntStateOf(0) }
    val items = remember(revision, content.lessons, hiddenTick) { vm.content.historyLessons() }
    val stamps = remember(revision, hiddenTick) { vm.content.historyStamps() }
    val completed = remember(revision) { vm.store.completedIds().toSet() }
    val positions = remember(revision) { vm.store.positions() }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("لا يوجد سجل استماع بعد.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    val todayStart = remember(items) {
        java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
    // التجميع يحفظ ترتيب السجل نفسه (الأحدث أوّلاً) لأن groupBy يبقي الإدخال.
    val sections = remember(items, stamps, todayStart) {
        items.groupBy { historyBucket(stamps[it.id] ?: 0L, todayStart) }.toList()
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        sections.forEach { (label, lessons) ->
            item(key = "history-header-$label") {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 2.dp),
                )
            }
            items(lessons, key = { "${it.id}-$revision" }) { lesson ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value != SwipeToDismissBoxValue.Settled) {
                            vm.content.hideFromHistory(lesson.id)
                            hiddenTick += 1
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
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.error)
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "إزالة من السجل", tint = Color.White)
                        }
                    },
                ) {
                    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                        AudioItem(vm, lesson, items, playback)
                        val savedMs = positions[lesson.id] ?: 0L
                        when {
                            lesson.id in completed -> HistoryBadge(
                                icon = Icons.Filled.DoneAll,
                                text = "أتممته ✓",
                                tint = GreenBrand,
                            )

                            savedMs > 3_000L -> HistoryBadge(
                                icon = Icons.Filled.History,
                                text = "توقّفت عند ${formatDuration(savedMs)}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color) {
    Row(
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.size(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/// تفاصيل قائمة تشغيل — نمط PlaylistDetailScreen الأصلي، مع صفّ التشغيل
/// (تشغيل الكل/عشوائي/فرز) وإعادة ترتيب يدويّة بالأسهم.
@Composable
fun PlaylistDetailScreen(vm: AppViewModel, playlistId: String) {
    val revision by vm.store.revision.collectAsState()
    val content by vm.content.state.collectAsState()
    val playlist = remember(revision) { vm.store.playlists().firstOrNull { it.id == playlistId } }
    // الترتيب اليدوي محفوظ في المستودع ولا يرفع `revision`، فيحرّكه عدّاد خاص.
    var orderTick by remember { mutableIntStateOf(0) }
    // 0 = ترتيبي (يدوي)، 1 = الأحدث، 2 = الأقدم.
    var sortMode by rememberSaveable(playlistId) { mutableIntStateOf(0) }
    val stored = remember(revision, content.lessons, orderTick) {
        vm.content.orderedPlaylist(playlistId, playlist?.lessonIds.orEmpty().mapNotNull(content.lessonById::get))
    }
    val lessons = remember(stored, sortMode) {
        when (sortMode) {
            1 -> stored.sortedByDescending(com.ali.menbaradkshk.data.Lesson::createdAtMs)
            2 -> stored.sortedBy(com.ali.menbaradkshk.data.Lesson::createdAtMs)
            else -> stored
        }
    }
    // المدد تُقرأ مرّة واحدة لا مرّة لكل صفّ (كل قراءة تحليل JSON كامل).
    val durations = remember(revision) { vm.store.durations() }
    if (playlist == null) {
        EmptyState("القائمة غير موجودة", "")
        return
    }
    if (lessons.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "القائمة فارغة.\nأضِف دروساً من زر «إضافة لقائمة» في المشغّل.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item(key = "playlist-actions") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = { vm.openPlayer(lessons.first(), lessons) },
                    leadingIcon = {
                        Icon(Icons.Filled.PlayCircleFilled, null, tint = Teal, modifier = Modifier.size(20.dp))
                    },
                    label = { Text("تشغيل الكل") },
                )
                AssistChip(
                    onClick = {
                        val shuffled = lessons.shuffled()
                        vm.openPlayer(shuffled.first(), shuffled)
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Shuffle, null, tint = BlueBrand, modifier = Modifier.size(20.dp))
                    },
                    label = { Text("عشوائي") },
                )
                Spacer(Modifier.weight(1f))
                AssistChip(
                    onClick = { sortMode = (sortMode + 1) % 3 },
                    leadingIcon = {
                        Icon(Icons.Filled.SwapVert, null, tint = OrangeBrand, modifier = Modifier.size(20.dp))
                    },
                    label = {
                        Text(
                            when (sortMode) {
                                1 -> "الأحدث"
                                2 -> "الأقدم"
                                else -> "ترتيبي"
                            },
                        )
                    },
                )
            }
        }
        itemsIndexed(lessons, key = { _, lesson -> "${lesson.id}-$revision" }) { index, lesson ->
            val duration = lesson.durationMs.takeIf { it > 0L } ?: (durations[lesson.id] ?: 0L)
            Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                ListItem(
                    modifier = Modifier.clickable { vm.openPlayer(lesson, lessons) },
                    leadingContent = {
                        Box(
                            modifier = Modifier.size(40.dp).background(Teal, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Color.White)
                        }
                    },
                    headlineContent = { Text(lesson.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = duration.takeIf { it > 0L }?.let { { Text(formatDuration(it)) } },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // الأسهم مع الترتيب اليدوي وحده كي لا يتضارب مع الفرز.
                            if (sortMode == 0) {
                                IconButton(
                                    onClick = {
                                        vm.content.movePlaylistItem(
                                            playlistId,
                                            lessons.map { it.id },
                                            index,
                                            index - 1,
                                        )
                                        orderTick += 1
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowUp,
                                        contentDescription = "تحريك للأعلى",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        vm.content.movePlaylistItem(
                                            playlistId,
                                            lessons.map { it.id },
                                            index,
                                            index + 1,
                                        )
                                        orderTick += 1
                                    },
                                    enabled = index < lessons.lastIndex,
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowDown,
                                        contentDescription = "تحريك للأسفل",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { vm.store.removeFromPlaylist(playlist.id, lesson.id) }) {
                                Icon(
                                    Icons.Filled.RemoveCircleOutline,
                                    contentDescription = "إزالة من القائمة",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

/// «تنزيلاتي» المطوّرة: ملخّص إحصائي، تقدّم التحميل الجاري، تحميل قسم كامل
/// (رئيسي أو فرعي)، تجميع الدروس حسب أقسامها، وتلميح التنزيل التلقائي.
@Composable
fun DownloadsScreen(vm: AppViewModel) {
    val revision by vm.store.revision.collectAsState()
    val content by vm.content.state.collectAsState()
    val bulk by vm.bulkDownload.collectAsState()
    val downloadsMap = remember(revision) { vm.store.downloads() }
    val items = remember(revision, content.lessons) {
        downloadsMap.keys.mapNotNull(content.lessonById::get)
    }
    // مسح أحجام الملفات على خيط الإدخال/الإخراج لا داخل التركيب — مفتاحه خريطة
    // التنزيلات فلا يتكرّر مع نبضات `revision` أثناء التشغيل.
    val totalBytes by produceState(0L, downloadsMap) {
        value = withContext(Dispatchers.IO) {
            downloadsMap.values.sumOf { path -> java.io.File(path).length().coerceAtLeast(0L) }
        }
    }
    // مدد الدروس تُقرأ مرّة واحدة لكل تركيب بدل تحليل JSON كامل لكل صفّ.
    val durations = remember(revision) { vm.store.durations() }
    // تجميع التنزيلات حسب القسم الفرعي (الأحدث تحميلاً يبقى ترتيبه داخل قسمه).
    val grouped = remember(items, content.subcategories) {
        items.groupBy { it.subcategoryId }
            .map { (subId, lessons) ->
                (content.subcategoryById[subId]?.name ?: "دروس أخرى") to lessons
            }
            .sortedBy { it.first }
    }
    var deleteTarget by remember { mutableStateOf<com.ali.menbaradkshk.data.Lesson?>(null) }
    var deleteAllDialog by remember { mutableStateOf(false) }
    var sectionSheet by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        // تلميح التنزيل التلقائي.
        item {
            HintCard(
                vm = vm,
                hintKey = "auto_download",
                icon = Icons.Filled.DownloadDone,
                text = "يمكن للتطبيق حفظ أحدث الدروس تلقائياً للاستماع دون إنترنت.",
                actionLabel = "تفعيل من الإعدادات",
                onAction = vm::openSettings,
            )
        }

        // ملخّص إحصائي + أزرار الإجراءات.
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(44.dp).background(GreenBrand, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.DownloadDone, null, tint = Color.White)
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${items.size} درساً محفوظاً",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "المساحة المستخدمة: ${formatBytes(totalBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        DownloadAllButton(
                            text = "تحميل قسم كامل",
                            enabled = bulk == null,
                            onClick = { sectionSheet = true },
                        )
                        if (items.isNotEmpty()) {
                            TextButton(onClick = { deleteAllDialog = true }) {
                                Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                Text(" حذف الكل", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        // تقدّم التحميل الجاري.
        bulk?.let { state ->
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    state.paused ->
                                        "التحميل موقوف مؤقّتاً — «${state.label}» يُستأنف من حيث توقّف"
                                    state.waitingForNetwork ->
                                        "بانتظار عودة الاتصال لاستئناف «${state.label}»…"
                                    else ->
                                        "جارٍ تحميل «${state.label}» — ${state.done}/${state.total}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            DownloadQueueControls(vm)
                        }
                        if (state.currentTitle.isNotBlank() && !state.waitingForNetwork) {
                            Text(
                                state.currentTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // عدّاد البايتات: `done/total` وحدها تعني «الدروس
                        // المكتملة»، فتبقى صفراً طوال تحميل درس واحد ويبدو
                        // الشريط متجمّداً. نعرض نسبة الملفّ الجاري وحجمه.
                        if (!state.waitingForNetwork && state.filePercent >= 0) {
                            Text(
                                buildString {
                                    append("${state.filePercent}%")
                                    if (state.fileTotalBytes > 0) {
                                        append(" — ${formatBytes(state.fileDownloadedBytes)}")
                                        append(" من ${formatBytes(state.fileTotalBytes)}")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                        ClassicLinearProgress(
                            progress = state.overallFraction,
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                    }
                }
            }
        }

        if (items.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp, start = 24.dp, end = 24.dp)) {
                    Text(
                        "لا توجد دروس منزّلة بعد.\nحمّل دروساً للاستماع دون إنترنت.",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        } else {
            grouped.forEach { (sectionName, lessons) ->
                item(key = "header-$sectionName") {
                    Text(
                        sectionName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
                items(lessons, key = { "${it.id}-$revision" }) { lesson ->
                    val duration = lesson.durationMs.takeIf { it > 0L } ?: (durations[lesson.id] ?: 0L)
                    Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                        ListItem(
                            modifier = Modifier.clickable { vm.openPlayer(lesson, items) },
                            leadingContent = {
                                Box(
                                    modifier = Modifier.size(40.dp).background(GreenBrand, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Filled.DownloadDone, null, tint = Color.White)
                                }
                            },
                            headlineContent = { Text(lesson.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = duration.takeIf { it > 0L }?.let { { Text(formatDuration(it)) } },
                            trailingContent = {
                                IconButton(onClick = { deleteTarget = lesson }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // ورقة «تحميل قسم كامل»: قسم رئيسي بكامله أو قسم فرعي بعينه.
    if (sectionSheet) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { sectionSheet = false }) {
            Text(
                "تحميل قسم كامل",
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                content.categories.forEach { category ->
                    val categoryLessons = content.lessons.filter { it.categoryId == category.id }
                    item(key = "cat-${category.id}") {
                        ListItem(
                            modifier = Modifier.clickable {
                                sectionSheet = false
                                vm.requestBulkDownload(category.name, categoryLessons)
                            },
                            leadingContent = {
                                Icon(iconForCategory(category.id), null, tint = colorForCategory(category.id))
                            },
                            headlineContent = { Text(category.name, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = { Text("القسم كاملاً — ${categoryLessons.size} درساً") },
                            trailingContent = {
                                Icon(Icons.Filled.DownloadDone, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                        )
                    }
                    val subs = content.subcategories.filter { it.categoryId == category.id }
                    items(subs, key = { "sub-${it.id}" }) { sub ->
                        val subLessons = content.lessons.filter { it.subcategoryId == sub.id }
                        ListItem(
                            modifier = Modifier
                                .padding(start = 24.dp)
                                .clickable {
                                    sectionSheet = false
                                    vm.requestBulkDownload(sub.name, subLessons)
                                },
                            headlineContent = { Text(sub.name) },
                            supportingContent = { Text("${subLessons.size} درساً") },
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { lesson ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("حذف التنزيل") },
            text = { Text("حذف \"${lesson.displayTitle}\" من جهازك؟") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { vm.downloads.delete(lesson.id) }
                    deleteTarget = null
                }) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("إلغاء") }
            },
        )
    }
    if (deleteAllDialog) {
        AlertDialog(
            onDismissRequest = { deleteAllDialog = false },
            title = { Text("حذف كل التنزيلات") },
            text = { Text("سيُحذف ${items.size} درساً محفوظاً (${formatBytes(totalBytes)}) من جهازك. الدروس نفسها تبقى متاحة بالبثّ.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteAllDialog = false
                    scope.launch { vm.downloads.deleteAll() }
                }) { Text("حذف الكل") }
            },
            dismissButton = {
                TextButton(onClick = { deleteAllDialog = false }) { Text("إلغاء") }
            },
        )
    }
}

/// «المفضّلة» — نمط favorites_screen.dart.
@Composable
fun FavoritesScreen(vm: AppViewModel, playback: PlaybackUiState) {
    val revision by vm.store.revision.collectAsState()
    val content by vm.content.state.collectAsState()
    val items = remember(revision, content.lessons) { vm.content.favorites() }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("اضغط ♥ على أي درس لحفظه هنا.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(items, key = { "${it.id}-$revision" }) { lesson ->
            AudioItem(vm, lesson, items, playback)
        }
    }
}
