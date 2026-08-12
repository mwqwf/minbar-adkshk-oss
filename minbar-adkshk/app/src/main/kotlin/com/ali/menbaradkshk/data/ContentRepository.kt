package com.ali.menbaradkshk.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max
import kotlin.random.Random

data class ContentState(
    val categories: List<Category> = emptyList(),
    val subcategories: List<Subcategory> = emptyList(),
    val lessons: List<Lesson> = emptyList(),
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
) {
    // فهارس مبنيّة مرّة واحدة لكل حالة لا عند كل قراءة: كانت `get()` تُعيد
    // بناء الخريطة كاملة في كل استدعاء، وهي تُقرأ داخل قوائم الواجهة لكل عنصر
    // على حدة (اسم القسم لكل بطاقة) — أي مرور كامل على مئات الدروس لكل صفّ في
    // كل إعادة تركيب. الحالة غير قابلة للتغيير فالبناء الكسول آمن.
    val lessonById: Map<String, Lesson> by lazy { lessons.associateBy(Lesson::id) }
    val categoryById: Map<String, Category> by lazy { categories.associateBy(Category::id) }
    val subcategoryById: Map<String, Subcategory> by lazy {
        subcategories.associateBy(Subcategory::id)
    }
}

class ContentRepository private constructor(context: Context) {
    private val store = LocalStore.get(context)
    /// تفضيلات خاصّة بالمستودع وحده (علامات المسبار، إخفاء عناصر السجل،
    /// ترتيب قوائم التشغيل) — لا تُخلط بمخزن التطبيق العام.
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val functions by lazy { FirebaseFunctions.getInstance() }
    private val _state = MutableStateFlow(
        ContentState(
            categories = store.categories(),
            subcategories = store.subcategories(),
            lessons = mergeDurations(store.lessons()).filter(Lesson::isPublished),
            loading = store.categories().isEmpty() && store.lessons().isEmpty(),
        ),
    )
    val state: StateFlow<ContentState> = _state.asStateFlow()

    /// نطاق خاص بالمستودع: التحديث الصريح لا يُلغى بمغادرة الشاشة، فلا تبقى
    /// حالة «جارٍ التحديث» عالقة إن انصرف المستخدم أثناء السحب-للتحديث.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var deepJob: Job? = null

    /// تحديث كامل صريح يتخطّى المسبار (سحب-للتحديث و«إعادة المحاولة»).
    fun requestDeepRefresh() {
        if (deepJob?.isActive == true) return
        deepJob = scope.launch { refresh(force = true, deep = true) }
    }

