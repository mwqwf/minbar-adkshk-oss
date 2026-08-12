package com.ali.menbaradkshk.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ali.menbaradkshk.MainActivity
import com.ali.menbaradkshk.R
import com.ali.menbaradkshk.data.LocalStore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MinbarMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val store = LocalStore.get(this)
        if (!store.notificationsEnabled()) return
        val title = message.notification?.title ?: message.data["title"] ?: getString(R.string.app_name)
        val body = message.notification?.body ?: message.data["body"].orEmpty()
        val destination = destinationFor(message.data)
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            destination?.let { data = android.net.Uri.parse(it) }
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            destination.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NotificationChannels.CONTENT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            NotificationManagerCompat.from(this)
                .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        }
    }

    override fun onNewToken(token: String) {
        // Pending submissions refresh their token the next time the user opens that screen.
    }

    companion object {
        /**
         * «الحمولة ← وجهة» — مصدر الحقيقة الوحيد للتوجيه، يستعمله هذا
         * المستقبل (رسائل التطبيق في المقدّمة) و`MainActivity` معاً (نقر
         * إشعار رسمه النظام والتطبيق في الخلفية، فالحمولة تصل في extras).
         *
         * التوجيه بحسب `type` حصراً: قراءة `id` بلا فحص النوع كانت تفتح
         * `lesson/<معرّف مساهمة>` = شاشة ميتة.
         * - `lesson` ⇒ الدرس
         * - `subcategory` / `category` ⇒ القسم الفرعي/الرئيسي
         * - `book` ⇒ لا وجهة (الكتب أُزيلت عمداً من التطبيق)
         * - `manual` ⇒ لا وجهة (فتح التطبيق فقط)
         * - `submission` / `transcript` ⇒ «مساهماتي»، إلا أن تحمل `lessonId`
         *   (فرع الاعتماد يرسله) فتُفتح صفحة الدرس المنشور.
         */
        fun destinationFor(data: Map<String, String>): String? {
            fun value(vararg keys: String): String? = keys.asSequence()
                .map { data[it] }
                .firstOrNull { !it.isNullOrBlank() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val lessonId = value("lessonId", "lesson_id")
            val refId = value("id", "refId")

            // الخادم صار يرسل `route` صريحاً لإشعارات المساهمات، وهو الحَكَم:
            // حمولة **رفض** النص المشروح تحمل lessonId (لتعريف الدرس) لكن
            // وجهتها «مساهماتي» لا صفحة الدرس — فالاستنتاج من lessonId وحده
            // يعيد الخطأ الذي أُصلح.
            when (value("route")) {
                "my-submissions" -> return "minbar://my-submissions"
                "lesson" -> lessonId?.let { return LESSON_LINK_PREFIX + it }
                "downloads" -> return "minbar://downloads"
            }

            return when (data["type"]?.trim().orEmpty()) {
                "submission", "transcript" ->
                    lessonId?.let { LESSON_LINK_PREFIX + it } ?: "minbar://my-submissions"
                "lesson" -> (lessonId ?: refId)?.let { LESSON_LINK_PREFIX + it }
                "subcategory" -> value("subcategoryId", "subId", "id")
                    ?.let { "minbar://subcategory/$it" }
                "category" -> value("categoryId", "id")
                    ?.let { "minbar://category/$it" }
                // الكتب أُزيلت من التطبيق، والإشعار اليدوي بلا هدف أصلاً.
                "book", "manual" -> null
                // نوع غير معروف: نفتح درساً فقط إن صرّحت الحمولة بمعرّف درس،
                // ولا نجازف بتفسير `id` عاماً (قد يكون معرّف مساهمة أو كتاب).
                else -> lessonId?.let { LESSON_LINK_PREFIX + it }
            }
        }

        private const val LESSON_LINK_PREFIX = "https://minbar-adkassahk.vercel.app/lesson/"
    }
}
