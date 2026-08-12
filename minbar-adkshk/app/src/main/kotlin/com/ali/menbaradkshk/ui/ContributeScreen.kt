@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ali.menbaradkshk.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.data.TranscriptExtras
import com.ali.menbaradkshk.util.AudioMerger
import com.ali.menbaradkshk.util.smartTitleFromFileName

data class PickedFile(val uri: Uri, val name: String)

/// معرّفات الحقول المطلوبة — تُستعمل لتمييز الحقل الناقص بصريّاً عند محاولة الإرسال.
private const val FIELD_FILES = "files"
private const val FIELD_TITLE = "title"
private const val FIELD_CATEGORY = "category"
private const val FIELD_SUBCATEGORY = "subcategory"
private const val FIELD_NAME = "name"

/// حافظ قائمة الملفات المختارة عبر التدوير: uri + الاسم لكل ملف بالتسلسل.
private val pickedFilesSaver = listSaver<SnapshotStateList<PickedFile>, String>(
    save = { list -> list.flatMap { listOf(it.uri.toString(), it.name) } },
    restore = { flat ->
        mutableStateListOf<PickedFile>().apply {
            flat.chunked(2).forEach { pair ->
                if (pair.size == 2) add(PickedFile(Uri.parse(pair[0]), pair[1]))
            }
        }
    },
)

/** حافظ Uri للصور عبر التدوير والتنقل المحفوظ؛ الملفات نفسها في كاش التطبيق. */
internal val uriStateListSaver = listSaver<SnapshotStateList<Uri>, String>(
    save = { list -> list.map(Uri::toString) },
    restore = { saved ->
        mutableStateListOf<Uri>().apply { saved.forEach { add(Uri.parse(it)) } }
    },
)