    /**
     * مزامنة المحتوى.
     *
     * «الطزاجة الفوريّة عند الفتح» قرار منتج مقصود ويبقى: كل عودة للتطبيق
     * تستدعي هذه الدالة بـ[force]=true. الجديد أنّها لم تعد تُنزّل مجموعة
     * الدروس كاملة في كل عودة (حوار صلاحية، تدوير، رجوع من الكاميرا…): يسبقها
     * **مسبار رخيص** — ثلاثة عدّادات تجميعيّة `count()` واستعلام وثيقة واحدة
     * لأحدث `updatedAt` — فإن طابق المخزَّن لم يُجلب شيء إطلاقاً.
     *
     * [deep] يتخطّى المسبار ويجلب كل شيء (سحب-للتحديث اليدوي).
     */
    suspend fun refresh(force: Boolean = false, deep: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val hasCache = _state.value.lessons.isNotEmpty()
        if (!force && now - store.lastSyncMs() < SYNC_INTERVAL_MS && hasCache) {
            return@withContext
        }
        // المسبار لا يُستعمل إلا مع كاش موجود وعلامات محفوظة من مزامنة سابقة؛
        // وأوّل تشغيل يجلب كل شيء كالمعتاد.
        if (!deep && hasCache) {
            val stored = storedMarks()
            if (stored != null) {
                // حدّ أدنى بين مسبارين: عودات متلاحقة لا تُكلّف شيئاً.
                if (now - lastProbeMs() < PROBE_INTERVAL_MS) return@withContext
                val server = probe()
                setLastProbeMs(now)
                // فشل المسبار (انقطاع/رفض) يسقط إلى الجلب الكامل كما كان.
                if (server != null && server == stored) return@withContext
            }
        }
        _state.value = _state.value.copy(syncing = true, error = null)
        runCatching {
            coroutineScope {
                val categoriesJob = async {
                    db.collection("categories").get().await().documents.map { document ->
                        Category.fromMap(document.id, document.data.orEmpty())
                    }
                }
                val subcategoriesJob = async {
                    db.collection("subcategories").get().await().documents.map { document ->
                        Subcategory.fromMap(document.id, document.data.orEmpty())
                    }
                }
                val newest = async { newestUpdatedMs() }

                val categories = categoriesJob.await()
                val subcategories = subcategoriesJob.await()
                // 🚀 أوّل تثبيت: الأقسام وثائق قليلة تصل في جزء من الثانية،
                // بينما الدروس مئات الوثائق قد تستغرق عشرات الثواني على شبكة
                // ضعيفة. كنّا ننتظرها كلّها قبل رسم أي شيء فتبقى الشاشة
                // فارغة. الآن تُرسم المكتبة فور وصول الأقسام، وتُملأ الدروس
                // تباعاً — المستخدم يرى تقدّماً مستمرّاً لا انتظاراً صامتاً.
                if (!hasCache) {
                    _state.value = _state.value.copy(
                        categories = categories,
                        subcategories = subcategories,
                        loading = false,
                        syncing = true,
                    )
                }

                // الدروس على صفحات: كل صفحة تُرسم فور وصولها بدل دفعة واحدة.
                val lessons = fetchLessonsPaged { page ->
                    if (!hasCache) {
                        _state.value = _state.value.copy(
                            lessons = mergeDurations(page.filter(Lesson::isPublished)),
                            loading = false,
                            syncing = true,
                        )
                    }
                }
                Snapshot(
                    categories = categories,
                    subcategories = subcategories,
                    lessons = lessons,
                    maxUpdatedMs = newest.await(),
                )
            }
        }.onSuccess { snapshot ->
            val categories = snapshot.categories
            val subcategories = snapshot.subcategories
            // الترشيح محليّ لأن الخادم يخزّن الدروس المجدولة في نفس المجموعة.
            val lessons = snapshot.lessons.filter(Lesson::isPublished)
            store.setCategories(categories)
            store.setSubcategories(subcategories)
            store.setLessons(lessons)
            store.setLastSyncMs(now)
            // علامات المسبار تُحفظ بأعداد **الخادم** (قبل الترشيح) كي تُقارن بها.
            saveMarks(
                ProbeMarks(
                    categories = categories.size,
                    subcategories = subcategories.size,
                    lessons = snapshot.lessons.size,
                    maxUpdatedMs = snapshot.maxUpdatedMs,
                ),
            )
            setLastProbeMs(now)
            // تنظيف الملفات اليتيمة لدروس أُزيلت من الخادم.
            store.pruneDownloads(lessons.map(Lesson::id).toSet())
            _state.value = ContentState(
                categories = categories,
                subcategories = subcategories,
                lessons = mergeDurations(lessons),
                loading = false,
                syncing = false,
            )
        }.onFailure { failure ->
            // ❗ runCatching يلتقط الإلغاء أيضاً: مغادرة التطبيق أثناء المزامنة
            // كانت تُسجَّل «فشلاً» في المستودع المفرد الباقي بعد الشاشة، فتبقى
            // راية offline ورسالة «نسخة محفوظة» ظاهرتين عند إعادة الفتح بلا سبب.
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            val cached = _state.value
            _state.value = cached.copy(
                loading = false,
                syncing = false,
                offline = true,
                error = if (cached.lessons.isEmpty()) {
                    "تعذّر الاتصال ولا توجد بيانات محفوظة بعد."
                } else {
                    "أنت تستعرض نسخة محفوظة على الجهاز."
                },
            )
        }
    }

