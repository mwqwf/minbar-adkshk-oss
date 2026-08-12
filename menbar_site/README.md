# موقع منبر ادكصهك — المصدر الحيّ (نسخة محلية)

هذه نسخة مطابقة لما هو منشور على `https://minbar-adkassahk.vercel.app`
(حُمِّلت من الموقع الحيّ نفسه في 2026-07-21).

## الملفات
| الملف | المسار على الويب |
|---|---|
| `index.html` | `/` |
| `privacy.html` | `/privacy` |
| `lesson.html` | `/lesson/<id>` (كل معرّفات الدروس تُوجَّه إليها) |
| `.well-known/assetlinks.json` | توثيق Android App Links |

## ⚠️ إعادة النشر — الصيغة الإلزامية
مشروع Vercel مضبوط على إطار Next.js، فأي نشر ثابت عادي يفشل بـ `NEXT_NO_VERSION`.
الحل الوحيد المجرَّب: `vercel.json` بالصيغة الكلاسيكية (لا تُحذف ولا تُبسَّط):

```json
{
  "version": 2,
  "builds": [{ "src": "**", "use": "@vercel/static" }],
  "routes": [
    { "handle": "filesystem" },
    { "src": "^/privacy/?$", "dest": "/privacy.html" },
    { "src": "^/lesson/[^/]+/?$", "dest": "/lesson.html" },
    { "src": "^/$", "dest": "/index.html" }
  ]
}
```

## 🔑 assetlinks.json — لا تحذف أي بصمة
ثلاث بصمات SHA-256 يجب بقاؤها جميعاً:
1. **Play App Signing** (`AD:35:8A:…`) — نسخة المستخدمين من المتجر. حذفها يكسر
   روابط المشاركة لكل الجمهور.
2. مفتاح الإصدار المحلي (`BF:D6:D8:…`) — نسخ APK المبنية من الجهاز.
3. debug (`57:44:49:…`) — للتجربة أثناء التطوير.

استخراج بصمة أي APK:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\apksigner.bat" verify --print-certs app.apk
```

## التحقق بعد أي نشر
```bash
curl -s -o /dev/null -w "%{http_code}\n" https://minbar-adkassahk.vercel.app/lesson/test
curl -s https://minbar-adkassahk.vercel.app/.well-known/assetlinks.json
```
ويجب أن يعيد الأول `200` والثاني ملف JSON بالبصمات الثلاث.