/// 📤 «شارك درساً» — النقل الأمين لـ contribute_screen.dart:
/// عدّة ملفات MP3 تُدمج محلياً بالترتيب المختار في درس واحد قبل الرفع.
@Composable
fun ContributeScreen(vm: AppViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val content by vm.content.state.collectAsState()
    val contribution by vm.contribution.collectAsState()

    val files = rememberSaveable(saver = pickedFilesSaver) { mutableStateListOf<PickedFile>() }
    var title by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(vm.store.submitterName()) }
    var note by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf("") }
    var subcategoryId by rememberSaveable { mutableStateOf("") }
    var rightsConfirmed by rememberSaveable { mutableStateOf(false) }
    var policyAccepted by rememberSaveable { mutableStateOf(false) }
    var policyDialog by rememberSaveable { mutableStateOf(false) }
    var formError by rememberSaveable { mutableStateOf("") }

    // «النص المشروح» الاختياري: يُرفق مع المساهمة نفسها ويُنشر مع الدرس
    // عند الاعتماد — من أحبّ أضافه، ومن لم يرد فلا شيء يلزمه به.
    var transcriptOpen by rememberSaveable { mutableStateOf(false) }
    var transcriptText by rememberSaveable { mutableStateOf("") }
    var transcriptBookTitle by rememberSaveable { mutableStateOf("") }
    var transcriptSourceRef by rememberSaveable { mutableStateOf("") }
    val transcriptImages = rememberSaveable(saver = uriStateListSaver) {
        mutableStateListOf<Uri>()
    }

    // الحقل الناقص الذي أوقف آخر محاولة إرسال — يُميَّز بالأحمر ويُصفَّر بمجرّد تعديله.
    var missingField by rememberSaveable { mutableStateOf("") }

    // حالة الرفع تأتي من الـViewModel كي لا يلغيها التدوير.
    val submitting = contribution.submitting
    val merging = contribution.merging
    val progress = contribution.progress
    val error = formError.ifEmpty { contribution.error }

    // عند فتح الشاشة من جديد تُنظَّف نتيجة رفعٍ سابق (لا تُنظَّف عند التدوير).
    var sessionStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!sessionStarted) {
            sessionStarted = true
            if (!vm.contribution.value.submitting) vm.clearContributionState()
        }
    }

    /// يرفع التمييز الأحمر ورسالة النقص بمجرّد أن يعالج المستخدم الحقل المعنيّ.
    fun clearMissing(field: String) {
        if (missingField == field) {
            missingField = ""
            formError = ""
        }
    }

    /// يضمّ ملفات جديدة (من محدّد النظام أو من مشاركة خارجية) إلى القائمة،
    /// مع فحص شروط الدمج والحدّ الأقصى واقتراح العنوان من اسم أول ملف.
    fun acceptFiles(picked: List<PickedFile>) {
        if (picked.isEmpty()) return
        // وصلت ملفات فعلاً: يُرفع تمييز «لم تختر ملفاً» قبل أي رسالة دمج لاحقة.
        if (missingField == FIELD_FILES) missingField = ""
        val existing = files.map { it.uri }.toSet()
        val combined = files + picked.filter { it.uri !in existing }

        // قيد «MP3 فقط» أُلغي نهائياً: الدمج صار يقبل أي صيغ (فكّ إلى PCM
        // ثم إعادة ترميز AAC/M4A عند اختلافها) — لا عبء تحويل على المستخدم.
        formError = if (combined.size > AudioMerger.maxFiles) {
            "الحد الأقصى ${AudioMerger.maxFiles} ملفات للدرس الواحد — أُبقي أولها."
        } else {
            ""
        }
        files.clear()
        files.addAll(combined.take(AudioMerger.maxFiles))
        if (title.trim().isEmpty() && files.isNotEmpty()) {
            title = smartTitleFromFileName(files.first().name)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        acceptFiles(
            uris.map { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                PickedFile(uri, displayNameOf(context, uri))
            },
        )
    }

    // ملفات وصلت من تطبيق خارجي عبر «المشاركة»: تُدرج فور جاهزيتها،
    // وتُؤجَّل إن كان رفعٌ جارياً حتى ينتهي فلا يُمَسّ ما يُرفع الآن.
    val shared by vm.sharedAudio.collectAsState()
    LaunchedEffect(shared, submitting) {
        if (submitting) return@LaunchedEffect
        when {
            shared.files.isNotEmpty() -> {
                // مشاركة جديدة بعد رفعٍ ناجح تبدأ نموذجاً نظيفاً بدل التراكم.
                if (contribution.done) {
                    vm.clearContributionState()
                    files.clear()
                    title = ""
                }
                acceptFiles(shared.files)
                vm.consumeSharedAudio()
            }

            shared.error.isNotEmpty() -> {
                formError = shared.error
                vm.consumeSharedAudio()
            }
        }
    }

    val categories = content.categories
    val category = content.categoryById[categoryId]
    val subcategory = content.subcategoryById[subcategoryId]
    val subsForCategory = category?.let { chosen ->
        content.subcategories.filter { it.categoryId == chosen.id }
    }.orEmpty()

    /// أوّل حقل ناقص بترتيب منطقي (الملف ← العنوان ← القسم ← القسم الفرعي)
    /// مع الرسالة التي تسمّيه بعينه. الإلزامي هو ما تُلزم به اللوحة نفسها
    /// فقط؛ الاسم والملاحظة والإقرارات والنص المشروح كلها اختيارية.
    fun firstMissing(): Pair<String, String>? = when {
        files.isEmpty() -> FIELD_FILES to "اختر ملفاً صوتياً أولاً."
        title.trim().length < 3 -> FIELD_TITLE to "اكتب عنوان الدرس (٣ أحرف على الأقل)."
        category == null -> FIELD_CATEGORY to "اختر القسم الرئيسي."
        subcategory == null && subsForCategory.isEmpty() ->
            FIELD_SUBCATEGORY to
                "لا توجد أقسام فرعية في «${category?.name.orEmpty()}» — اختر قسماً رئيسياً آخر."
        subcategory == null -> FIELD_SUBCATEGORY to "اختر القسم الفرعي."
        else -> null
    }

    fun submit() {
        // الزرّ لا يُعطَّل إلا أثناء الرفع؛ النقص يُشرح ولا يُخرِس الزرّ.
        if (submitting) return
        val missing = firstMissing()
        if (missing != null) {
            missingField = missing.first
            formError = missing.second
            return
        }
        val cat = category ?: return
        val sub = subcategory ?: return
        missingField = ""
        formError = ""
        vm.submitContribution(
            files = files.toList(),
            title = title,
            category = cat,
            subcategory = sub,
            submitterName = name,
            note = note,
            // كان الإقراران يضيعان هنا (لا يُمرَّران) فيصلان المشرف false دائماً
            // مهما اختار المستخدم — الآن يُنقلان كما اختارهما فعلاً.
            rightsConfirmed = rightsConfirmed,
            contentPolicyAccepted = policyAccepted,
            transcript = TranscriptExtras(
                text = transcriptText,
                bookTitle = transcriptBookTitle,
                sourceRef = transcriptSourceRef,
                images = transcriptImages.toList(),
            ),
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    RoundedCornerShape(12.dp),
                )
                .padding(12.dp),
        ) {
            Text(
                "مساهمتك تُعرض على المشرفين قبل النشر، ويصلك إشعار بالنتيجة. يُنشر الدرس ضمن أقسام التطبيق الحقيقية.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { picker.launch(arrayOf("audio/*")) },
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                if (files.isEmpty()) Icons.Filled.Audiotrack else Icons.AutoMirrored.Filled.PlaylistAdd,
                contentDescription = null,
            )
            Text(
                if (files.isEmpty()) " اختر ملفاً صوتياً (أو عدّة ملفات لدمجها)"
                else " إضافة ملفات أخرى (${files.size}/${AudioMerger.maxFiles})",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (missingField == FIELD_FILES) {
            Spacer(Modifier.height(6.dp))
            Text(
                "الملف الصوتي مطلوب — اضغط الزر أعلاه لاختياره.",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (shared.preparing) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                "جارٍ تجهيز الملفات المشارَكة…",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (files.size > 1) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(10.dp),
            ) {
                Text(
                    "ستُدمج ${files.size} ملفات بالترتيب أدناه في درس واحد متصل — استخدم الأسهم لإعادة الترتيب.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        files.forEachIndexed { index, file ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(26.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(8.dp))
                Text(file.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(
                    enabled = !submitting,
                    onClick = { files.removeAt(index) },
                ) { Icon(Icons.Filled.Close, "إزالة", Modifier.size(18.dp)) }
                if (files.size > 1) {
                    IconButton(
                        enabled = index > 0 && !submitting,
                        onClick = {
                            val item = files.removeAt(index)
                            files.add(index - 1, item)
                        },
                    ) { Icon(Icons.Filled.KeyboardArrowUp, "أعلى") }
                    IconButton(
                        enabled = index < files.lastIndex && !submitting,
                        onClick = {
                            val item = files.removeAt(index)
                            files.add(index + 1, item)
                        },
                    ) { Icon(Icons.Filled.KeyboardArrowDown, "أسفل") }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = title,
            onValueChange = {
                title = it.take(120)
                clearMissing(FIELD_TITLE)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("عنوان الدرس") },
            isError = missingField == FIELD_TITLE,
            enabled = !submitting,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.take(50)
                clearMissing(FIELD_NAME)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("اسمك (اختياري — يظهر للمشرفين)") },
            isError = missingField == FIELD_NAME,
            enabled = !submitting,
        )
        Spacer(Modifier.height(8.dp))
        var categoryMenu by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = categoryMenu, onExpandedChange = { categoryMenu = it }) {
            OutlinedTextField(
                value = category?.name.orEmpty(),
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                label = { Text("القسم الرئيسي") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryMenu) },
                isError = missingField == FIELD_CATEGORY,
                enabled = !submitting,
            )
            ExposedDropdownMenu(
                expanded = categoryMenu,
                onDismissRequest = { categoryMenu = false },
            ) {
                categories.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.name) },
                        onClick = {
                            categoryId = item.id
                            subcategoryId = ""
                            categoryMenu = false
                            clearMissing(FIELD_CATEGORY)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        var subcategoryMenu by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = subcategoryMenu, onExpandedChange = { subcategoryMenu = it }) {
            OutlinedTextField(
                value = subcategory?.name.orEmpty(),
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                label = { Text("القسم الفرعي") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(subcategoryMenu) },
                isError = missingField == FIELD_SUBCATEGORY,
                enabled = category != null && !submitting,
            )
            ExposedDropdownMenu(
                expanded = subcategoryMenu,
                onDismissRequest = { subcategoryMenu = false },
            ) {
                subsForCategory.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.name) },
                        onClick = {
                            subcategoryId = item.id
                            subcategoryMenu = false
                            clearMissing(FIELD_SUBCATEGORY)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(300) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ملاحظة للمشرفين (اختياري)") },
            minLines = 2,
            enabled = !submitting,
        )
        Spacer(Modifier.height(12.dp))

        // 📖 «النص المشروح» الاختياري — نفس مكوّنات ميزة النص في المشغّل.
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    GreenBrand.copy(alpha = 0.07f),
                    RoundedCornerShape(12.dp),
                ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { transcriptOpen = !transcriptOpen },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = GreenBrand,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "النص المشروح (اختياري)",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "أرفق نص المقطع من الكتاب أو صورة صفحته — يُنشر مع الدرس عند اعتماده.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        if (transcriptOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                    )
                }
                if (transcriptOpen) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = transcriptBookTitle,
                        onValueChange = { transcriptBookTitle = it.take(200) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("اسم الكتاب/المتن (اختياري)") },
                        enabled = !submitting,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = transcriptSourceRef,
                        onValueChange = { transcriptSourceRef = it.take(300) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("المقطع: من … إلى … (اختياري)") },
                        enabled = !submitting,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = transcriptText,
                        onValueChange = {
                            transcriptText = it.take(
                                com.ali.menbaradkshk.data.TranscriptRepository.MAX_TEXT_CHARS,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("النص الأصلي المشروح") },
                        minLines = 4,
                        maxLines = 10,
                        enabled = !submitting,
                    )
                    Spacer(Modifier.height(8.dp))
                    TranscriptImagesEditor(
                        images = transcriptImages,
                        enabled = !submitting,
                        onError = { formError = it },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // إقرارات لا تمنع الإرسال: المشرفون يتحقّقون بأنفسهم والقرار النهائي لهم.
        Text(
            "إقرارات اختيارية",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "إقرارك يساعدنا، لكنه ليس شرطاً للإرسال — كل درس يراجعه المشرفون " +
                "ويتحقّقون منه بأنفسهم قبل النشر، والقرار النهائي لهم.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(rightsConfirmed, { rightsConfirmed = it }, enabled = !submitting)
            Column {
                Text("أملك حق مشاركة هذا التسجيل")
                Text(
                    "أؤكد أن التسجيل لي أو لدي إذن صريح بنشره في منبر.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(policyAccepted, { policyAccepted = it }, enabled = !submitting)
            Column {
                Text("أوافق على ضوابط المحتوى والمراجعة")
                Text(
                    "لا حقوق منتهكة، ولا كراهية أو تحريض أو محتوى غير قانوني، ويحق للمشرفين الرفض أو التعديل قبل النشر.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { policyDialog = true }) {
                Icon(Icons.Filled.Policy, null)
                Text(" قراءة الضوابط")
            }
        }
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                error,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (submitting) {
            if (merging || progress <= 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (merging) "جارٍ دمج الملفات في مقطع واحد…" else "جارٍ الرفع… $progress%",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
        }
        FilledTonalButton(
            onClick = ::submit,
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, null)
            Text(" إرسال للمراجعة")
        }
    }

    if (policyDialog) {
        AlertDialog(
            onDismissRequest = { policyDialog = false },
            title = { Text("ضوابط مشاركة الدروس") },
            text = {
                Text(
                    "يُمنع إرسال تسجيل لا تملك حق نشره، أو يتضمن كراهية أو تحريضاً أو تهديداً أو انتهاك خصوصية أو نشاطاً غير قانوني. كل مساهمة تبقى معلّقة حتى يراجعها المشرفون، وقد تُعدّل أو تُرفض مع بيان السبب. عند اكتشاف مخالفة يمكن حذف المساهمة وملفها نهائياً.",
                )
            },
            confirmButton = {
                TextButton(onClick = { policyDialog = false }) { Text("فهمت") }
            },
        )
    }
    if (contribution.done) {
        // ✅ تصفير كامل بعد النجاح: كانت حقول النموذج (rememberSaveable) تبقى
        // كما هي طوال عمر التطبيق (بل وعبر موت العملية، إذ لا يُستدعى
        // removeState لحالة الشاشة)، فيُعاد فتح النموذج معبّأً بنفس الملف
        // فتكفي ضغطة واحدة لإنشاء مساهمة مكرّرة ورفع الملف مرّة أخرى.
        val close = {
            vm.clearContributionState()
            files.clear()
            title = ""
            note = ""
            categoryId = ""
            subcategoryId = ""
            rightsConfirmed = false
            policyAccepted = false
            transcriptOpen = false
            transcriptText = ""
            transcriptBookTitle = ""
            transcriptSourceRef = ""
            transcriptImages.clear()
            formError = ""
            missingField = ""
            // الجلسة تبدأ من جديد عند العودة للشاشة فتُنظَّف نتيجة الرفع السابق.
            sessionStarted = false
            vm.back()
            Unit
        }
        AlertDialog(
            onDismissRequest = close,
            icon = { Icon(Icons.Filled.MarkEmailRead, null, tint = GreenBrand, modifier = Modifier.size(40.dp)) },
            title = { Text("وصل طلبك للمشرفين") },
            text = {
                Text(
                    "سيراجع المشرفون مساهمتك، ويصلك إشعار بالنتيجة: نُشرت كما هي، أو نُشرت بعد تحسينها، أو اعتذار مع السبب.\nتابع حالتها من «مساهماتي».",
                    textAlign = TextAlign.Center,
                )
            },
            confirmButton = {
                TextButton(onClick = close) { Text("حسناً") }
            },
        )
    }
}

/// اسم الملف المعروض (يستعمله أيضاً استقبال «المشاركة» في الـViewModel).
internal fun displayNameOf(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            cursor.getString(index)?.let { return it }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "lesson_audio.mp3"
}