    fun refreshPersonalization() {
        _state.value = _state.value.copy(lessons = mergeDurations(_state.value.lessons))
    }

    // ------------------------------------------------------------------
    // المسبار الرخيص (لا يجلب وثائق: ثلاثة عدّادات + وثيقة واحدة)
    // ------------------------------------------------------------------

    /// نتيجة الجلب الكامل قبل الترشيح — تُشتقّ منها علامات المسبار.
    private data class Snapshot(
        val categories: List<Category>,
        val subcategories: List<Subcategory>,
        val lessons: List<Lesson>,
        val maxUpdatedMs: Long,
    )

    /// بصمة حالة الخادم: أعداد المجموعات الثلاث + أحدث طابع تعديل.
    private data class ProbeMarks(
        val categories: Int,
        val subcategories: Int,
        val lessons: Int,
        val maxUpdatedMs: Long,
    )

    /**
     * يجلب الدروس على صفحات مرتَّبة بمعرّف الوثيقة، ويُبلّغ [onPage] بكل ما
     * تجمّع بعد كل صفحة.
     *
     * الترتيب بـ`__name__` مقصود: لا يحتاج فهرساً ولا يتأثّر بغياب حقل
     * `createdAt` عن الوثائق القديمة المغلَّفة، والترقيم به مستقرّ فلا تتكرّر
     * وثيقة ولا تسقط أخرى بين صفحتين.
     */
    private suspend fun fetchLessonsPaged(onPage: (List<Lesson>) -> Unit): List<Lesson> {
        val all = mutableListOf<Lesson>()
        var last: com.google.firebase.firestore.DocumentSnapshot? = null
        while (true) {
            var query = db.collection("lessons")
                .orderBy(com.google.firebase.firestore.FieldPath.documentId())
                .limit(LESSONS_PAGE_SIZE)
            last?.let { query = query.startAfter(it) }
            val snapshot = query.get().await()
            if (snapshot.isEmpty) break
            all += snapshot.documents.map { document ->
                Lesson.fromMap(document.id, document.data.orEmpty())
            }
            onPage(all.toList())
            if (snapshot.size() < LESSONS_PAGE_SIZE) break
            last = snapshot.documents.last()
        }
        return all
    }

    private suspend fun probe(): ProbeMarks? = runCatching {
        coroutineScope {
            val categories = async { countOf("categories") }
            val subcategories = async { countOf("subcategories") }
            val lessons = async { countOf("lessons") }
            val newest = async { newestUpdatedMs() }
            ProbeMarks(
                categories = categories.await(),
                subcategories = subcategories.await(),
                lessons = lessons.await(),
                maxUpdatedMs = newest.await(),
            )
        }
    }.getOrNull()

    private suspend fun countOf(collection: String): Int =
        db.collection(collection).count().get(AggregateSource.SERVER).await().count.toInt()

    /// أحدث `updatedAt` في مجموعة الدروس (وثيقة واحدة). الوثائق التي لا تحمل
    /// الحقل لا تدخل الاستعلام أصلاً، فغيابه كلّياً يعني صفراً ثابتاً ولا يضرّ.
    private suspend fun newestUpdatedMs(): Long = runCatching {
        db.collection("lessons")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .await()
            .documents
            .firstOrNull()
            ?.get("updatedAt")
            .timeMillis()
    }.getOrDefault(0L)

    private fun storedMarks(): ProbeMarks? {
        if (!prefs.contains(KEY_MARK_LESSONS)) return null
        return ProbeMarks(
            categories = prefs.getInt(KEY_MARK_CATEGORIES, -1),
            subcategories = prefs.getInt(KEY_MARK_SUBCATEGORIES, -1),
            lessons = prefs.getInt(KEY_MARK_LESSONS, -1),
            maxUpdatedMs = prefs.getLong(KEY_MARK_UPDATED, 0L),
        )
    }

