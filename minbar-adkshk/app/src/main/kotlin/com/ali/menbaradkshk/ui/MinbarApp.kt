@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ali.menbaradkshk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MicExternalOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.data.AppConfigRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.catch

private data class RootTab(
    val route: Route,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val rootTabs = listOf(
    RootTab(Route.Home, "الرئيسية", Icons.Outlined.Home, Icons.Filled.Home),
    RootTab(Route.Library, "المكتبة", Icons.AutoMirrored.Outlined.LibraryBooks, Icons.AutoMirrored.Filled.LibraryBooks),
    RootTab(Route.MyLists, "قوائمي", Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
    RootTab(Route.Downloads, "تنزيلاتي", Icons.Outlined.Download, Icons.Filled.Download),
    RootTab(Route.Favorites, "المفضّلة", Icons.Outlined.FavoriteBorder, Icons.Filled.Favorite),
)

/** مفتاح حالة يميّز محتوى المسار، ويتجاهل startAtMs المؤقت لنفس الدرس. */
private fun routeStateKey(route: Route): String = when (route) {
    Route.Home -> "Home"
    Route.Library -> "Library"
    Route.MyLists -> "MyLists"
    Route.Downloads -> "Downloads"
    Route.Favorites -> "Favorites"
    is Route.Category -> "Category:${route.id}"
    is Route.Subcategory -> "Subcategory:${route.id}"
    is Route.Lesson -> "Lesson:${route.id}"
    is Route.Search -> "Search:${route.initial}"
    is Route.Playlist -> "Playlist:${route.id}"
    Route.Radio -> "Radio"
    Route.Car -> "Car"
    Route.Stats -> "Stats"
    Route.Contribute -> "Contribute"
    Route.ContributeTranscript -> "ContributeTranscript"
    Route.MySubmissions -> "MySubmissions"
    Route.Notifications -> "Notifications"
}

@Composable
fun MinbarApp(vm: AppViewModel, requestNotifications: () -> Unit) {
    val route by vm.route.collectAsState()
    val content by vm.content.state.collectAsState()
    val playback by vm.playback.state.collectAsState()
    val message by vm.message.collectAsState()
    val showSettings by vm.showSettings.collectAsState()
    val transcriptContribution by vm.transcriptContribution.collectAsState()
    val updateStatus by vm.updateStatus.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // 🔔 تذكير التحديث — **شاشة كاملة** لا شريطاً ولا إشعاراً: أغلب
    // المستخدمين لا ينظرون إلى الإشعارات ولا إلى شريط صغير، فتبقى نسخ قديمة
    // تعمل بمزايا ناقصة. وهي مع ذلك متزنة: لا تحجب التطبيق («لاحقاً» متاح
    // دائماً)، ولا تتكرّر للنسخة الاختياريّة أكثر من مرّة كل ٢٤ ساعة.
    var updateDeferred by rememberSaveable { mutableStateOf(false) }
    val pendingUpdate = updateStatus.takeIf { it !is AppConfigRepository.Status.None }
    if (pendingUpdate != null && !updateDeferred && vm.shouldPromptUpdate(pendingUpdate)) {
        UpdateScreen(
            status = pendingUpdate,
            onUpdate = { vm.openStoreFor(pendingUpdate) },
            onLater = {
                updateDeferred = true
                vm.noteUpdatePromptShown(pendingUpdate)
            },
        )
        return
    }

    // تأكيد التحميل الجماعي — حوار واحد على مستوى التطبيق: كل الشاشات
    // تمرّ بـ`requestBulkDownload`، فلا يبقى مدخل يُنزّل قسماً بلا سؤال.
    val pendingBulk by vm.pendingBulkDownload.collectAsState()
    pendingBulk?.let { request ->
        ConfirmBulkDownloadDialog(
            label = request.label,
            count = request.count,
            onConfirm = { vm.confirmBulkDownload() },
            onDismiss = { vm.dismissBulkDownload() },
        )
    }

    val isRoot = rootTabs.any { it.route == route }
    val navigationBlocked = route == Route.ContributeTranscript &&
        transcriptContribution.submitting
    // المشغّل ووضع القيادة شاشتان بملء الشاشة بلا أشرطة (نمط الأصل).
    val fullScreen = route is Route.Lesson || route == Route.Car

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    // فشل التشغيل كان صامتاً خارج شاشة المشغّل (صفوف القوائم/المشغّل المصغّر):
    // نعرضه عالمياً مع «إعادة المحاولة». شاشة المشغّل لها شريطها الثابت فلا نكرّره.
    // نعرض الرسالة مرة واحدة لكل خطأ؛ كل محاولة جديدة تُصفّر الخطأ أولاً فيُعاد عرضه.
    var shownError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playback.error, route) {
        val error = playback.error
        if (error == null) {
            shownError = null
            return@LaunchedEffect
        }
        if (route is Route.Lesson || error == shownError) return@LaunchedEffect
        shownError = error
        val result = snackbar.showSnackbar(
            message = error,
            actionLabel = "إعادة المحاولة",
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) vm.playback.retry()
    }
    BackHandler(enabled = !isRoot && !navigationBlocked) { vm.back() }
    BackHandler(enabled = navigationBlocked) {
        vm.showMessage("انتظر اكتمال إرسال المساهمة قبل الرجوع.")
    }

    Scaffold(
        topBar = {
            if (!fullScreen) {
                CenterAlignedTopAppBar(
                    title = { Text(titleFor(route, vm)) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = if (isDarkTheme(vm.store.themeMode())) AppBarBackgroundDark else AppBarBackgroundLight,
                        titleContentColor = AppBarForeground,
                        navigationIconContentColor = AppBarForeground,
                        actionIconContentColor = AppBarForeground,
                    ),
                    navigationIcon = {
                        if (!isRoot) {
                            IconButton(
                                onClick = { vm.back() },
                                enabled = !navigationBlocked,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع")
                            }
                        }
                    },
                    actions = {
                        when (route) {
                            Route.Home -> {
                                if (content.syncing) {
                                    Box(Modifier.padding(14.dp)) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = AppBarForeground,
                                        )
                                    }
                                }
                                StreakChip(vm)
                                NotificationBell(vm)
                                MySubmissionsButton(vm)
                                IconButton(onClick = { vm.open(Route.Search()) }) {
                                    Icon(Icons.Filled.Search, "بحث")
                                }
                                IconButton(onClick = vm::openSettings) {
                                    Icon(Icons.Filled.MoreVert, "الإعدادات")
                                }
                            }
                            Route.Contribute -> {
                                IconButton(onClick = { vm.open(Route.MySubmissions) }) {
                                    Icon(Icons.Filled.HistoryEdu, "مساهماتي")
                                }
                            }
                            else -> Unit
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // 📤 «شارك درساً» يظهر في تبويب الرئيسية فقط (نمط الأصل).
            if (route == Route.Home) {
                ExtendedFloatingActionButton(
                    onClick = { vm.open(Route.Contribute) },
                    icon = { Icon(Icons.Filled.MicExternalOn, contentDescription = null) },
                    text = { Text("شارك درساً") },
                )
            }
        },
        bottomBar = {
            if (!fullScreen) {
                Column {
                    // المشغّل المصغّر: التبويبات الجذرية + شاشة دروس القسم الفرعي.
                    if (isRoot || route is Route.Subcategory) {
                        MiniPlayer(
                            state = playback,
                            onOpen = {
                                playback.mediaId.takeIf(String::isNotBlank)
                                    ?.let { vm.open(Route.Lesson(it)) }
                            },
                            onToggle = vm.playback::toggle,
                            onNext = { vm.playback.next() },
                            onRetry = { vm.playback.retry() },
                            onDismissError = { vm.playback.clearError() },
                        )
                    }
                    if (isRoot) {
                        // مؤشّر التحديد تركوازي فاتح/داكن كما في navigationBarTheme الأصلي.
                        val dark = isDarkTheme(vm.store.themeMode())
                        NavigationBar {
                            rootTabs.forEach { tab ->
                                val selected = route == tab.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { vm.openRoot(tab.route) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = if (dark) {
                                            androidx.compose.ui.graphics.Color(0xFF31575A)
                                        } else {
                                            androidx.compose.ui.graphics.Color(0xFFDCEBE9)
                                        },
                                        selectedIconColor = if (dark) SecondaryGold else Teal,
                                        selectedTextColor = if (dark) SecondaryGold else Teal,
                                    ),
                                    icon = {
                                        Icon(
                                            if (selected) tab.selectedIcon else tab.icon,
                                            contentDescription = tab.label,
                                        )
                                    },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    } else {
                        // العرض حتى حافة الشاشة: شريط التنقّل السفلي يستهلك
                        // حشوة شريط النظام بنفسه، فبغيابه لا يستهلكها شيء
                        // ويسكن آخر المحتوى (والمشغّل المصغّر) خلف شريط
                        // التنقّل. هذه المساحة تحجزها بلا أي تغيير في السمة.
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .windowInsetsBottomHeight(WindowInsets.navigationBars),
                        )
                    }
                }
            }
        },
    ) { padding ->
        // حافظ حالة الشاشات عبر التنقل: بلاه كان تبديل الشاشة (مثلاً مشاركة
        // صورة تفتح «ساهم بالنص») يدمّر rememberSaveable لنموذج «شارك درساً»
        // المفتوح فيمسح ملفات المستخدم وعنوانه بصمت.
        val screenStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        Column(Modifier.padding(padding)) {
        Box(Modifier.weight(1f)) {
            screenStateHolder.SaveableStateProvider(routeStateKey(route)) {
                when (val current = route) {
                Route.Home -> HomeScreen(vm, content, playback)
                Route.Library -> LibraryScreen(vm, content)
                Route.MyLists -> MyListsScreen(vm, playback)
                Route.Downloads -> DownloadsScreen(vm)
                Route.Favorites -> FavoritesScreen(vm, playback)
                is Route.Category -> CategoryScreen(vm, current.id, content)
                is Route.Subcategory -> LessonsScreen(vm, current.id, playback)
                is Route.Search -> SearchScreen(vm, current.initial, playback)
                is Route.Lesson -> {
                    val lesson = content.lessonById[current.id]
                    if (lesson == null) {
                        EmptyState(
                            "الدرس غير متاح",
                            if (content.loading) "جارٍ تحميل المحتوى…" else "ربما أزيل أو لم يُنشر بعد.",
                        )
                    } else {
                        PlayerScreen(vm, lesson, current.startAtMs, playback)
                    }
                }
                is Route.Playlist -> PlaylistDetailScreen(vm, current.id)
                Route.Radio -> RadioScreen(vm)
                Route.Car -> CarScreen(vm, playback)
                Route.Stats -> StatsScreen(vm)
                Route.Contribute -> ContributeScreen(vm)
                Route.ContributeTranscript -> TranscriptContributeScreen(vm)
                Route.MySubmissions -> MySubmissionsScreen(vm)
                Route.Notifications -> NotificationsScreen(vm)
                }
            }
        }
        }
    }

    if (showSettings) {
        SettingsSheet(vm, requestNotifications)
    }
}

/**
 * شاشة «حدِّث التطبيق» بملء الشاشة.
 *
 * **لماذا ملء الشاشة؟** الشريط الصغير والإشعار كلاهما يُتجاهَل عملياً.
 * **ولماذا متزنة؟** الحجب الكامل يحوّل تطبيق استماع إلى رهينة تحديث؛ والدروس
 * المنزَّلة يجب أن تبقى قابلة للاستماع بلا إنترنت ومهما قدُم الإصدار — فزرّ
 * المتابعة موجود دائماً.
 */
@Composable
private fun UpdateScreen(
    status: AppConfigRepository.Status,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    val required = status is AppConfigRepository.Status.Required
    val custom = when (status) {
        is AppConfigRepository.Status.Required -> status.message
        is AppConfigRepository.Status.Optional -> status.message
        else -> ""
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.SystemUpdate,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(22.dp))
            Text(
                if (required) "دعم هذا الإصدار يوشك أن يتوقّف" else "تتوفّر نسخة أحدث",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                custom.ifBlank {
                    if (required) {
                        "نسختك من منبر ادكصهك قديمة وسيتوقّف دعمها قريباً. " +
                            "حدِّثها لتبقى المزايا كلّها تعمل كما ينبغي."
                    } else {
                        "حدِّث منبر ادكصهك لئلّا تفقد ميزات مهمّة وإصلاحات جديدة."
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "تنزيلاتك وقوائمك ومواضع استماعك تبقى كما هي.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(30.dp))
            Button(
                onClick = onUpdate,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("تحديث الآن", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
                Text(if (required) "متابعة الآن" else "لاحقاً")
            }
        }
    }
}

/// شريحة السلسلة 🔥 في الشريط العلوي — كانت مدفونة في ورقة الإعدادات
/// و«حصادك» فقط، فلا يراها المستخدم حيث تحفّزه فعلاً. تظهر من يومين فصاعداً،
/// والنقر يفتح «حصادك».
@Composable
private fun StreakChip(vm: AppViewModel) {
    val revision by vm.store.revision.collectAsState()
    val streak = remember(revision) { vm.store.streakDays() }
    if (streak < 2) return
    Row(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(AppBarForeground.copy(alpha = 0.16f))
            .clickable { vm.open(Route.Stats) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.LocalFireDepartment,
            contentDescription = "سلسلة الاستماع: $streak يوماً",
            tint = GoldOnDark,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "$streak",
            color = AppBarForeground,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/// زر الجرس مع عدّاد غير المقروء — نمط NotificationBell الأصلي.
@Composable
private fun NotificationBell(vm: AppViewModel) {
    val items by vm.notifications.collectAsState()
    val revision by vm.store.revision.collectAsState()
    val unread = remember(items, revision) {
        val lastSeen = vm.store.notificationLastSeenMs()
        visibleNotifications(vm, items).count { it.createdAtMs > lastSeen }
    }
    IconButton(onClick = { vm.open(Route.Notifications) }) {
        if (unread > 0) {
            BadgedBox(badge = { Badge { Text("$unread") } }) {
                Icon(Icons.Filled.NotificationsNone, contentDescription = "الإشعارات")
            }
        } else {
            Icon(Icons.Filled.NotificationsNone, contentDescription = "الإشعارات")
        }
    }
}

/// زر «مساهماتي» بجوار الجرس — يظهر لمن لديه هوية مساهمات فقط،
/// وعليه نقطة إذا حُسمت مساهمة بعد آخر زيارة للشاشة.
@Composable
private fun MySubmissionsButton(vm: AppViewModel) {
    var user by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser) }
    DisposableEffect(Unit) {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { user = it.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    if (user == null) return

    val flow = remember { vm.submissions.mine().catch { emit(emptyList()) } }
    val submissions by flow.collectAsState(initial = emptyList())
    val revision by vm.store.revision.collectAsState()
    val hasNewDecision = remember(submissions, revision) {
        val seen = vm.store.submissionsLastSeenMs()
        submissions.any { it.status != "pending" && it.decidedAtMs > seen }
    }
    IconButton(onClick = { vm.open(Route.MySubmissions) }) {
        if (hasNewDecision) {
            BadgedBox(badge = { Badge(modifier = Modifier.size(8.dp)) }) {
                Icon(Icons.Filled.Outbox, contentDescription = "مساهماتي")
            }
        } else {
            Icon(Icons.Filled.Outbox, contentDescription = "مساهماتي")
        }
    }
}

private fun titleFor(route: Route, vm: AppViewModel): String = when (route) {
    Route.Home -> "منبر ادكصهك"
    Route.Library -> "المكتبة"
    Route.MyLists -> "قوائمي"
    Route.Downloads -> "تنزيلاتي"
    Route.Favorites -> "المفضّلة"
    is Route.Category -> vm.content.state.value.categoryById[route.id]?.name ?: "الأقسام"
    is Route.Subcategory -> vm.content.state.value.subcategoryById[route.id]?.name ?: "الدروس"
    is Route.Lesson -> "الآن يُشغَّل"
    is Route.Search -> "بحث"
    is Route.Playlist -> vm.store.playlists().firstOrNull { it.id == route.id }?.name ?: "قائمة التشغيل"
    Route.Radio -> "إذاعة منبر"
    Route.Car -> "وضع القيادة"
    Route.Stats -> "حصادك"
    Route.Contribute -> "شارك درساً"
    Route.ContributeTranscript -> "ساهم بالنص"
    Route.MySubmissions -> "مساهماتي"
    Route.Notifications -> "الإشعارات"
}
