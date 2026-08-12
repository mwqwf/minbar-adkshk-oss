package com.ali.menbaradkshk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.data.Lesson
import com.ali.menbaradkshk.data.TranscriptDraft
import com.ali.menbaradkshk.data.TranscriptRepository
import com.ali.menbaradkshk.util.normalizeArabic

/**
 * 📖 «ساهم بالنص» شاشةً مستقلة — تُفتح من مشاركة صورة/نص من تطبيق خارجي:
 * يختار المستمع الدرس المقصود (بحث عربي)، ثم نفس نموذج المساهمة (نص +
 * صور صفحات بقصّ ودمج وترتيب) ويُرسل لمراجعة المشرفين.
 */
@Composable
fun TranscriptContributeScreen(vm: AppViewModel) {
    val content by vm.content.state.collectAsState()
    val shared by vm.sharedTranscript.collectAsState()
    val submission by vm.transcriptContribution.collectAsState()

    var lessonId by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var bookTitle by rememberSaveable { mutableStateOf("") }
    var sourceRef by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(vm.store.submitterName()) }
    val images = rememberSaveable(saver = uriStateListSaver) {
        mutableStateListOf<android.net.Uri>()
    }
    var message by rememberSaveable { mutableStateOf("") }
    val ownsSubmission = lessonId.isNotBlank() && submission.lessonId == lessonId
    val sending = submission.submitting
    val progress = if (ownsSubmission) submission.progress else 0
    val done = ownsSubmission && submission.done
    val visibleMessage = message.ifBlank {
        if (ownsSubmission) submission.error else ""
    }

    // حمولة المشاركة الخارجية تُدرج مرة واحدة فور تجهيزها.
    LaunchedEffect(shared) {
        if (shared.preparing) return@LaunchedEffect
        if (shared.text.isNotEmpty() || shared.images.isNotEmpty()) {
            if (shared.text.isNotEmpty()) {
                val incoming = shared.text.take(TranscriptRepository.MAX_TEXT_CHARS)
                text = when {
                    text.isBlank() -> incoming
                    text.contains(incoming) -> text
                    else -> "$text\n\n$incoming".take(TranscriptRepository.MAX_TEXT_CHARS)
                }
            }
            shared.images.forEach { uri ->
                if (images.size < TranscriptRepository.MAX_IMAGES) images.add(uri)
            }
            vm.consumeSharedTranscript()
        } else if (shared.error.isNotEmpty()) {
            message = shared.error
            vm.consumeSharedTranscript()
        }
    }

    val lesson = content.lessonById[lessonId]

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(GreenBrand.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text(
                "أرفق النص الأصلي (أو صورة الصفحة) من الكتاب الذي تشرحه الصوتية، " +
                    "واختر الدرس المقصود — يراجعه المشرفون قبل النشر.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(14.dp))

        if (lesson == null) {
            Text(
                "أولاً: اختر الدرس",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ابحث بالعنوان أو رقم الدرس أو اسم القسم") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                supportingText = {
                    Text("مثال: «3 الفقه» يجد الدرس رقم 3 في قسم الفقه.")
                },
                singleLine = true,
                enabled = !sending,
            )
            Spacer(Modifier.height(8.dp))
            // بحث عامّ ككل بحث تعليمي: يقبل رقماً واحداً، وكل كلمة من البحث
            // تُطابق العنوان أو القسم الرئيسي أو الفرعي — فالعناوين الرقمية
            // («3»، «12») تتمايز باسم قسمها.
            val tokens = remember(query) {
                query.trim().split(Regex("\\s+"))
                    .map { normalizeArabic(it) }
                    .filter { it.isNotEmpty() }
            }
            val matches = remember(tokens, content.lessons) {
                if (tokens.isEmpty()) {
                    emptyList()
                } else {
                    content.lessons.filter { item ->
                        val haystack = normalizeArabic(
                            listOfNotNull(
                                item.displayTitle,
                                content.subcategoryById[item.subcategoryId]?.name,
                                content.categoryById[item.categoryId]?.name,
                            ).joinToString(" "),
                        )
                        tokens.all { haystack.contains(it) }
                    }.take(20)
                }
            }
            if (tokens.isNotEmpty() && matches.isEmpty()) {
                Text(
                    "لا نتائج — جرّب رقم الدرس مع اسم قسمه، مثل: «3 الفقه».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            matches.forEach { item ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable(enabled = !sending) { lessonId = item.id },
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .background(colorForCategory(item.categoryId), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.displayTitle,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // مسار القسم كاملاً — به تتمايز العناوين الرقمية المتشابهة.
                            val sectionPath = listOfNotNull(
                                content.categoryById[item.categoryId]?.name
                                    ?.takeIf(String::isNotBlank),
                                content.subcategoryById[item.subcategoryId]?.name
                                    ?.takeIf(String::isNotBlank),
                            ).joinToString(" ← ")
                            if (sectionPath.isNotEmpty()) {
                                Text(
                                    sectionPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = GreenBrand,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        lesson.displayTitle,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = { lessonId = "" }, enabled = !sending) {
                        Icon(Icons.Filled.Edit, contentDescription = "تغيير الدرس", tint = GreenBrand)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = bookTitle,
                onValueChange = { if (it.length <= 200) bookTitle = it },
                label = { Text("اسم الكتاب/المتن (اختياري)") },
                singleLine = true,
                enabled = !sending,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sourceRef,
                onValueChange = { if (it.length <= 300) sourceRef = it },
                label = { Text("المقطع: من … إلى … (اختياري)") },
                singleLine = true,
                enabled = !sending,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= TranscriptRepository.MAX_TEXT_CHARS) text = it },
                label = { Text("النص الأصلي المشروح") },
                minLines = 5,
                maxLines = 12,
                enabled = !sending,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            if (shared.preparing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            TranscriptImagesEditor(
                images = images,
                enabled = !sending,
                onError = { message = it },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 50) name = it },
                label = { Text("اسمك (اختياري — يظهر مع النص عند الاعتماد)") },
                singleLine = true,
                enabled = !sending,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= 300) note = it },
                label = { Text("ملاحظة للمشرفين (اختياري)") },
                minLines = 1,
                maxLines = 3,
                enabled = !sending,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (visibleMessage.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                visibleMessage,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(14.dp))
        if (sending && images.isNotEmpty() && progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        FilledTonalButton(
            onClick = {
                // النقص يُشرح بعينه ولا يُخرِس الزر (نفس نهج «شارك درساً»).
                when {
                    lesson == null -> message = "اختر الدرس أولاً — ابحث عنه بعنوانه في الأعلى."
                    text.trim().length < 10 && images.isEmpty() ->
                        message = "أضف نص المقطع من الكتاب أو أرفق صورة صفحة واحدة على الأقل."
                    else -> {
                        message = ""
                        vm.submitTranscript(
                            TranscriptDraft(
                                lessonId = lesson.id,
                                text = text,
                                bookTitle = bookTitle,
                                sourceRef = sourceRef,
                                note = note,
                                submitterName = name,
                                images = images.toList(),
                            ),
                        )
                    }
                }
            },
            enabled = !sending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Text("  جارٍ الإرسال…")
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Text(" إرسال للمراجعة")
            }
        }
    }

    if (done) {
        val close = {
            lessonId = ""
            query = ""
            text = ""
            bookTitle = ""
            sourceRef = ""
            note = ""
            images.clear()
            message = ""
            vm.clearTranscriptContribution()
            vm.back()
            Unit
        }
        AlertDialog(
            onDismissRequest = close,
            icon = {
                Icon(
                    Icons.Filled.CheckCircleOutline,
                    contentDescription = null,
                    tint = GreenBrand,
                    modifier = Modifier.size(40.dp),
                )
            },
            title = { Text("وصلت مساهمتك") },
            text = {
                Text(
                    "سيراجع المشرفون النص وتصلك النتيجة إشعاراً.\nتابع حالتها من «مساهماتي».",
                    textAlign = TextAlign.Center,
                )
            },
            confirmButton = { TextButton(onClick = close) { Text("حسناً") } },
        )
    }
}