    private fun saveMarks(marks: ProbeMarks) = prefs.edit()
        .putInt(KEY_MARK_CATEGORIES, marks.categories)
        .putInt(KEY_MARK_SUBCATEGORIES, marks.subcategories)
        .putInt(KEY_MARK_LESSONS, marks.lessons)
        .putLong(KEY_MARK_UPDATED, marks.maxUpdatedMs)
        .apply()

    private fun lastProbeMs(): Long = prefs.getLong(KEY_LAST_PROBE, 0L)

    private fun setLastProbeMs(value: Long) =
        prefs.edit().putLong(KEY_LAST_PROBE, value).apply()

    fun lessonsForSubcategory(subcategoryId: String): List<Lesson> =
        _state.value.lessons
            .filter { it.subcategoryId == subcategoryId }
            .sortedBy(Lesson::createdAtMs)

    fun subcategoriesForCategory(categoryId: String): List<Subcategory> =
        _state.value.subcategories
            .filter { it.categoryId == categoryId }
            .sortedByDescending(Subcategory::createdAtMs)

    /// كل دروس قسم رئيسي (عبر كل أقسامه الفرعية) — للتحميل الجماعي.
    fun lessonsForCategory(categoryId: String): List<Lesson> =
        _state.value.lessons
            .filter { it.categoryId == categoryId }
            .sortedBy(Lesson::createdAtMs)

    fun newest(limit: Int = 15): List<Lesson> =
        withAudio().sortedByDescending(Lesson::createdAtMs).take(limit)

    /**
     * «مختارات المنبر» — التمييز صار مؤقّتاً: ما انقضت مدّته يسقط هنا فوراً
     * دون انتظار تنظيف الخادم، فلا يظهر للمستخدم درس انتهى تمييزه.
     */
    fun featured(limit: Int = 12): List<Lesson> {
        val now = System.currentTimeMillis()
        return withAudio()
            .filter { it.featured && (it.featuredUntilMs <= 0L || it.featuredUntilMs > now) }
            .sortedByDescending(Lesson::createdAtMs)
            .take(limit)
    }

    fun mostListened(limit: Int = 15): List<Lesson> =
        withAudio().filter { it.views > 0L }
            .sortedWith(compareByDescending<Lesson> { it.views }.thenByDescending { it.createdAtMs })
            .take(limit)

    fun continueListening(): List<Lesson> {
        // الرئيسية تُفتح يومياً، فختم السجل هنا يجعل رؤوس «اليوم/أمس» دقيقة.
        touchHistoryStamps()
        val byId = _state.value.lessonById
        val completed = store.completedIds().toSet()
        val positions = store.positions()
        return store.recentPlayedIds().mapNotNull { id ->
            byId[id]?.takeIf { id !in completed && (positions[id] ?: 0L) > 3_000L }
        }
    }

    fun unfinished(): List<Lesson> {
        val completed = store.completedIds().toSet()
        val positions = store.positions()
        return _state.value.lessons.filter {
            it.id !in completed && (positions[it.id] ?: 0L) > 3_000L
        }.sortedByDescending { positions[it.id] ?: 0L }
    }

    fun favorites(): List<Lesson> {
        val byId = _state.value.lessonById
        return store.favoriteIds().mapNotNull(byId::get)
    }

    /// هل لدى المستخدم أي سجلّ يُبنى عليه التخصيص؟ عند غيابه تكون «مقترح لك»
    /// نسخة طبق الأصل من «الأحدث»، فتُستبدل في الرئيسية بـ«ابدأ من هنا».
    fun hasHistory(): Boolean =
        store.subcategoryVisits().isNotEmpty() ||
            store.categoryVisits().isNotEmpty() ||
            store.playCounts().isNotEmpty()

    // ---- سجلّ الاستماع: عناصر مخفيّة بالسحب ----

