package com.ali.menbaradkshk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ali.menbaradkshk.notification.MinbarMessagingService
import com.ali.menbaradkshk.ui.AppViewModel
import com.ali.menbaradkshk.ui.MinbarApp
import com.ali.menbaradkshk.ui.MinbarTheme
import com.ali.menbaradkshk.ui.isDarkTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) viewModel.showMessage("يمكنك تفعيل الإشعارات لاحقًا من إعدادات النظام.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ⛔ لا نستدعي `enableEdgeToEdge()`: نسختها في androidx.activity
        // تستدعي داخلياً `Window.setStatusBarColor` و`setNavigationBarColor`
        // وتضبط `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` — وهي الواجهات
        // الثلاث المتوقّفة في أندرويد 15 التي يرصدها فحص Play (تظهر في تقريره
        // مبهَمةً باسم androidx لا باسم كودنا). البديل أدناه غير متوقّف كلّياً:
        // على targetSdk 35+ العرض حتى الحافة مفروض من النظام أصلاً، فيكفي أن
        // نُخبر النافذة بألّا تُقلّم المحتوى، ثم نضبط تباين أيقونات الشريطين.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.refresh(true)
                    // فحص التحديث مع كل عودة إلى التطبيق لا مرّة واحدة عند
                    // إنشاء الـViewModel: التطبيق قد يبقى في الذاكرة أياماً،
                    // فكان صاحبه لا يرى تذكيراً أبداً بعد أوّل تشغيل.
                    // (القراءة الشبكيّة نفسها تبقى محكومة بخانق الست ساعات.)
                    viewModel.checkForUpdate()
                }
            },
        )
        // إعادة إنشاء النشاط (تدوير الشاشة) تعيد النيّة نفسها — لا تُلتقط مرّتين.
        if (savedInstanceState == null) {
            viewModel.handleDeepLink(deepLinkFrom(intent))
            captureShare(intent)
            requestNotificationPermissionOnce()
        }
        setContent {
            // القراءة الفعلية لـrevision داخل النطاق شرط إعادة التركيب عند
            // تغيير السمة أو حجم الخط من الإعدادات.
            val revision by viewModel.store.revision.collectAsState()
            val themeMode = remember(revision) { viewModel.store.themeMode() }
            val fontScale = remember(revision) { viewModel.store.fontScale() }
            // شريطا النظام يتبعان سمة **التطبيق** لا سمة الجهاز: المستخدم قد
            // يختار داكناً بينما النظام فاتح، فتصير الأيقونات غير مقروءة لو
            // تُركت للسلوك التلقائي. والشريط العلوي داكن في السمتين (Teal /
            // DarkAppBar) فأيقونات الحالة فاتحة دائماً، بينما شريط التنقّل
            // السفلي يتبع سطح التطبيق. هذا يحلّ محلّ statusBarColor/
            // navigationBarColor المتوقّفتين في أندرويد 15.
            val dark = isDarkTheme(themeMode)
            LaunchedEffect(dark) {
                val bars = WindowCompat.getInsetsController(window, window.decorView)
                // الشريط العلوي داكن في السمتين ⇒ أيقوناته فاتحة دائماً.
                bars.isAppearanceLightStatusBars = false
                // شريط التنقّل يتبع سطح التطبيق: أيقونات داكنة على سطح فاتح.
                bars.isAppearanceLightNavigationBars = !dark
            }
            MinbarTheme(
                themeMode = themeMode,
                fontScale = fontScale,
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MinbarApp(viewModel, ::requestNotificationPermission)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleDeepLink(deepLinkFrom(intent))
        captureShare(intent)
    }

    /**
     * وجهة النيّة: `intent.data` أولاً (الروابط العميقة وإشعارات المقدّمة
     * التي يبنيها MinbarMessagingService)، ثم **extras**.
     *
     * الخادم يرسل `notification` + `data` معاً، فحين يكون التطبيق في
     * الخلفية يرسم النظام الإشعار بنفسه ويفتح هذا النشاط بحمولة الـ`data`
     * داخل extras لا في `intent.data` — فكان النقر يفتح الرئيسية دائماً.
     * منطق «الحمولة ← وجهة» واحد ومشترك في `MinbarMessagingService`.
     */
    private fun deepLinkFrom(intent: Intent?): Uri? {
        if (intent == null) return null
        intent.data?.let { return it }
        val extras = intent.extras ?: return null
        val payload = buildMap<String, String> {
            for (key in extras.keySet()) {
                // حمولة FCM كلّها نصوص؛ أي مفتاح غير نصّي يُتجاهل بأمان.
                val value = runCatching { extras.getString(key) }.getOrNull()
                if (!value.isNullOrBlank()) put(key, value)
            }
        }
        if (payload.isEmpty()) return null
        val destination = MinbarMessagingService.destinationFor(payload) ?: return null
        return runCatching { Uri.parse(destination) }.getOrNull()
    }

    /// «مشاركة إلى منبر»: صوتيات ← «شارك درساً»، وصور/نص ← «ساهم بالنص»
    /// (باختيار الدرس). حمولة تطبيق آخر قد تكون تالفة — لا تُسقط التطبيق.
    private fun captureShare(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val type = intent.type.orEmpty()
        val uris: List<Uri> = runCatching { sharedStreamUris(intent) }.getOrDefault(emptyList())
        when {
            type.startsWith("audio/") && uris.isNotEmpty() -> {
                // تُستهلك النيّة فوراً كي لا تتكرّر الملفات إن عاد النشاط إليها.
                runCatching { intent.removeExtra(Intent.EXTRA_STREAM) }
                viewModel.receiveSharedAudio(uris)
            }

            type.startsWith("image/") && uris.isNotEmpty() -> {
                runCatching { intent.removeExtra(Intent.EXTRA_STREAM) }
                viewModel.receiveSharedTranscript(text = "", imageUris = uris)
            }

            type.startsWith("text/") -> {
                val text = runCatching {
                    intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                }.getOrDefault("")
                if (text.isNotBlank()) {
                    runCatching { intent.removeExtra(Intent.EXTRA_TEXT) }
                    viewModel.receiveSharedTranscript(text = text, imageUris = emptyList())
                }
            }
        }
    }

    private fun sharedStreamUris(intent: Intent): List<Uri> {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                list.orEmpty().filterNotNull()
            }

            else -> emptyList()
        }
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * طلب إذن الإشعارات عند أول تشغيل (نظير ما تفعله لوحة الإدارة).
     * بلاه كان أندرويد 13+ يمنع كل إشعار مدى الحياة في تثبيت جديد: مفتاح
     * الإعدادات مفعّل افتراضاً فلا يلمسه أحد، و`targetSdk=36` لا يعرض أي
     * حوار تلقائي — والنتيجة صفر إشعارات (حتى متحكّم المشغّل في شاشة القفل).
     * يُطلب مرّة واحدة فقط: العلامة تُحفظ فور العرض فلا يتحوّل إلى إزعاج
     * متكرّر، ويبقى مفتاح الإعدادات مساراً صريحاً لمن رفض ثم بدا له.
     */
    private fun requestNotificationPermissionOnce() {
        if (!viewModel.store.notificationsEnabled()) return
        if (viewModel.store.hintSeen(NOTIFICATION_PERMISSION_ASKED)) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.store.markHintSeen(NOTIFICATION_PERMISSION_ASKED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
    }
}
