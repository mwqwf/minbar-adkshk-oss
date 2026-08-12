# ==============================================================================
# قواعد R8 لتطبيق «منبر ادكصهك» (com.ali.menbaradkshk)
#
# R8 مفعّل بالكامل: isMinifyEnabled + isShrinkResources + full mode (افتراضي
# منذ AGP 8). وتوثيق Google صريح: «فوائد R8 مرتبطة مباشرة بمقدار ما يستطيع
# تحسينه» — فكل قاعدة keep زائدة تعني حجماً أكبر وبدء تشغيل أبطأ.
#
#   ⛔ ممنوع `-keep ... { *; }` على حزمة كاملة.
#   ⛔ ممنوع إضافة قاعدة بلا سبب مكتوب تحتها.
#   ✅ كل قاعدة هنا مرفقة بدليلها.
#
# روجع 2026-07-31: أُزيلت ثلاث قواعد حزم كاملة (data / notification / widget)
# وسطرا -keepattributes بعد إثبات أنّها زائدة كلّها — الأدلّة أدناه.
# ==============================================================================


# ------------------------------------------------------------------------------
# القاعدة الوحيدة اللازمة: عمّال WorkManager.
#
# لماذا: WorkerFactory الافتراضي يُنشئ العامل من **اسم صنفه النصّي** المخزَّن في
# قاعدة بيانات WorkManager — مسار لا يراه R8 كاستدعاء. وقواعد المستهلك التي
# تشحنها androidx.work لا تكفي، لأنّ أوّلها `-keepnames` (تمنع إعادة التسمية
# فقط إن نجا الصنف، ولا تمنع حذفه) والثانية `-keepclassmembers` (مشروطة ببقاء
# الصنف). فإبقاء الصنف وبانيه صراحةً هو الضمان الوحيد.
#
# يغطّي: ContinueReminderWorker و WardWorker و AutoDownloadWorker
#        (notification/BackgroundWorkers.kt) و LessonDownloadWorker
#        (data/DownloadWorker.kt).
#
# ⚠️ حذفها يعطّل «تابع الاستماع» و«وِرد اليوم» والتنزيل التلقائي وطابور التنزيل
#    **في نسخة الإصدار وحدها وبصمت** (نسخة التطوير سليمة لأن R8 معطّل فيها).
# ------------------------------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}


# ==============================================================================
# لماذا لا توجد قاعدة لمكوّنات المانيفست (الودجت، خدمة FCM، JobService، الخدمة)
# ==============================================================================
# AGP يولّدها تلقائياً ويمرّرها إلى R8. تحقّق بنفسك:
#   app/build/intermediates/aapt_proguard_file/release/
#       processReleaseResources/aapt_rules.txt
# وفيه حرفياً لهذا التطبيق:
#   -keep class com.ali.menbaradkshk.MainActivity                        { <init>(); }
#   -keep class com.ali.menbaradkshk.MinbarApplication                   { <init>(); }
#   -keep class com.ali.menbaradkshk.data.LessonDownloadJobService       { <init>(); }
#   -keep class com.ali.menbaradkshk.media.PlaybackService               { <init>(); }
#   -keep class com.ali.menbaradkshk.notification.MinbarMessagingService { <init>(); }
#   -keep class com.ali.menbaradkshk.widget.NowPlayingWidget             { <init>(); }
#
# ⇒ القاعدتان القديمتان التاليتان كانتا تكراراً محضاً — لا تُعِدهما:
#     -keep class com.ali.menbaradkshk.notification.** { *; }
#     -keep class com.ali.menbaradkshk.widget.**       { *; }
# كانتا تُبقيان BackgroundScheduler و NotificationChannels و NotificationPublisher
# و NowPlayingWidget بكل أعضائها الخاصّة، وكلّها تُستدعى استدعاءً مباشراً.


# ==============================================================================
# لماذا لا توجد قاعدة لطبقة البيانات com.ali.menbaradkshk.data
# ==============================================================================
# التعليق القديم زعم أنّ «لقطات Firestore تُفحص انعكاسياً على بعض الأجهزة».
# غير صحيح في هذا المشروع، والدليل قاطع (بحث شامل = صفر نتيجة):
#   لا toObject() ولا @PropertyName ولا @DocumentId ولا @IgnoreExtraProperties
#   ولا Gson ولا Moshi ولا kotlinx.serialization ولا @Keep ولا Class.forName.
#
#   • قراءة Firestore يدويّة بالمفاتيح النصّية:
#       ContentRepository.kt → Category/Subcategory/Lesson.fromMap(document.data)
#       SubmissionRepository.kt و NotificationsRepository.kt →
#           document.getString("title") / document.getLong("createdAtMs")
#   • الكتابة إلى الخادم عبر mapOf(...) لا عبر كائنات POJO.
#   • التخزين المحلي JSON يدويّ (Models.kt: toJson/fromJson) — أسماء الحقول
#     ثابتة نصّياً داخل الدالتين فلا يضرّها التعتيم.
#   • rememberSaveable لا يحفظ أي نوع مخصّص (listSaver إلى String).
#
# ⇒ أسماء أصناف وحقول data/ ليست جزءاً من أي عقد تشغيلي، وتعتيمها آمن. وهي
#   أكبر طبقة في التطبيق، فتحريرها للتحسين أكبر مكسب متاح للحجم وبدء التشغيل.
#
# ⚠️ إن أُضيف مستقبلاً toObject()/@PropertyName/Gson، فثبّت **الصنف المعنيّ
#    وحده** لا الحزمة، مثال:
#      -keepclassmembers class com.ali.menbaradkshk.data.Lesson {
#          <init>(); <fields>;
#      }


# ==============================================================================
# لماذا لا يوجد -keepattributes
# ==============================================================================
# build.gradle.kts يطبّق proguard-android-optimize.txt وهو يحوي أصلاً:
#   -keepattributes AnnotationDefault, EnclosingMethod, InnerClasses,
#                   RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations,
#                   RuntimeVisibleTypeAnnotations, Signature
#
#   • `-keepattributes Signature` كان تكراراً حرفياً (وسببه المعتاد — الأنواع
#     العامّة في toObject/Gson — غير قائم أصلاً هنا).
#   • `-keepattributes *Annotation*` كان يضيف سمات RuntimeInvisible* فقط، وهي
#     بتعريفها (Retention.CLASS) غير مقروءة بالانعكاس وقت التشغيل، فلا تنفع
#     أحداً وتزيد الحجم. أمّا kotlin.Metadata فهي RUNTIME ومغطّاة بالافتراضي.


# ==============================================================================
# WebRTC — غير مستعمل في التطبيق العام
# ==============================================================================
# قاعدة `-keep class org.webrtc.** { *; }` تخصّ **لوحة الإدارة** وحدها (المكالمات
# الصوتيّة بين المشرفين). لا تنسخها إلى هنا: التطبيق العام لا يعتمد WebRTC.


# ==============================================================================
# للطوارئ فقط — تراجُع سريع
# ==============================================================================
# إن ظهر عطل يخصّ نسخة الإصدار وحدها بعد هذا التضييق، فعّل السطر المناسب مؤقّتاً
# لتحديد الطبقة المسؤولة، ثمّ استبدله بقاعدة ضيّقة على الصنف بعينه مع كتابة
# سببها هنا — ولا تتركه مفتوحاً على الحزمة:
# -keep class com.ali.menbaradkshk.data.**         { *; }
# -keep class com.ali.menbaradkshk.notification.** { *; }
# -keep class com.ali.menbaradkshk.widget.**       { *; }