    /// السجلّ المعروض: ترتيب الاستماع نفسه، منقوصاً منه ما أخفاه المستخدم.
    /// أي إعادة تشغيل للدرس تُعيده إلى السجلّ تلقائياً (عدّاد التشغيل تجاوز
    /// قيمته لحظة الإخفاء)، فلا يختفي درس يستمع إليه المستخدم من جديد.
    fun historyLessons(): List<Lesson> {
        touchHistoryStamps()
        val hidden = hiddenHistory()
        val byId = _state.value.lessonById
        val counts = store.playCounts()
        val revived = mutableListOf<String>()
        val items = store.recentPlayedIds().mapNotNull { id ->
            if (hidden.has(id)) {
                if ((counts[id] ?: 0L) > hidden.optLong(id)) {
                    revived += id
                } else {
                    return@mapNotNull null
                }
            }
            byId[id]
        }
        if (revived.isNotEmpty()) {
            revived.forEach { hidden.remove(it) }
            prefs.edit().putString(KEY_HIDDEN_HISTORY, hidden.toString()).apply()
        }
        return items
    }

    /// إخفاء عنصر من السجلّ (سحب للحذف) — محليّ بحت وقابل للنقض بإعادة التشغيل.
    fun hideFromHistory(lessonId: String) {
        if (lessonId.isBlank()) return
        val hidden = hiddenHistory().put(lessonId, store.playCounts()[lessonId] ?: 0L)
        prefs.edit().putString(KEY_HIDDEN_HISTORY, hidden.toString()).apply()
    }

