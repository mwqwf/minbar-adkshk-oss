@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ali.menbaradkshk.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/// «الإعدادات» — ورقة منسدلة بنمط نبراس (ContentOptionsSheet) حرفياً:
/// ارتفاع ثابت محسوب صراحةً (92% من الشاشة) + LazyColumn بـweight(1f)
/// ومفاتيح بنود ثابتة وnavigationBarsPadding — البنية التي لا تتذبذب مع
/// التمرير العنيف. الخيارات المعقدة تُفتح في حوارات ومنتقيات فرعية.
@Composable
fun SettingsSheet(vm: AppViewModel, requestNotifications: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val revision by vm.store.revision.collectAsState()
    val content by vm.content.state.collectAsState()

    val streak = remember(revision) { vm.store.streakDays() }
    val notifOn = remember(revision) { vm.store.notificationsEnabled() }
    val themeMode = remember(revision) { vm.store.themeMode() }
    val fontScale = remember(revision) { vm.store.fontScale() }
    val autoDownload = remember(revision) { vm.store.autoDownloadEnabled() }
    val autoTarget = remember(revision) { vm.store.autoDownloadTarget() ?: "recent" }
    val wifiOnly = remember(revision) { vm.store.autoDownloadWifiOnly() }
    val continueReminder = remember(revision) { vm.store.continueReminderEnabled() }
    val wardEnabled = remember(revision) { vm.store.wardEnabled() }
    val wardHour = remember(revision) { vm.store.wardHour() }
    val wardMinute = remember(revision) { vm.store.wardMinute() }
    val weeklyGoal = remember(revision) { vm.store.weeklyGoalMinutes() }
    val downloadsMap = remember(revision) { vm.store.downloads() }
    val downloadsCount = downloadsMap.size
    // حجم التنزيلات = مسح للقرص: يُحسب على خيط الإدخال/الإخراج ومفتاحه خريطة
    // التنزيلات نفسها، فلا يتكرّر مع نبضات `revision` أثناء التشغيل.
    val downloadsBytes by produceState(0L, downloadsMap) {
        value = withContext(Dispatchers.IO) {
            downloadsMap.values.sumOf { java.io.File(it).length().coerceAtLeast(0L) }
        }
    }
    val favorites = remember(revision, content.lessons) { vm.content.favorites() }
    val continueList = remember(revision, content.lessons) { vm.content.continueListening() }

    var themeDialog by remember { mutableStateOf(false) }
    var fontDialog by remember { mutableStateOf(false) }
    var wardTimeDialog by remember { mutableStateOf(false) }
    var goalSheet by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var sectionSheet by remember { mutableStateOf(false) }

    // نسخة احتياطية محلية عبر منتقي ملفات النظام (بلا أي رفع للسحابة).
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(vm.store.exportBackup().toByteArray())
                }
            }.onSuccess { vm.showMessage("حُفظت نسخة بياناتك.") }
                .onFailure { vm.showMessage("تعذّر حفظ النسخة.") }
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }.orEmpty()
                vm.store.importBackup(text)
            }.onSuccess { restored ->
                if (restored < 0) vm.showMessage("الملف ليس نسخة احتياطية صالحة للتطبيق.")
                else {
                    vm.content.refreshPersonalization()
                    vm.showMessage("استُعيدت بياناتك ($restored عنصراً).")
                }
            }.onFailure { vm.showMessage("تعذّر قراءة الملف.") }
        }
    }

    fun wardTimeLabel(): String {
        if (wardHour < 0) return "—"
        val hour12 = when {
            wardHour == 0 -> 12
            wardHour > 12 -> wardHour - 12
            else -> wardHour
        }
        val period = if (wardHour < 12) "ص" else "م"
        return "%d:%02d %s".format(Locale.ROOT, hour12, wardMinute, period)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // بنية نبراس (ContentOptionsSheet): ارتفاع الورقة ثابت محسوب صراحةً من
    // ارتفاع الشاشة، لا fillMaxHeight بنسبة. النسبة تُقرأ من قيود الورقة
    // *المتغيّرة أثناء حركتها*، فكانت تمريرة سريعة جداً تُدخل القياس والحركة
    // في حلقة تغذية راجعة (تذبذب رسم لا يتوقّف). الارتفاع الثابت يفصلهما،
    // وweight(1f) على القائمة يمنحها قيوداً مقيَّدة مستقرّة داخل العمود.
    val sheetHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    ModalBottomSheet(onDismissRequest = vm::closeSettings, sheetState = sheetState) {
        Column(Modifier.height(sheetHeight).fillMaxWidth()) {
            Text(
                "الإعدادات",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
            )
            if (streak > 0) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    AssistChip(
                        onClick = {},
                        leadingIcon = { Icon(Icons.Filled.LocalFireDepartment, null, tint = OrangeBrand) },
                        label = { Text("سلسلة استماع: $streak ${if (streak == 1) "يوم" else "أيام"}") },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // مصدرا طاقة التذبذب عند التمريرة العنيفة، ويُغلقان معاً:
            // (1) سرعة الـfling المتبقّية بعد بلوغ القائمة حدّها كانت تُسلَّم
            //     إلى نابض استقرار الورقة فيهتزّ عند مرساه بلا توقّف — نبتلع
            //     الهابط منها فقط؛ السحب البطيء (onPostScroll) يمرّ كما هو،
            //     فيبقى الإغلاق بالسحب من المقبض أو من رأس القائمة يعمل.
            // (2) نابض تمدّد الحواف (overscroll) نفسه قد يعلق في اهتزاز
            //     دون-بكسلي يحرق الإطارات بلا حركة مرئية — نعطّله داخل
            //     الورقة (لا أثر وظيفي؛ مجرد لمعة الحافّة).
            val flingGuard = remember {
                object : NestedScrollConnection {
                    override suspend fun onPostFling(
                        consumed: Velocity,
                        available: Velocity,
                    ): Velocity = if (available.y > 0f) available else Velocity.Zero
                }
            }
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .navigationBarsPadding()
                        .nestedScroll(flingGuard),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        bottom = 32.dp,
                    ),
                ) {
                item(key = "theme") {
                    SettingsTile(
                        icon = Icons.Filled.BrightnessMedium,
                        title = "المظهر",
                        trailing = {
                            Text(
                                when (themeMode) {
                                    "light" -> "فاتح"
                                    "dark" -> "داكن"
                                    else -> "النظام"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = { themeDialog = true },
                    )
                }
                item(key = "fontsize") {
                    SettingsTile(
                        icon = Icons.Filled.TextFields,
                        title = "حجم النص",
                        trailing = {
                            Text(
                                "${(fontScale * 100).toInt()}%",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = { fontDialog = true },
                    )
                }

                item(key = "dl-header") { SectionHeader("التنزيلات") }
                item(key = "autodl") {
                    SettingsTile(
                        icon = Icons.Filled.DownloadForOffline,
                        title = "التنزيل التلقائي",
                        subtitle = "يحفظ أحدث الدروس للاستماع دون إنترنت",
                        trailing = {
                            Switch(
                                checked = autoDownload,
                                onCheckedChange = { enabled ->
                                    vm.setAutoDownloadEnabled(enabled)
                                    if (enabled) vm.setAutoDownloadTarget("recent")
                                },
                            )
                        },
                    )
                }
                if (autoDownload) {
                    item(key = "autodl-target") {
                        SettingsTile(
                            icon = Icons.Filled.DownloadDone,
                            title = "ما الذي يُنزّل تلقائياً؟",
                            trailing = {
                                Text(
                                    if (autoTarget == "main") "خلاصتك المقترحة" else "أحدث الدروس",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = {
                                vm.setAutoDownloadTarget(if (autoTarget == "main") "recent" else "main")
                            },
                        )
                    }
                    item(key = "autodl-wifi") {
                        SettingsTile(
                            icon = Icons.Filled.Wifi,
                            title = "عبر Wi‑Fi فقط",
                            trailing = {
                                Switch(checked = wifiOnly, onCheckedChange = vm::setAutoDownloadWifiOnly)
                            },
                        )
                    }
                }
                item(key = "dl-favs") {
                    SettingsTile(
                        icon = Icons.Filled.Favorite,
                        title = "تحميل المفضّلة كلها",
                        subtitle = "${favorites.size} درساً",
                        onClick = {
                            if (favorites.isEmpty()) vm.showMessage("لا توجد دروس في المفضّلة بعد.")
                            else vm.requestBulkDownload("المفضّلة", favorites)
                        },
                    )
                }
                item(key = "dl-continue") {
                    SettingsTile(
                        icon = Icons.Filled.Headphones,
                        title = "تحميل دروس «تابع الاستماع»",
                        subtitle = "${continueList.size} درساً لم تكمله",
                        onClick = {
                            if (continueList.isEmpty()) vm.showMessage("لا توجد دروس غير مكتملة.")
                            else vm.requestBulkDownload("تابع الاستماع", continueList)
                        },
                    )
                }
                item(key = "dl-section") {
                    SettingsTile(
                        icon = Icons.Filled.LibraryAdd,
                        title = "تحميل قسم كامل",
                        subtitle = "قسم رئيسي بكل فروعه أو قسم فرعي بعينه",
                        onClick = { sectionSheet = true },
                    )
                }
                item(key = "dl-manage") {
                    SettingsTile(
                        icon = Icons.Filled.DownloadDone,
                        title = "إدارة التنزيلات",
                        subtitle = "$downloadsCount درساً — ${formatStorage(downloadsBytes)}",
                        onClick = {
                            vm.closeSettings()
                            vm.openRoot(Route.Downloads)
                        },
                    )
                }

                item(key = "notif-header") { SectionHeader("الإشعارات") }
                item(key = "notif") {
                    SettingsTile(
                        icon = if (notifOn) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsOff,
                        title = "الإشعارات",
                        subtitle = if (notifOn) "تصلك إشعارات المحتوى الجديد" else "الإشعارات موقوفة",
                        trailing = {
                            Switch(
                                checked = notifOn,
                                onCheckedChange = { enabled ->
                                    if (enabled) requestNotifications()
                                    vm.setNotificationsEnabled(enabled)
                                },
                            )
                        },
                    )
                }
                if (notifOn) {
                    item(key = "notif-reminder") {
                        SettingsTile(
                            icon = Icons.Filled.HistoryToggleOff,
                            title = "تذكير «تابع الاستماع»",
                            subtitle = "إشعار محلي بلطف بدرس لم تكمله",
                            trailing = {
                                Switch(checked = continueReminder, onCheckedChange = vm::setContinueReminderEnabled)
                            },
                        )
                    }
                }
                item(key = "ward") {
                    SettingsTile(
                        icon = Icons.Filled.WbSunny,
                        title = "الوِرد اليومي",
                        subtitle = if (wardEnabled) "درس اليوم في ${wardTimeLabel()}"
                        else "درس مقترح كل يوم في وقت تختاره",
                        trailing = {
                            Switch(
                                checked = wardEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) wardTimeDialog = true else vm.disableWard()
                                },
                            )
                        },
                    )
                }
                if (wardEnabled) {
                    item(key = "ward-time") {
                        SettingsTile(
                            icon = Icons.Filled.Schedule,
                            title = "وقت الوِرد",
                            trailing = {
                                Text(wardTimeLabel(), style = MaterialTheme.typography.titleMedium)
                            },
                            onClick = { wardTimeDialog = true },
                        )
                    }
                }

                item(key = "other-header") { SectionHeader("أخرى") }
                item(key = "goal") {
                    SettingsTile(
                        icon = Icons.Filled.Flag,
                        title = "الهدف الأسبوعي للاستماع",
                        subtitle = if (weeklyGoal > 0) "$weeklyGoal دقيقة أسبوعياً" else "غير محدَّد",
                        onClick = { goalSheet = true },
                    )
                }
                item(key = "backup") {
                    SettingsTile(
                        icon = Icons.Filled.Save,
                        title = "حفظ نسخة من بياناتي",
                        subtitle = "المفضّلة والقوائم والسجل والتقدّم — ملف على جهازك",
                        onClick = { exportLauncher.launch("minbar-backup.json") },
                    )
                }
                item(key = "restore") {
                    SettingsTile(
                        icon = Icons.Filled.Restore,
                        title = "استعادة نسخة",
                        subtitle = "استيراد ملف نسخة احتياطية سابق",
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    )
                }
                item(key = "privacy") {
                    SettingsTile(
                        icon = Icons.Filled.PrivacyTip,
                        title = "سياسة الخصوصية",
                        subtitle = "تفتح مباشرة في موقع منبر",
                        trailing = { Icon(Icons.Filled.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://minbar-adkassahk.vercel.app/privacy")),
                                )
                            }.onFailure { vm.showMessage("تعذّر فتح سياسة الخصوصية.") }
                        },
                    )
                }
                item(key = "submissions") {
                    SettingsTile(
                        icon = Icons.Filled.FactCheck,
                        title = "مساهماتي",
                        subtitle = "تابع قرار المشرفين وسبب النتيجة",
                        onClick = {
                            vm.closeSettings()
                            vm.open(Route.MySubmissions)
                        },
                    )
                }
                item(key = "delete") {
                    SettingsTile(
                        icon = Icons.Filled.DeleteForever,
                        title = "حذف بياناتي",
                        subtitle = "حذف المساهمات والبيانات الشخصية نهائياً",
                        iconTint = MaterialTheme.colorScheme.error,
                        onClick = { deleteDialog = true },
                    )
                }
                item(key = "footer") {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "منبر ادكصهك — دروس صوتية",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                }
            }
        }
    }

    // ---- منتقي المظهر ----
    if (themeDialog) {
        AlertDialog(
            onDismissRequest = { themeDialog = false },
            title = { Text("المظهر") },
            text = {
                Column {
                    listOf("light" to "فاتح", "dark" to "داكن", "system" to "اتّباع النظام").forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                vm.store.setThemeMode(value)
                                themeDialog = false
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = themeMode == value,
                                onClick = {
                                    vm.store.setThemeMode(value)
                                    themeDialog = false
                                },
                            )
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { themeDialog = false }) { Text("إغلاق") }
            },
        )
    }

    // ---- حجم النص ----
    if (fontDialog) {
        var value by remember { mutableFloatStateOf(fontScale.coerceIn(0.8f, 1.4f)) }
        AlertDialog(
            onDismissRequest = { fontDialog = false },
            title = { Text("حجم النص") },
            text = {
                Column {
                    Text("${(value * 100).toInt()}%", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Slider(
                        value = value,
                        onValueChange = { value = it },
                        valueRange = 0.8f..1.4f,
                        steps = 5,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.store.setFontScale(value)
                    fontDialog = false
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { fontDialog = false }) { Text("إلغاء") }
            },
        )
    }

    // ---- ورقة «تحميل قسم كامل» ----
    if (sectionSheet) {
        ModalBottomSheet(onDismissRequest = { sectionSheet = false }) {
            Text(
                "تحميل قسم كامل",
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
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
                        )
                    }
                    items(
                        content.subcategories.filter { it.categoryId == category.id },
                        key = { "sub-${it.id}" },
                    ) { sub ->
                        val subLessons = content.lessons.filter { it.subcategoryId == sub.id }
                        ListItem(
                            modifier = Modifier.padding(start = 24.dp).clickable {
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

    // ---- وقت الوِرد ----
    if (wardTimeDialog) {
        val timeState = rememberTimePickerState(
            initialHour = if (wardEnabled && wardHour >= 0) wardHour else 6,
            initialMinute = if (wardEnabled && wardHour >= 0) wardMinute else 0,
        )
        AlertDialog(
            onDismissRequest = { wardTimeDialog = false },
            title = { Text("وقت الوِرد اليومي") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    vm.setWardTime(timeState.hour, timeState.minute)
                    wardTimeDialog = false
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { wardTimeDialog = false }) { Text("إلغاء") }
            },
        )
    }

    // ---- الهدف الأسبوعي ----
    if (goalSheet) {
        ModalBottomSheet(onDismissRequest = { goalSheet = false }) {
            Text(
                "الهدف الأسبوعي",
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            listOf(0, 30, 60, 120, 180, 300).forEach { minutes ->
                ListItem(
                    modifier = Modifier.clickable {
                        vm.store.setWeeklyGoalMinutes(minutes)
                        goalSheet = false
                    },
                    headlineContent = {
                        Text(if (minutes == 0) "بلا هدف" else "$minutes دقيقة أسبوعياً")
                    },
                    trailingContent = if (weeklyGoal == minutes) {
                        { Icon(Icons.Filled.Check, null, tint = Teal) }
                    } else {
                        null
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- حذف بياناتي ----
    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("حذف بياناتي") },
            text = {
                Text(
                    "سيُحذف سجل مساهماتك وملفاتها من السحابة، ثم تُمسح المفضلة والقوائم والسجل والتنزيلات من هذا الجهاز. لا يمكن التراجع.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteDialog = false
                    scope.launch {
                        runCatching { vm.deleteMyData() }
                            .onFailure { vm.showMessage("تعذّر حذف البيانات. تحقق من الاتصال وحاول مجدداً.") }
                    }
                }) { Text("حذف نهائي") }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialog = false }) { Text("إلغاء") }
            },
        )
    }
}

private fun formatStorage(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

@Composable
private fun SectionHeader(title: String) {
    Column {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            title,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            color = Teal,
        )
    }
}

/// بند إعدادات بنمط SettingsTile في نبراس: أيقونة + عنوان (+وصف) + عنصر جانبي.
@Composable
private fun SettingsTile(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: androidx.compose.ui.graphics.Color = Teal,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp)) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = trailing,
    )
}
