package com.ali.menbaradkshk.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.ali.menbaradkshk.data.Lesson
import com.ali.menbaradkshk.data.LessonTranscript
import com.ali.menbaradkshk.data.TranscriptDraft
import com.ali.menbaradkshk.data.TranscriptRepository

/**
 * 📖 «النص المشروح» في شاشة التشغيل: يعرض المتن/المقطع الأصلي الذي تشرحه
 * الصوتية (نصاً وصور صفحات) بعد اعتماده من المشرفين، ويتيح للمستمع
 * المساهمة بالنص أو اقتراح تصحيحه — تمر المساهمة بمراجعة المشرفين قبل
 * الظهور (نفس دورة «شارك درساً»).
 */
@Composable
fun TranscriptSection(vm: AppViewModel, lesson: Lesson) {
    var loading by remember(lesson.id) { mutableStateOf(true) }
    var transcript by remember(lesson.id) { mutableStateOf<LessonTranscript?>(null) }
    // تعذّر الجلب ولا نسخة محفوظة: «لم يُجلب بعد» لا «لا نص لهذا الدرس» —
    // الخلط بينهما كان يعرض دعوة المساهمة لدرسٍ نصُّه موجود ولم يصل فقط.
    var unreachable by remember(lesson.id) { mutableStateOf(false) }
    var retry by remember(lesson.id) { mutableIntStateOf(0) }
    var contributeSheet by rememberSaveable(lesson.id) { mutableStateOf(false) }
    var viewingImage by remember { mutableStateOf("") }

    LaunchedEffect(lesson.id, retry) {
        loading = true
        runCatching { vm.transcripts.fetch(lesson.id) }
            .onSuccess {
                transcript = it
                unreachable = false
            }
            .onFailure {
                transcript = null
                unreachable = true
            }
        loading = false
    }

    if (viewingImage.isNotEmpty()) {
        Dialog(onDismissRequest = { viewingImage = "" }) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black, RoundedCornerShape(12.dp))
                    .clickable { viewingImage = "" },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = viewingImage,
                    contentDescription = "صورة صفحة الكتاب",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (contributeSheet) {
        TranscriptContributeSheet(
            vm = vm,
            lesson = lesson,
            existing = transcript,
            onDismiss = { contributeSheet = false },
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            tint = GreenBrand,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("النص المشروح", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        if (transcript != null) {
            TextButton(onClick = { contributeSheet = true }) {
                Text("اقتراح تصحيح", fontSize = 13.sp)
            }
        }
    }

    when {
        loading -> Box(
            Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = GreenBrand,
            )
        }

        transcript != null -> {
            val t = transcript!!
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    if (t.bookTitle.isNotBlank()) {
                        Text(
                            t.bookTitle,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = GreenBrand,
                        )
                    }
                    if (t.sourceRef.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            t.sourceRef,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (t.text.isNotBlank()) {
                        if (t.bookTitle.isNotBlank() || t.sourceRef.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                        }
                        // النص كاملاً بلا نقر ولا «قراءة المزيد» — تمرير الصفحة
                        // الطبيعي يتكفّل بالطول (قرار المستخدم الصريح).
                        SelectionContainer {
                            Text(
                                t.text,
                                style = MaterialTheme.typography.bodyLarge,
                                lineHeight = 30.sp,
                            )
                        }
                    }
                    // صفحات الكتاب بعرض البطاقة كاملاً وبنسبة أبعادها الطبيعية
                    // (لا قصّ ولا تشويه) — تُقرأ مباشرة دون نقر، والنقر يفتحها
                    // مكبَّرة أكثر للتدقيق.
                    if (t.imageUrls.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        t.imageUrls.forEachIndexed { i, url ->
                            if (i > 0) Spacer(Modifier.height(8.dp))
                            AsyncImage(
                                model = url,
                                contentDescription = "صفحة الكتاب ${i + 1}",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { viewingImage = url },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Verified,
                            contentDescription = null,
                            tint = GreenBrand,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            buildString {
                                append("روجع واعتُمد من المشرفين")
                                if (t.contributorName.isNotBlank()) {
                                    append(" • بمساهمة ${t.contributorName}")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // بلا اتصال ولم يُجلب النص بعد: لا ندعو للمساهمة كأن الدرس بلا نص —
        // فقد يكون له نصٌّ معتمد لم يصل الجهاز أصلاً.
        unreachable -> Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "النص المشروح غير متاح الآن",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "تعذّر الاتصال — إن كان لهذا الدرس نص فسيظهر عند عودة الاتصال.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { retry++ }) {
                    Text("إعادة", fontSize = 13.sp)
                }
            }
        }

        // شريط مدمج لا بطاقة ضخمة: القسم صار أعلى المشغّل، فحالة «لا نص»
        // يجب أن تدعو للمساهمة دون أن تزاحم أدوات التشغيل.
        else -> Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = GreenBrand.copy(alpha = 0.08f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { contributeSheet = true },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.AddPhotoAlternate,
                    contentDescription = null,
                    tint = GreenBrand,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "ساهم بنص هذا الدرس",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = GreenBrand,
                    )
                    Text(
                        "انقل المقطع من الكتاب أو صوّر صفحته — يُنشر بعد مراجعة المشرفين.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * نموذج المساهمة: نص المقطع من الكتاب و/أو صور صفحاته (حتى 4)، مع اسم
 * الكتاب ونطاق المقطع وملاحظة للمشرفين. يُرسل للمراجعة ولا يظهر قبل
 * الاعتماد. عند «اقتراح تصحيح» تُملأ الحقول بالنص المعتمد الحالي.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptContributeSheet(
    vm: AppViewModel,
    lesson: Lesson,
    existing: LessonTranscript?,
    onDismiss: () -> Unit,
) {
    val submission by vm.transcriptContribution.collectAsState()
    var text by rememberSaveable(lesson.id) { mutableStateOf(existing?.text.orEmpty()) }
    var bookTitle by rememberSaveable(lesson.id) {
        mutableStateOf(existing?.bookTitle.orEmpty())
    }
    var sourceRef by rememberSaveable(lesson.id) {
        mutableStateOf(existing?.sourceRef.orEmpty())
    }
    var note by rememberSaveable(lesson.id) { mutableStateOf("") }
    var name by rememberSaveable(lesson.id) { mutableStateOf(vm.store.submitterName()) }
    val images = rememberSaveable(lesson.id, saver = uriStateListSaver) {
        mutableStateListOf<Uri>()
    }
    var message by rememberSaveable(lesson.id) { mutableStateOf("") }
    val ownsSubmission = submission.lessonId == lesson.id
    val sending = submission.submitting
    val progress = if (ownsSubmission) submission.progress else 0
    val done = ownsSubmission && submission.done
    val visibleMessage = message.ifBlank { if (ownsSubmission) submission.error else "" }
    val close = {
        if (!sending) {
            vm.clearTranscriptContribution()
            onDismiss()
        }
    }
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        confirmValueChange = { target ->
            !sending || target != androidx.compose.material3.SheetValue.Hidden
        },
    )

    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                if (existing == null) "المساهمة بالنص المشروح" else "اقتراح تصحيح النص",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "«${lesson.displayTitle}»",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "انقل النص الأصلي من الكتاب الذي تشرحه هذه الصوتية (لا تفريغ " +
                    "كلام الشيخ)، أو أرفق صورة واضحة للصفحة — يراجعها المشرفون " +
                    "قبل النشر.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(14.dp))

            if (done) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Verified,
                        contentDescription = null,
                        tint = GreenBrand,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "وصلت مساهمتك وستُراجع قريباً.\nتابع حالتها من «مساهماتي».",
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = close) { Text("حسناً") }
                }
                return@Column
            }

            OutlinedTextField(
                value = bookTitle,
                onValueChange = { if (it.length <= 200) bookTitle = it },
                label = { Text("اسم الكتاب/المتن (اختياري)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sourceRef,
                onValueChange = { if (it.length <= 300) sourceRef = it },
                label = { Text("المقطع: من … إلى … (اختياري)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= TranscriptRepository.MAX_TEXT_CHARS) text = it },
                label = { Text("النص الأصلي المشروح") },
                minLines = 5,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            TranscriptImagesEditor(
                images = images,
                enabled = !sending,
                onError = { message = it },
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 50) name = it },
                label = { Text("اسمك (يظهر مع النص عند الاعتماد)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= 300) note = it },
                label = { Text("ملاحظة للمشرفين (اختياري)") },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (visibleMessage.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    visibleMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    // الزر لا يُعطَّل؛ النقص يُشرح بعينه (نفس نهج «شارك درساً»).
                    if (text.trim().length < 10 && images.isEmpty()) {
                        message = if (text.isBlank()) {
                            "أضف نص المقطع من الكتاب أو أرفق صورة صفحة واحدة على الأقل."
                        } else {
                            "النص قصير جداً (١٠ أحرف على الأقل) — أكمله أو أرفق صورة الصفحة."
                        }
                        return@Button
                    }
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
                },
                enabled = !sending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (images.isNotEmpty() && progress in 1..99) {
                            "جارٍ رفع الصور… $progress%"
                        } else {
                            "جارٍ الإرسال…"
                        },
                    )
                } else {
                    Text("إرسال للمراجعة")
                }
            }
        }
    }
}
