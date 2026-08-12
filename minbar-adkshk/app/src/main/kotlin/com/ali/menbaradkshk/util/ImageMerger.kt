package com.ali.menbaradkshk.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.bitmapConfig
import coil3.size.Precision
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 🖼️ دمج صور صفحات الكتاب عموديّاً في صورة واحدة — نظير AudioMerger
 * للصوتيات: الترتيب الذي اختاره المستخدم (أيّها أولاً) هو ترتيب اللصق من
 * الأعلى للأسفل. تُوحَّد الأعراض وتُضغط النتيجة JPEG في كاش التطبيق.
 *
 * فكّ الترميز يمرّ بـ**Coil** لا بـ`BitmapFactory` مباشرةً: فحص Play يرصد
 * فكّ الترميز اليدوي ويحذّر من «استخدام مفرط للذاكرة وبطء وتعطّل»، ومكتبة
 * التحميل تتولّى تقليل الدقّة وإدارة الذاكرة. ومكسب إضافي: Coil يطبّق اتجاه
 * EXIF بنفسه، فسقط التحويل اليدوي الذي كان يحاكيه (وكان تكراره بعد Coil
 * سيدوّر الصورة مرّتين).
 */
object ImageMerger {
    const val MAX_MERGE_IMAGES = 4
    private const val TARGET_WIDTH = 1440
    private const val MAX_TOTAL_HEIGHT = 14_000

    /** يدمج الصور بترتيبها ويعيد Uri لملف الناتج في كاش التطبيق. */
    suspend fun mergeVertically(context: Context, uris: List<Uri>): Uri {
        require(uris.size in 2..MAX_MERGE_IMAGES) { "اختر صورتين إلى أربع صور للدمج." }
        // الجلب خارج Dispatchers.IO: `execute` معلَّقة وتدير خيوطها بنفسها.
        val bitmaps = uris.map { decodeScaled(context, it) }
        return withContext(Dispatchers.IO) {
            val width = TARGET_WIDTH
            val heights = bitmaps.map { bmp ->
                (bmp.height.toLong() * width / bmp.width).toInt().coerceAtLeast(1)
            }
            val totalHeight = heights.sum()
            require(totalHeight <= MAX_TOTAL_HEIGHT) {
                "الصور طويلة جداً للدمج في صورة واحدة — قصّها أولاً أو أرسلها منفصلة."
            }
            val merged = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(merged)
                canvas.drawColor(Color.WHITE)
                var top = 0
                bitmaps.forEachIndexed { index, bmp ->
                    val h = heights[index]
                    canvas.drawBitmap(bmp, null, Rect(0, top, width, top + h), null)
                    top += h
                }
                val dir = File(context.cacheDir, "merged_pages").apply { mkdirs() }
                // نظافة الكاش: نواتج دمج قديمة لم تُستعمل تُحذف بعد يوم.
                val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
                val out = File(dir, "pages_${System.currentTimeMillis()}.jpg")
                val written = out.outputStream().use { stream ->
                    merged.compress(Bitmap.CompressFormat.JPEG, 88, stream)
                }
                check(written) { "تعذّر حفظ الصورة المدموجة." }
                Uri.fromFile(out)
            } finally {
                merged.recycle()
            }
        }
    }

    /**
     * يقرأ الصورة بمقاس مناسب عبر Coil.
     *
     * ⚠️ لا نُحرّر (`recycle`) البِتماب العائد: قد يملكه Coil. ولذلك نُعطّل
     * كاش الذاكرة لهذه الطلبات تحديداً كي لا تتضخّم بصور ضخمة تُستعمل مرّة
     * واحدة، ونترك تحريرها لجامع المهملات.
     */
    private suspend fun decodeScaled(context: Context, uri: Uri): Bitmap {
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(TARGET_WIDTH, MAX_TOTAL_HEIGHT)
            .precision(Precision.INEXACT)
            // ARGB_8888 لا HARDWARE: البِتماب العتاديّ لا يُرسم على Canvas برمجيّ.
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val image = (result as? SuccessResult)?.image
        requireNotNull(image) { "تعذّرت قراءة إحدى الصور." }
        val bitmap = (image as? BitmapImage)?.bitmap
            ?: image.toBitmap(image.width, image.height)
        // شبكة أمان: لو عاد بِتماب عتاديّ رغم الإعداد، ننسخه نسخة برمجيّة.
        return if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        } else {
            bitmap
        }
    }
}
