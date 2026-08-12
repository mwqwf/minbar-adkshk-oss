package com.ali.menbaradkshk.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class ClipboardImageCandidate(
    val fingerprint: String,
    val uri: Uri,
)

/** يقترح الصورة المنسوخة عند عودة التطبيق للمقدّمة من دون مسح الحافظة. */
@Composable
fun ClipboardImageSuggestion(
    enabled: Boolean,
    onImage: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var refresh by remember { mutableIntStateOf(0) }
    var dismissedFingerprint by remember { mutableStateOf<String?>(null) }
    var candidate by remember { mutableStateOf<ClipboardImageCandidate?>(null) }

    DisposableEffect(clipboard, lifecycleOwner) {
        val clipListener = ClipboardManager.OnPrimaryClipChangedListener { refresh++ }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        clipboard.addPrimaryClipChangedListener(clipListener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        refresh++
        onDispose {
            clipboard.removePrimaryClipChangedListener(clipListener)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    LaunchedEffect(refresh, enabled) {
        if (!enabled || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            candidate = null
            return@LaunchedEffect
        }
        val clip = runCatching { clipboard.primaryClip }.getOrNull()
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val source = item?.uri
        if (source == null) {
            candidate = null
            return@LaunchedEffect
        }
        val declaredImage = clip.description.hasMimeType("image/*")
        val mime = runCatching { context.contentResolver.getType(source) }.getOrNull()
        if (!declaredImage && mime?.startsWith("image/") != true) {
            candidate = null
            return@LaunchedEffect
        }
        val fingerprint = "${source}|${clip.description.label}|${mime.orEmpty()}"
        if (fingerprint == dismissedFingerprint || candidate?.fingerprint == fingerprint) {
            return@LaunchedEffect
        }
        val local = withContext(Dispatchers.IO) {
            copyClipboardImageToCache(context, source, mime)
        }
        candidate = local?.let { ClipboardImageCandidate(fingerprint, Uri.fromFile(it)) }
    }

    val shown = candidate ?: return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(start = 8.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = shown.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("لقطة شاشة من الحافظة", fontSize = 12.5.sp)
                Text(
                    "إرفاقها كصورة؟",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    dismissedFingerprint = shown.fingerprint
                    candidate = null
                    onImage(shown.uri)
                },
            ) { Text("إرفاق") }
            IconButton(
                onClick = {
                    dismissedFingerprint = shown.fingerprint
                    candidate = null
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "إخفاء الاقتراح")
            }
        }
    }
}

private fun copyClipboardImageToCache(context: Context, source: Uri, mime: String?): File? =
    runCatching {
        val dir = File(context.cacheDir, "clipboard_images").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }
            ?.forEach(File::delete)
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "img"
        val output = File(dir, "clipboard_${System.nanoTime()}.$extension")
        context.contentResolver.openInputStream(source)?.use { input ->
            output.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        } ?: return null
        output
    }.getOrNull()
