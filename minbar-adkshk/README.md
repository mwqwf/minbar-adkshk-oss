# منبر ادكصهك — Android أصلي بـ Kotlin

هذه نسخة Android أصلية موازية للتطبيق المنشور، مكتوبة بـ Kotlin وJetpack Compose.
مشروع Flutter القديم لم يُعدّل، ليبقى مسار رجوع آمنًا حتى اجتياز اختبار الترقية.

## نتيجة التحقق

- نجح `assembleDebug`.
- نجحت الاختبارات الستة، ومنها اختبار ترحيل بيانات Flutter القديمة.
- نجح `lintDebug` دون أخطاء.
- تحقق APK فعليًا باسم `منبر ادكصهك`، والحزمة الإنتاجية
  `com.ali.menbaradkshk`، و`versionCode=6`.
- نسخة التجربة الجاهزة: `minbar-adkshk-debug.apk`.

## ثوابت الترقية الآمنة

- `applicationId`: `com.ali.menbaradkshk` — لم يتغير.
- `versionCode`: `6` — أعلى من النسخة المثبتة من Google Play (`3`).
- `versionName`: `1.3.2`.
- `minSdk`: 23، و`targetSdk`/`compileSdk`: 36.
- مشروع Firebase ومجموعاته ودواله ومسارات Storage لم تتغير.
- ملف Flutter القديم `FlutterSharedPreferences.xml` يُقرأ ويُنسخ مرة واحدة دون حذفه.
- تنزيلات Flutter القديمة تُستعمل من مساراتها الحالية ولا تُنزّل مجددًا.
- إصدار Release لا يعود إلى مفتاح Debug أبدًا؛ يفشل البناء إن غاب إعداد التوقيع.
- إصدار Debug يستخدم الحزمة `com.ali.menbaradkshk.dev` حتى يُثبت بجانب نسخة
  Play دون المساس بها. **الاسم الظاهر «منبر ادكصهك» في كل الأنواع بلا لاحقة**
  (لا «تجريبي» ولا غيرها) — الفصل بالحزمة لا بالاسم.

## البناء

افتح هذا المجلد مباشرة في Android Studio، أو نفّذ على Windows:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

اسم مجلد المشروع `minbar-adkshk`، وهو تهجئة لاتينية لاسم التطبيق الحقيقي
«منبر ادكصهك». الاسم الظاهر للمستخدم داخل التطبيق يبقى عربيًا كما هو.

## إنشاء AAB موقّع

1. انسخ `signing.properties.example` إلى `signing.properties`.
2. استخدم **مفتاح الرفع نفسه** للنسخة الموجودة في Google Play.
3. لا تضف `signing.properties` أو ملف المفتاح إلى Git؛ كلاهما مستثنى في `.gitignore`.
4. نفّذ:

```powershell
.\gradlew.bat testDebugUnitTest lintRelease bundleRelease
```

الناتج يكون في:
`app/build/outputs/bundle/release/app-release.aab`.

> تحذير: APK التجريبي موقّع بمفتاح Debug، لذلك لا يمكن تثبيته فوق نسخة Google
> Play الموقعة. اختبار الترقية الحقيقي يجب أن يتم برفع AAB الموقّع إلى مسار
> الاختبار الداخلي أو المغلق نفسه، ثم التحديث من Play.

## الميزات المنقولة

- Firebase: Firestore وAnonymous Auth وFunctions وStorage وMessaging وApp Check.
- ذاكرة محلية وترحيل آمن من Flutter للمفضلة والسجل والمواضع والقوائم والإحصاءات.
- Media3: تشغيل الخلفية وشاشة القفل والطابور والسرعة ومؤقت النوم.
- تنزيل ذري مع الاستماع دون اتصال والاستفادة من تنزيلات Flutter السابقة.
- روابط الدروس العميقة، المشاركة من لحظة زمنية، والإشعارات.
- الوِرد اليومي، تذكير متابعة الاستماع، والتنزيل التلقائي عبر WorkManager.
- المكتبة والبحث والمفضلة والسجل والقوائم والإذاعة ووضع السيارة والإحصاءات.
- مساهمات المستمعين ومتابعة قرارات المشرف وحذف بيانات المستخدم.

## سياسة النشر

لا ترفع النسخة مباشرة إلى Production. اتبع
[قائمة اختبار الترقية](MIGRATION-CHECKLIST.md) أولًا، ثم ابدأ بنسبة صغيرة من
المختبرين. احتفظ بنسخة Flutter وبآخر AAB صالح حتى اكتمال المراقبة.