    private fun hiddenHistory(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_HIDDEN_HISTORY, "{}").orEmpty()) }
            .getOrElse { JSONObject() }

    /// طوابع زمنية تقريبيّة لآخر استماع: السجل نفسه لا يحمل تواريخ، فكل معرّف
    /// يظهر فيه لأوّل مرّة — أو ازداد عدّاد تشغيله — يُختم بوقت رؤيته. التطبيق
    /// يُفتح يومياً فالدقّة كافية تماماً لرؤوس «اليوم/أمس/هذا الأسبوع».
    private fun touchHistoryStamps() {
        val recent = store.recentPlayedIds()
        if (recent.isEmpty()) return
        val counts = store.playCounts()
        val root = historyStampsJson()
        val now = System.currentTimeMillis()
        var changed = false
        recent.forEach { id ->
            val entry = root.optJSONObject(id)
            val plays = counts[id] ?: 0L
            if (entry == null || entry.optLong("plays", -1L) != plays) {
                root.put(id, JSONObject().put("at", now).put("plays", plays))
                changed = true
            }
        }
        // ما خرج من السجل لا يُبقى له طابع.
        val valid = recent.toSet()
        val stale = buildList {
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key !in valid) add(key)
            }
        }
        if (stale.isNotEmpty()) {
            stale.forEach { root.remove(it) }
            changed = true
        }
        if (changed) prefs.edit().putString(KEY_HISTORY_STAMPS, root.toString()).apply()
    }

    /// طابع آخر استماع لكل درس في السجل (بالمللي ثانية) — لرؤوس السجل الزمنيّة.
    fun historyStamps(): Map<String, Long> {
        val root = historyStampsJson()
        return buildMap {
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val at = root.optJSONObject(key)?.optLong("at") ?: 0L
                if (at > 0L) put(key, at)
            }
        }
    }

    private fun historyStampsJson(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_HISTORY_STAMPS, "{}").orEmpty()) }
            .getOrElse { JSONObject() }

    // ---- ترتيب يدوي لقوائم التشغيل ----

    /// يعيد دروس القائمة بترتيب المستخدم إن وُجد؛ وما استُجدّ من دروس يبقى في
    /// آخر القائمة بترتيبه الأصلي.
    fun orderedPlaylist(playlistId: String, lessons: List<Lesson>): List<Lesson> {
        val order = playlistOrder(playlistId) ?: return lessons
        val rank = order.withIndex().associate { (index, id) -> id to index }
        val known = lessons.filter { rank.containsKey(it.id) }.sortedBy { rank.getValue(it.id) }
        val fresh = lessons.filterNot { rank.containsKey(it.id) }
        return known + fresh
    }

    /// ينقل عنصراً في القائمة المعروضة ويحفظ الترتيب الجديد.
    fun movePlaylistItem(playlistId: String, orderedIds: List<String>, from: Int, to: Int) {
        if (from == to || from !in orderedIds.indices || to !in orderedIds.indices) return
        val updated = orderedIds.toMutableList()
        updated.add(to, updated.removeAt(from))
        val root = playlistOrders().put(playlistId, JSONArray(updated))
        prefs.edit().putString(KEY_PLAYLIST_ORDER, root.toString()).apply()
    }

    private fun playlistOrder(playlistId: String): List<String>? {
        val array = playlistOrders().optJSONArray(playlistId) ?: return null
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotEmpty)?.let(::add)
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun playlistOrders(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_PLAYLIST_ORDER, "{}").orEmpty()) }
            .getOrElse { JSONObject() }

    fun recommended(limit: Int = 50): List<Lesson> {
        val pool = withAudio()
        val subVisits = store.subcategoryVisits()
        val categoryVisits = store.categoryVisits()
        val playCounts = store.playCounts()
        val completed = store.completedIds().toSet()
        if (subVisits.isEmpty() && categoryVisits.isEmpty() && playCounts.isEmpty()) {
            return pool.sortedByDescending(Lesson::createdAtMs).take(limit)
        }
        fun score(lesson: Lesson): Double =
            (subVisits[lesson.subcategoryId] ?: 0L) * 3.0 +
                (categoryVisits[lesson.categoryId] ?: 0L) * 1.5 +
                lesson.views * 0.05 -
                if ((playCounts[lesson.id] ?: 0L) > 0L) 2.0 else 0.0 -
                if (lesson.id in completed) 4.0 else 0.0
        return pool.sortedWith(
            compareByDescending<Lesson>(::score).thenByDescending(Lesson::createdAtMs),
        ).take(limit)
    }

    fun trending(limit: Int = 15): List<Lesson> {
        val now = System.currentTimeMillis()
        fun score(lesson: Lesson): Double {
            val ageDays = max(0L, (now - lesson.createdAtMs) / 86_400_000L).coerceAtMost(3_650)
            return lesson.views * (0.5 + 1.0 / (1 + ageDays / 30.0))
        }
        return withAudio().filter { it.views > 0L }
            .sortedByDescending(::score)
            .take(limit)
    }

    /// دروس في أقسام فرعية زارها المستخدم ولم يُكملها كلها (استكمل قسمك).
    fun continueSection(limit: Int = 15): List<Lesson> {
        val subVisits = store.subcategoryVisits()
        if (subVisits.isEmpty()) return emptyList()
        val completed = store.completedIds().toSet()
        val positions = store.positions()
        val sortedSubs = subVisits.entries.sortedByDescending { it.value }
        val result = mutableListOf<Lesson>()
        for (entry in sortedSubs) {
            for (lesson in lessonsForSubcategory(entry.key)) {
                if (lesson.id in completed) continue
                if ((positions[lesson.id] ?: 0L) > 0L || subVisits.containsKey(lesson.subcategoryId)) {
                    result += lesson
                    if (result.size >= limit) return result
                }
            }
        }
        return result
    }

    fun randomSectionToday(limit: Int = 12): List<Lesson> {
        val subs = _state.value.subcategories
        if (subs.isEmpty()) return emptyList()
        val now = LocalDate.now()
        val sub = subs[(now.dayOfMonth + now.monthValue * 31) % subs.size]
        return lessonsForSubcategory(sub.id).take(limit)
    }

    fun dailyWard(): Lesson? {
        val pool = withAudio().sortedBy(Lesson::id)
        if (pool.isEmpty()) return null
        val now = LocalDate.now()
        return pool[(now.year * 1_000 + now.monthValue * 40 + now.dayOfMonth) % pool.size]
    }

    fun shortStation(): List<Lesson> = withAudio().filter {
        val duration = if (it.durationMs > 0L) it.durationMs else store.duration(it.id)
        duration in 1L until 10 * 60 * 1_000L
    }.sortedByDescending(Lesson::createdAtMs)

    fun randomStation(): List<Lesson> = withAudio().shuffled(Random(System.currentTimeMillis()))

    fun similarTo(lesson: Lesson, limit: Int = 20): List<Lesson> =
        withAudio().filterNot { it.id == lesson.id }.sortedWith(
            compareBy<Lesson> {
                when {
                    lesson.subcategoryId.isNotBlank() && it.subcategoryId == lesson.subcategoryId -> 0
                    lesson.categoryId.isNotBlank() && it.categoryId == lesson.categoryId -> 1
                    else -> 2
                }
            }.thenByDescending { it.views }.thenByDescending { it.createdAtMs },
        ).take(limit)

    fun seriesProgress(subcategoryId: String): Pair<Int, Int> {
        val items = lessonsForSubcategory(subcategoryId).filter { it.audioUrl.isNotBlank() }
        val completed = store.completedIds().toSet()
        return items.count { it.id in completed } to items.size
    }

    suspend fun incrementView(lessonId: String) {
        if (lessonId.isBlank() || store.isViewCountedToday(lessonId)) return
        runCatching {
            ensureSignedIn()
            functions.getHttpsCallable("incrementLessonView")
                .call(mapOf("lessonId" to lessonId)).await()
        }.onSuccess { store.markViewCounted(lessonId) }
    }

    suspend fun sendFeedback(lessonId: String, type: String, note: String) {
        ensureSignedIn()
        functions.getHttpsCallable("sendFeedback").call(
            mapOf("lessonId" to lessonId, "type" to type, "note" to note.trim()),
        ).await()
    }

    private suspend fun ensureSignedIn() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) auth.signInAnonymously().await()
    }

    private fun withAudio(): List<Lesson> =
        _state.value.lessons.filter { it.audioUrl.isNotBlank() && it.isPublished }

    private fun mergeDurations(items: List<Lesson>): List<Lesson> = items.map { lesson ->
        val local = store.duration(lesson.id)
        if (lesson.durationMs <= 0L && local > 0L) lesson.copy(durationMs = local) else lesson
    }

    companion object {
        private const val SYNC_INTERVAL_MS = 2 * 60 * 1_000L
        /// حدّ أدنى بين مسبارين — يبتلع عودات ON_RESUME المتلاحقة.
        private const val PROBE_INTERVAL_MS = 60 * 1_000L
        private const val PREFS = "content_repo_v1"
        /// حجم صفحة الدروس. 300 وثيقة تصل في زمن معقول حتى على شبكة ضعيفة،
        /// فيرى المستخدم محتوى يتراكم بدل شاشة فارغة حتى اكتمال كل الدروس.
        private const val LESSONS_PAGE_SIZE = 300L

        private const val KEY_MARK_CATEGORIES = "mark_categories"
        private const val KEY_MARK_SUBCATEGORIES = "mark_subcategories"
        private const val KEY_MARK_LESSONS = "mark_lessons"
        private const val KEY_MARK_UPDATED = "mark_updated_ms"
        private const val KEY_LAST_PROBE = "last_probe_ms"
        private const val KEY_HIDDEN_HISTORY = "hidden_history_v1"
        private const val KEY_HISTORY_STAMPS = "history_stamps_v1"
        private const val KEY_PLAYLIST_ORDER = "playlist_order_v1"
        @Volatile private var instance: ContentRepository? = null
        fun get(context: Context): ContentRepository = instance ?: synchronized(this) {
            instance ?: ContentRepository(context.applicationContext).also { instance = it }
        }
    }
}
