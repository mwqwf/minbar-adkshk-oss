package com.ali.menbaradkshk.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Native store with a deliberately non-destructive migration from Flutter.
 *
 * Flutter's legacy plugin writes to FlutterSharedPreferences.xml and prefixes
 * every key with "flutter.". We copy those values once into a new file, keep
 * the old file untouched for rollback, and continue to understand Flutter's
 * encoded doubles and string lists.
 */
class LocalStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(NATIVE_FILE, Context.MODE_PRIVATE)
    private val legacy = appContext.getSharedPreferences(FLUTTER_FILE, Context.MODE_PRIVATE)
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    init {
        migrateFromFlutter()
        repairDownloadIndex()
    }

    private fun migrateFromFlutter() {
        if (preferences.getBoolean(MIGRATED_KEY, false)) return
        val editor = preferences.edit()
        var migratedCount = 0
        legacy.all.forEach { (rawKey, rawValue) ->
            if (!rawKey.startsWith(FLUTTER_PREFIX)) return@forEach
            val key = rawKey.removePrefix(FLUTTER_PREFIX)
            if (preferences.contains(key)) return@forEach
            when (rawValue) {
                is Boolean -> editor.putBoolean(key, rawValue)
                is Int -> editor.putLong(key, rawValue.toLong())
                is Long -> editor.putLong(key, rawValue)
                is Float -> editor.putFloat(key, rawValue)
                is String -> editor.putString(key, decodeFlutterStringList(rawValue) ?: rawValue)
                is Set<*> -> editor.putString(
                    key,
                    JSONArray(rawValue.filterIsInstance<String>()).toString(),
                )
                else -> return@forEach
            }
            migratedCount += 1
        }
        editor
            .putBoolean(MIGRATED_KEY, true)
            .putInt(MIGRATED_COUNT_KEY, migratedCount)
            .putLong(MIGRATED_AT_KEY, System.currentTimeMillis())
            .commit()
    }

    private fun decodeFlutterStringList(value: String): String? {
        if (!value.startsWith(JSON_LIST_PREFIX)) return null
        val json = value.removePrefix(JSON_LIST_PREFIX)
        return runCatching { JSONArray(json).toString() }.getOrNull()
    }

    private fun raw(key: String): Any? {
        if (preferences.contains(key)) return preferences.all[key]
        return legacy.all["$FLUTTER_PREFIX$key"]
    }

    private fun string(key: String, default: String = ""): String =
        (raw(key) as? String)?.let { decodeFlutterStringList(it) ?: it } ?: default

    private fun long(key: String, default: Long = 0L): Long = when (val value = raw(key)) {
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: default
        else -> default
    }

    private fun bool(key: String, default: Boolean = false): Boolean = when (val value = raw(key)) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull() ?: default
        else -> default
    }

    private fun double(key: String, default: Double = 0.0): Double = when (val value = raw(key)) {
        is Float -> value.toDouble()
        is Double -> value
        is Number -> value.toDouble()
        is String -> value
            .removePrefix(DOUBLE_PREFIX)
            .toDoubleOrNull()
            ?: default
        else -> default
    }

    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        preferences.edit().apply(block).apply()
        _revision.value += 1L
    }

    private fun jsonArray(key: String): JSONArray =
        runCatching { JSONArray(string(key, "[]")) }.getOrElse { JSONArray() }

    private fun jsonObject(key: String): JSONObject =
        runCatching { JSONObject(string(key, "{}")) }.getOrElse { JSONObject() }

    private fun putJson(key: String, value: Any) = write { putString(key, value.toString()) }

    fun categories(): List<Category> = jsonArray(KEY_CACHE_CATEGORIES).objects(Category::fromJson)
    fun subcategories(): List<Subcategory> =
        jsonArray(KEY_CACHE_SUBCATEGORIES).objects(Subcategory::fromJson)
    fun lessons(): List<Lesson> = jsonArray(KEY_CACHE_LESSONS).objects(Lesson::fromJson)

    fun setCategories(items: List<Category>) =
        putJson(KEY_CACHE_CATEGORIES, JSONArray(items.map(Category::toJson)))
    fun setSubcategories(items: List<Subcategory>) =
        putJson(KEY_CACHE_SUBCATEGORIES, JSONArray(items.map(Subcategory::toJson)))
    fun setLessons(items: List<Lesson>) =
        putJson(KEY_CACHE_LESSONS, JSONArray(items.map(Lesson::toJson)))

    fun lastSyncMs(): Long = long(KEY_LAST_SYNC)
    fun setLastSyncMs(value: Long) = write { putLong(KEY_LAST_SYNC, value) }

    fun downloads(): Map<String, String> = jsonObject(KEY_DOWNLOADS).stringMap()
    fun localAudioPath(lessonId: String): String? =
        downloads()[lessonId]?.takeIf { File(it).isFile }

    fun setDownload(lessonId: String, path: String) {
        val json = jsonObject(KEY_DOWNLOADS).put(lessonId, path)
        putJson(KEY_DOWNLOADS, json)
        trackEvent("download")
    }

    /// يحذف تنزيلات دروس لم تعد موجودة في الفهرس (ملفات يتيمة تستهلك المساحة).
    /// يُستدعى بعد مزامنة ناجحة فقط كي لا تُحذف عند فشل الجلب.
    fun pruneDownloads(validIds: Set<String>): Int {
        val orphans = downloads().filterKeys { it !in validIds }
        orphans.forEach { (id, path) ->
            runCatching { File(path).delete() }
            removeDownload(id)
        }
        return orphans.size
    }

    fun removeDownload(lessonId: String) {
        val json = jsonObject(KEY_DOWNLOADS)
        json.remove(lessonId)
        putJson(KEY_DOWNLOADS, json)
    }

    private fun repairDownloadIndex() {
        val current = downloads()
        if (current.isEmpty()) return
        val valid = current.filterValues { File(it).isFile }
        if (valid.size != current.size) {
            putJson(KEY_DOWNLOADS, JSONObject(valid))
        }
    }

    fun intMap(key: String): Map<String, Long> = jsonObject(key).longMap()
    private fun setIntMap(key: String, value: Map<String, Long>) =
        putJson(key, JSONObject(value))
    fun stringList(key: String): List<String> = jsonArray(key).strings()
    private fun setStringList(key: String, value: List<String>) =
        putJson(key, JSONArray(value))

    fun playCounts(): Map<String, Long> = intMap(KEY_PLAY_COUNTS)
    fun incrementPlayCount(id: String) {
        val values = playCounts().toMutableMap()
        values[id] = (values[id] ?: 0L) + 1L
        setIntMap(KEY_PLAY_COUNTS, values)
    }

    fun recentPlayedIds(): List<String> = stringList(KEY_RECENT)
    fun addRecentPlayed(id: String) {
        val values = recentPlayedIds().toMutableList().apply {
            remove(id)
            add(0, id)
            while (size > 60) removeAt(lastIndex)
        }
        setStringList(KEY_RECENT, values)
    }

    fun categoryVisits(): Map<String, Long> = intMap(KEY_CATEGORY_VISITS)
    fun incrementCategoryVisit(id: String) = incrementMapValue(KEY_CATEGORY_VISITS, id)
    fun subcategoryVisits(): Map<String, Long> = intMap(KEY_SUBCATEGORY_VISITS)
    fun incrementSubcategoryVisit(id: String) = incrementMapValue(KEY_SUBCATEGORY_VISITS, id)

    private fun incrementMapValue(key: String, id: String) {
        if (id.isBlank()) return
        val values = intMap(key).toMutableMap()
        values[id] = (values[id] ?: 0L) + 1L
        setIntMap(key, values)
    }

    fun positions(): Map<String, Long> = intMap(KEY_POSITIONS)
    fun position(lessonId: String): Long = positions()[lessonId] ?: 0L
    fun setPosition(lessonId: String, milliseconds: Long) {
        val values = positions().toMutableMap()
        if (milliseconds <= 0L) values.remove(lessonId) else values[lessonId] = milliseconds
        setIntMap(KEY_POSITIONS, values)
    }

    fun completedIds(): List<String> = stringList(KEY_COMPLETED)
    fun markCompleted(lessonId: String) {
        val values = completedIds().toMutableList()
        if (lessonId !in values) {
            values += lessonId
            setStringList(KEY_COMPLETED, values)
        }
        setPosition(lessonId, 0L)
        recordDailyListen()
    }

    fun durations(): Map<String, Long> = intMap(KEY_DURATIONS)
    fun duration(lessonId: String): Long = durations()[lessonId] ?: 0L
    fun setDuration(lessonId: String, milliseconds: Long) {
        if (lessonId.isBlank() || milliseconds <= 0L) return
        val values = durations().toMutableMap()
        values[lessonId] = milliseconds
        setIntMap(KEY_DURATIONS, values)
    }

    /// قراءة فقط: العلامة تُكتب بـ[markViewCounted] بعد نجاح الكتابة للخادم فقط،
    /// كي لا تضيع المشاهدة نهائياً عند فشل النداء (الخادم يمنع التكرار بنفسه).
    fun isViewCountedToday(lessonId: String): Boolean {
        if (lessonId.isBlank()) return false
        return jsonObject(KEY_VIEW_COUNTED).optString(lessonId) == todayKey()
    }

    fun markViewCounted(lessonId: String) {
        if (lessonId.isBlank()) return
        putJson(KEY_VIEW_COUNTED, jsonObject(KEY_VIEW_COUNTED).put(lessonId, todayKey()))
    }

    fun favoriteIds(): List<String> = stringList(KEY_FAVORITES)
    fun isFavorite(lessonId: String): Boolean = lessonId in favoriteIds()
    fun toggleFavorite(lessonId: String) {
        val values = favoriteIds().toMutableList()
        if (!values.remove(lessonId)) values.add(0, lessonId)
        setStringList(KEY_FAVORITES, values)
    }

    fun searchHistory(): List<String> = stringList(KEY_SEARCH_HISTORY)
    fun addSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        val values = searchHistory().toMutableList().apply {
            remove(trimmed)
            add(0, trimmed)
            while (size > 20) removeAt(lastIndex)
        }
        setStringList(KEY_SEARCH_HISTORY, values)
    }
    fun clearSearchHistory() = write { remove(KEY_SEARCH_HISTORY) }

    fun playlists(): List<Playlist> = jsonArray(KEY_PLAYLISTS).objects(Playlist::fromJson)
    private fun setPlaylists(items: List<Playlist>) =
        putJson(KEY_PLAYLISTS, JSONArray(items.map(Playlist::toJson)))

    fun createPlaylist(name: String): Playlist {
        val playlist = Playlist(
            id = "pl_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
            name = name.trim().ifEmpty { "قائمة" },
            lessonIds = emptyList(),
            createdAtMs = System.currentTimeMillis(),
        )
        setPlaylists(listOf(playlist) + playlists())
        return playlist
    }

    fun deletePlaylist(id: String) = setPlaylists(playlists().filterNot { it.id == id })
    fun renamePlaylist(id: String, name: String) = setPlaylists(
        playlists().map {
            if (it.id == id && name.isNotBlank()) it.copy(name = name.trim()) else it
        },
    )

    fun addToPlaylist(playlistId: String, lessonId: String) = setPlaylists(
        playlists().map {
            if (it.id == playlistId && lessonId !in it.lessonIds) {
                it.copy(lessonIds = it.lessonIds + lessonId)
            } else it
        },
    )

    fun removeFromPlaylist(playlistId: String, lessonId: String) = setPlaylists(
        playlists().map {
            if (it.id == playlistId) it.copy(lessonIds = it.lessonIds - lessonId) else it
        },
    )

    fun playbackSpeed(): Double = double(KEY_SPEED, 1.0).coerceIn(0.75, 2.0)
    fun setPlaybackSpeed(value: Double) =
        write { putFloat(KEY_SPEED, value.coerceIn(0.75, 2.0).toFloat()) }
    fun skipSeconds(): Int = long(KEY_SKIP, 15L).toInt()
    fun setSkipSeconds(value: Int) = write { putLong(KEY_SKIP, value.toLong()) }

    /// موعد انتهاء مؤقّت النوم (0 = لا مؤقّت). يُحفظ هنا لا في نطاق الواجهة
    /// وحده: سحب التطبيق من التطبيقات الحديثة كان يقتل المؤقّت بينما يستمرّ
    /// التشغيل عبر الخدمة، فيبقى الصوت شغّالاً طوال الليل. الخدمة تقرأ هذه
    /// القيمة وتوقف التشغيل في موعده، والواجهة تستعيد المؤقّت عند إعادة الفتح.
    fun sleepEndsAtMs(): Long = long(KEY_SLEEP_ENDS_AT)
    fun setSleepEndsAtMs(value: Long) = write { putLong(KEY_SLEEP_ENDS_AT, value) }
    fun clearSleepTimer() = write { putLong(KEY_SLEEP_ENDS_AT, 0L) }

    // المظهر الافتراضي: اتّباع النظام (طلب المستخدم 2026-07-23).
    fun themeMode(): String = string(KEY_THEME, "system")
    fun setThemeMode(value: String) = write { putString(KEY_THEME, value) }
    // المدى مطابق لمنزلق الإعدادات وللأصل (0.8–1.4).
    fun fontScale(): Float = double(KEY_FONT_SCALE, 1.0).toFloat().coerceIn(0.8f, 1.4f)
    fun setFontScale(value: Float) =
        write { putFloat(KEY_FONT_SCALE, value.coerceIn(0.8f, 1.4f)) }

    fun autoDownloadEnabled(): Boolean = bool(KEY_AUTO_DOWNLOAD)
    fun setAutoDownloadEnabled(value: Boolean) = write { putBoolean(KEY_AUTO_DOWNLOAD, value) }
    fun autoDownloadTarget(): String? = string(KEY_AUTO_TARGET).takeIf { it.isNotBlank() }
    fun setAutoDownloadTarget(value: String?) = write {
        if (value == null) remove(KEY_AUTO_TARGET) else putString(KEY_AUTO_TARGET, value)
    }
    fun autoDownloadWifiOnly(): Boolean = bool(KEY_WIFI_ONLY, true)
    fun setAutoDownloadWifiOnly(value: Boolean) = write { putBoolean(KEY_WIFI_ONLY, value) }

    fun notificationsEnabled(): Boolean = bool(KEY_NOTIFICATIONS, true)
    fun setNotificationsEnabled(value: Boolean) = write { putBoolean(KEY_NOTIFICATIONS, value) }
    fun continueReminderEnabled(): Boolean = bool(KEY_CONTINUE_REMINDER, true)
    fun setContinueReminderEnabled(value: Boolean) =
        write { putBoolean(KEY_CONTINUE_REMINDER, value) }

    fun followedSubcategories(): List<String> = stringList(KEY_FOLLOWED_SUBS)
    fun isFollowingSubcategory(id: String): Boolean = id in followedSubcategories()
    fun toggleFollowSubcategory(id: String) {
        val values = followedSubcategories().toMutableList()
        if (!values.remove(id)) values += id
        setStringList(KEY_FOLLOWED_SUBS, values)
    }

    fun notificationLastSeenMs(): Long = long(KEY_NOTIFICATION_SEEN)
    fun setNotificationLastSeenMs(value: Long) = write { putLong(KEY_NOTIFICATION_SEEN, value) }
    fun submissionsLastSeenMs(): Long = long(KEY_SUBMISSIONS_SEEN)
    fun setSubmissionsLastSeenMs(value: Long) = write { putLong(KEY_SUBMISSIONS_SEEN, value) }
    fun dismissedNotificationIds(): List<String> = stringList(KEY_DISMISSED_NOTIFICATIONS)
    fun dismissNotification(id: String) {
        val values = dismissedNotificationIds().toMutableList()
        if (id !in values) values += id
        while (values.size > 500) values.removeAt(0)
        setStringList(KEY_DISMISSED_NOTIFICATIONS, values)
    }

    /// إخفاء دفعة إشعارات بكتابة واحدة (زر «مسح الكل»).
    fun dismissNotifications(ids: List<String>) {
        if (ids.isEmpty()) return
        val values = (dismissedNotificationIds() + ids).distinct().toMutableList()
        while (values.size > 500) values.removeAt(0)
        setStringList(KEY_DISMISSED_NOTIFICATIONS, values)
    }

    /// التراجع عن «مسح الكل»: يُعيد إظهار كل ما استُبعد يدوياً. الإشعارات
    /// نفسها لا تُحذف من الخادم، فالاستبعاد محليّ بحت وقابل للنقض.
    fun clearDismissedNotifications() =
        setStringList(KEY_DISMISSED_NOTIFICATIONS, emptyList())

    // ---- تلميحات الإرشاد للمستخدم الجديد (تُعرض مرة واحدة) ----
    fun hintSeen(key: String): Boolean = bool("hint_$key")
    fun markHintSeen(key: String) = write { putBoolean("hint_$key", true) }

    // ---- طابور التحميل الخلفي (يُعالَج عبر WorkManager ويصمد لإغلاق التطبيق) ----
    /// قفل يحمي دورة قراءة-تعديل-كتابة الطابور من التزامن بين الخيوط.
    private val queueLock = Any()

    fun downloadQueue(): List<String> = stringList("download_queue")

    /// معرّفات عناصر الطابور المقيَّدة بـ«واي فاي فقط».
    ///
    /// القيد صار **لكل عنصر** لا حالة عامّة واحدة: العلم المشترك القديم كان
    /// يُكتب بلا شرط عند كل إضافة، فضغطة تحميل يدويّة واحدة (بالافتراضي
    /// `wifiOnly=false`) تمحو قيد الدفعة التلقائية فتنزل عشرات الدروس على
    /// بيانات الجوّال خلافاً لإعداد المستخدم الصريح.
    ///
    /// ترحيل آمن للقيمة القديمة: إن لم تُكتب هذه القائمة بعد وكان العلم العام
    /// مرفوعاً، فكل ما في الطابور مقيَّد — وهو سلوك النسخة السابقة حرفيّاً.
    fun downloadQueueWifiOnlyIds(): Set<String> {
        if (raw(KEY_QUEUE_WIFI_IDS) == null) {
            return if (downloadQueueWifiOnly()) downloadQueue().toSet() else emptySet()
        }
        return stringList(KEY_QUEUE_WIFI_IDS).toSet()
    }

    /// [wifiOnly] يُحفظ لكل معرّف مُضاف في هذه الدفعة وحدها، فلا تُخفَّض قيود
    /// دفعات سابقة أبداً (دفعات التحميل التلقائي).
    fun addToDownloadQueue(ids: List<String>, label: String, wifiOnly: Boolean = false) {
        synchronized(queueLock) {
            val current = downloadQueue().toMutableList()
            val added = ids.filter { it !in current }
            if (added.isEmpty()) return
            // يُقرأ قبل كتابة الطابور الجديد كي يبقى ترحيل العلم القديم صحيحاً.
            val restricted = downloadQueueWifiOnlyIds().toMutableSet()
            if (wifiOnly) restricted += added
            current += added
            write {
                putString("download_queue", JSONArray(current).toString())
                putString("download_queue_label", label)
                putLong("download_queue_total", (downloadQueueTotal() + added.size).toLong())
                putString(KEY_QUEUE_WIFI_IDS, JSONArray(restricted.toList()).toString())
                // العلم العام يبقى للتوافق فقط ولا يُخفَّض: «هل في الطابور مقيَّد؟».
                putBoolean("download_queue_wifi_only", restricted.isNotEmpty())
            }
        }
    }

    fun removeFromDownloadQueue(id: String) {
        synchronized(queueLock) {
            val current = downloadQueue().toMutableList()
            if (!current.remove(id)) return
            val restricted = downloadQueueWifiOnlyIds() - id
            write {
                putString("download_queue", JSONArray(current).toString())
                putString(KEY_QUEUE_WIFI_IDS, JSONArray(restricted.toList()).toString())
                putBoolean("download_queue_wifi_only", restricted.isNotEmpty())
                if (current.isEmpty()) putLong("download_queue_total", 0L)
            }
        }
    }

    fun clearDownloadQueue() = synchronized(queueLock) { clearQueueLocked() }

    /// يمسح الطابور فقط إن كان فارغاً فعلاً، كي لا تُبتلع دفعة أُضيفت أثناء
    /// انتهاء المعالج.
    fun clearDownloadQueueIfEmpty() = synchronized(queueLock) {
        if (downloadQueue().isEmpty()) clearQueueLocked()
    }

    private fun clearQueueLocked() = write {
        // الإيقاف حالة طابورٍ لا حالة تطبيق: طابور فارغ يعني بداية نظيفة،
        // وإلّا بقي علم الإيقاف مرفوعاً فلا يبدأ التحميل التالي أبداً.
        remove("download_queue_paused")
        remove("download_queue")
        remove("download_queue_wifi_only")
        remove(KEY_QUEUE_WIFI_IDS)
        putLong("download_queue_total", 0L)
    }

    /// ⏸ إيقاف مؤقّت لطابور التحميل — **لا إلغاء**: الملفات الجزئية وترتيب
    /// الطابور يبقيان، والاستئناف يُكمل من البايت الذي وقف عنده بـRange.
    /// محفوظ على القرص كي يصمد لإغلاق التطبيق: بلا ذلك كان WorkManager
    /// يوقظ العامل بعد دقائق فيستأنف تحميلاً أوقفه المستخدم عمداً — وغالباً
    /// أوقفه لأنّه على بيانات الجوّال.
    fun downloadQueuePaused(): Boolean = bool("download_queue_paused")

    fun setDownloadQueuePaused(paused: Boolean) = write {
        putBoolean("download_queue_paused", paused)
    }

    fun downloadQueueLabel(): String = string("download_queue_label")
    fun downloadQueueTotal(): Int = long("download_queue_total").toInt()
    fun downloadQueueWifiOnly(): Boolean = bool("download_queue_wifi_only")

    fun wardHour(): Int = long(KEY_WARD_HOUR, -1L).toInt()
    fun wardMinute(): Int = long(KEY_WARD_MINUTE, 0L).toInt()
    fun wardEnabled(): Boolean = wardHour() >= 0
    fun setWardTime(hour: Int, minute: Int) = write {
        putLong(KEY_WARD_HOUR, hour.toLong())
        putLong(KEY_WARD_MINUTE, minute.toLong())
    }
    fun disableWard() = write { putLong(KEY_WARD_HOUR, -1L) }

    fun weeklyGoalMinutes(): Int = long(KEY_WEEKLY_GOAL).toInt().coerceAtLeast(0)
    fun setWeeklyGoalMinutes(value: Int) =
        write { putLong(KEY_WEEKLY_GOAL, value.coerceAtLeast(0).toLong()) }
    fun dailySeconds(): Map<String, Long> = intMap(KEY_DAILY_SECONDS)
    fun addListenSeconds(seconds: Long) {
        if (seconds <= 0L) return
        val values = dailySeconds().toMutableMap()
        val key = todayKey()
        values[key] = (values[key] ?: 0L) + seconds
        values.keys
            .sortedByDescending { parseLegacyDate(it) ?: LocalDate.MIN }
            .drop(120)
            .forEach { values.remove(it) }
        setIntMap(KEY_DAILY_SECONDS, values)
    }
    fun todaySeconds(): Long = dailySeconds()[todayKey()] ?: 0L
    fun totalSeconds(): Long = dailySeconds().values.sum()
    fun weekSeconds(): Long {
        val values = dailySeconds()
        return (0L..6L).sumOf { offset ->
            values[legacyDateKey(LocalDate.now().minusDays(offset))] ?: 0L
        }
    }

    fun streakDays(): Int = long(KEY_STREAK).toInt()
    fun lastListenDate(): String? = string(KEY_LAST_LISTEN_DATE).takeIf { it.isNotBlank() }
    fun recordDailyListen() {
        val today = todayKey()
        val last = lastListenDate()
        if (last == today) return
        val streak = runCatching {
            val previous = requireNotNull(last?.let(::parseLegacyDate))
            val days = java.time.temporal.ChronoUnit.DAYS.between(previous, LocalDate.now())
            if (days == 1L) streakDays() + 1 else 1
        }.getOrDefault(1)
        write {
            putLong(KEY_STREAK, streak.toLong())
            putString(KEY_LAST_LISTEN_DATE, today)
        }
    }

    fun bookmarks(lessonId: String): List<Bookmark> {
        val array = jsonObject(KEY_BOOKMARKS).optJSONArray(lessonId) ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    Bookmark(
                        lessonId = lessonId,
                        positionMs = item.optLong("ms"),
                        note = item.optString("note"),
                        savedAtMs = item.optLong("savedAt"),
                    ),
                )
            }
        }.sortedBy(Bookmark::positionMs)
    }

    fun allBookmarks(): List<Bookmark> {
        val root = jsonObject(KEY_BOOKMARKS)
        return buildList {
            root.keys().forEach { lessonId -> addAll(bookmarks(lessonId)) }
        }.sortedByDescending(Bookmark::savedAtMs)
    }

    fun addBookmark(lessonId: String, positionMs: Long, note: String) {
        val root = jsonObject(KEY_BOOKMARKS)
        val array = root.optJSONArray(lessonId) ?: JSONArray()
        array.put(
            JSONObject()
                .put("ms", positionMs)
                .put("note", note.trim())
                .put("savedAt", System.currentTimeMillis()),
        )
        val sorted = (0 until array.length())
            .mapNotNull(array::optJSONObject)
            .sortedBy { it.optLong("ms") }
        root.put(lessonId, JSONArray(sorted))
        putJson(KEY_BOOKMARKS, root)
    }

    fun removeBookmark(lessonId: String, savedAtMs: Long) {
        val root = jsonObject(KEY_BOOKMARKS)
        val array = root.optJSONArray(lessonId) ?: return
        val remaining = (0 until array.length())
            .mapNotNull(array::optJSONObject)
            .filterNot { it.optLong("savedAt") == savedAtMs }
        if (remaining.isEmpty()) root.remove(lessonId) else root.put(lessonId, JSONArray(remaining))
        putJson(KEY_BOOKMARKS, root)
    }

    fun submitterName(): String = string(KEY_SUBMITTER_NAME)
    fun setSubmitterName(value: String) = write { putString(KEY_SUBMITTER_NAME, value.trim()) }
    fun knownSubmissionStatuses(): Map<String, String> {
        val raw = string(KEY_KNOWN_SUBMISSION_STATUSES, "{}")
        runCatching { JSONObject(raw).stringMap() }.getOrNull()?.let { return it }

        // Older Flutter versions stored "id=status" entries as a StringList.
        return runCatching {
            JSONArray(raw).strings().mapNotNull { item ->
                val separator = maxOf(item.lastIndexOf('='), item.lastIndexOf('|'))
                if (separator <= 0 || separator == item.lastIndex) null
                else item.substring(0, separator) to item.substring(separator + 1)
            }.toMap()
        }.getOrDefault(emptyMap())
    }
    fun setKnownSubmissionStatuses(value: Map<String, String>) =
        putJson(KEY_KNOWN_SUBMISSION_STATUSES, JSONObject(value))

    fun trackEvent(name: String) {
        val values = intMap(KEY_ANALYTICS).toMutableMap()
        values[name] = (values[name] ?: 0L) + 1L
        setIntMap(KEY_ANALYTICS, values)
    }

    // ---- نسخة احتياطية محلية (مفضّلة/قوائم/سجل/تقدّم/لحظات) ----
    private val backupKeys = listOf(
        KEY_FAVORITES, KEY_PLAYLISTS, KEY_RECENT, KEY_COMPLETED, KEY_POSITIONS,
        KEY_DURATIONS, KEY_BOOKMARKS, KEY_FOLLOWED_SUBS, KEY_SEARCH_HISTORY,
        KEY_CATEGORY_VISITS, KEY_SUBCATEGORY_VISITS, KEY_PLAY_COUNTS,
        KEY_DAILY_SECONDS, KEY_STREAK, KEY_LAST_LISTEN_DATE, KEY_WEEKLY_GOAL,
    )

    /// يصدّر البيانات الشخصية كنص JSON (بلا تنزيلات ولا كاش المحتوى).
    fun exportBackup(): String {
        val root = JSONObject()
            .put("app", "menbaradkshk")
            .put("version", 1)
            .put("exportedAtMs", System.currentTimeMillis())
        val data = JSONObject()
        backupKeys.forEach { key ->
            when (val value = raw(key)) {
                null -> Unit
                is String -> data.put(key, decodeFlutterStringList(value) ?: value)
                is Long -> data.put(key, value)
                is Int -> data.put(key, value.toLong())
                is Boolean -> data.put(key, value)
                is Float -> data.put(key, value.toDouble())
                else -> Unit
            }
        }
        return root.put("data", data).toString(2)
    }

    /// يستورد نسخة احتياطية سابقة. يعيد عدد الحقول المستعادة، أو -1 إن كان الملف غير صالح.
    fun importBackup(json: String): Int {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return -1
        if (root.optString("app") != "menbaradkshk") return -1
        val data = root.optJSONObject("data") ?: return -1
        var restored = 0
        write {
            backupKeys.forEach { key ->
                if (!data.has(key)) return@forEach
                when (val value = data.get(key)) {
                    is String -> putString(key, value)
                    is Int -> putLong(key, value.toLong())
                    is Long -> putLong(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Double -> putFloat(key, value.toFloat())
                    else -> return@forEach
                }
                restored += 1
            }
        }
        return restored
    }

    fun clearPersonalData() {
        // ملفات الصوت تُحذف **قبل** محو الفهرس: محو `KEY_DOWNLOADS` وحده كان
        // يُفقد المسارات فتبقى الدروس المنزَّلة (مئات الميغابايتات) في
        // `filesDir/lessons` إلى الأبد بلا أي طريق لحذفها من التطبيق.
        runCatching { downloads().values.forEach { File(it).delete() } }
        val editor = preferences.edit()
        PERSONAL_KEYS.forEach { editor.remove(it) }
        editor.apply()
        val legacyEditor = legacy.edit()
        PERSONAL_KEYS.forEach { legacyEditor.remove("$FLUTTER_PREFIX$it") }
        legacyEditor.apply()
        _revision.value += 1L
    }

    fun migrationSummary(): MigrationSummary = MigrationSummary(
        completed = preferences.getBoolean(MIGRATED_KEY, false),
        migratedKeys = preferences.getInt(MIGRATED_COUNT_KEY, 0),
        migratedAtMs = preferences.getLong(MIGRATED_AT_KEY, 0L),
        legacyFilePresent = legacy.all.isNotEmpty(),
    )

    private fun todayKey(): String = legacyDateKey(LocalDate.now(ZoneId.systemDefault()))

    private fun legacyDateKey(date: LocalDate): String =
        "${date.year}-${date.monthValue}-${date.dayOfMonth}"

    private fun parseLegacyDate(value: String): LocalDate? {
        val parts = value.split('-')
        if (parts.size != 3) return null
        return runCatching {
            LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }.getOrNull()
    }

    data class MigrationSummary(
        val completed: Boolean,
        val migratedKeys: Int,
        val migratedAtMs: Long,
        val legacyFilePresent: Boolean,
    )

    companion object {
        private const val NATIVE_FILE = "minbar_native_preferences"
        private const val FLUTTER_FILE = "FlutterSharedPreferences"
        private const val FLUTTER_PREFIX = "flutter."
        private const val MIGRATED_KEY = "_native_migration_v1"
        private const val MIGRATED_COUNT_KEY = "_native_migration_count"
        private const val MIGRATED_AT_KEY = "_native_migration_at"
        private const val DOUBLE_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu"
        private const val LIST_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
        private const val JSON_LIST_PREFIX = "$LIST_PREFIX!"

        const val KEY_CACHE_CATEGORIES = "cache_categories"
        const val KEY_CACHE_SUBCATEGORIES = "cache_subcategories"
        const val KEY_CACHE_LESSONS = "cache_lessons"
        const val KEY_LAST_SYNC = "cache_last_sync_ms"
        const val KEY_DOWNLOADS = "downloads_audio"
        const val KEY_PLAY_COUNTS = "pers_play_counts"
        const val KEY_RECENT = "pers_recent_played"
        const val KEY_CATEGORY_VISITS = "pers_cat_visits"
        const val KEY_SUBCATEGORY_VISITS = "pers_sub_visits"
        const val KEY_POSITIONS = "pers_positions"
        const val KEY_COMPLETED = "pers_completed"
        const val KEY_DURATIONS = "pers_durations"
        const val KEY_VIEW_COUNTED = "pers_view_counted"
        const val KEY_FAVORITES = "pers_favorites"
        const val KEY_SEARCH_HISTORY = "pers_search_hist"
        const val KEY_PLAYLISTS = "pers_playlists"
        const val KEY_STREAK = "pers_streak_days"
        const val KEY_LAST_LISTEN_DATE = "pers_last_listen_date"
        const val KEY_SPEED = "pref_speed"
        const val KEY_SKIP = "pref_skip_sec"
        const val KEY_SLEEP_ENDS_AT = "pref_sleep_ends_at"
        /// قيد «واي فاي فقط» لكل عنصر في طابور التحميل (يحلّ محلّ العلم العام).
        const val KEY_QUEUE_WIFI_IDS = "download_queue_wifi_ids"
        const val KEY_THEME = "theme_mode"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_AUTO_DOWNLOAD = "auto_dl_enabled"
        const val KEY_AUTO_TARGET = "auto_dl_target"
        const val KEY_WIFI_ONLY = "auto_dl_wifi_only"
        const val KEY_CONTINUE_REMINDER = "pref_continue_reminder"
        const val KEY_ANALYTICS = "analytics_event_counts"
        const val KEY_NOTIFICATION_SEEN = "notif_last_seen_ms"
        const val KEY_SUBMISSIONS_SEEN = "my_subs_seen_ms"
        const val KEY_NOTIFICATIONS = "notif_enabled"
        const val KEY_DISMISSED_NOTIFICATIONS = "notif_dismissed"
        const val KEY_BOOKMARKS = "pers_bookmarks"
        const val KEY_FOLLOWED_SUBS = "pers_followed_subs"
        const val KEY_WARD_HOUR = "ward_hour"
        const val KEY_WARD_MINUTE = "ward_minute"
        const val KEY_DAILY_SECONDS = "stat_daily_seconds"
        const val KEY_WEEKLY_GOAL = "goal_weekly_min"
        const val KEY_KNOWN_SUBMISSION_STATUSES = "submission_known_statuses_v1"
        const val KEY_SUBMITTER_NAME = "submitter_name_v1"

        private val PERSONAL_KEYS = setOf(
            KEY_DOWNLOADS,
            KEY_PLAY_COUNTS,
            KEY_RECENT,
            KEY_CATEGORY_VISITS,
            KEY_SUBCATEGORY_VISITS,
            KEY_POSITIONS,
            KEY_COMPLETED,
            KEY_DURATIONS,
            KEY_VIEW_COUNTED,
            KEY_FAVORITES,
            KEY_SEARCH_HISTORY,
            KEY_PLAYLISTS,
            KEY_STREAK,
            KEY_LAST_LISTEN_DATE,
            KEY_BOOKMARKS,
            KEY_FOLLOWED_SUBS,
            KEY_DAILY_SECONDS,
            KEY_ANALYTICS,
            KEY_NOTIFICATION_SEEN,
            KEY_DISMISSED_NOTIFICATIONS,
            KEY_SUBMISSIONS_SEEN,
            KEY_WEEKLY_GOAL,
            KEY_KNOWN_SUBMISSION_STATUSES,
            KEY_SUBMITTER_NAME,
        )

        @Volatile
        private var instance: LocalStore? = null

        fun get(context: Context): LocalStore = instance ?: synchronized(this) {
            instance ?: LocalStore(context).also { instance = it }
        }
    }
}

private fun <T> JSONArray.objects(parser: (JSONObject) -> T): List<T> = buildList {
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        runCatching { parser(item) }.getOrNull()?.let(::add)
    }
}

private fun JSONArray.strings(): List<String> = buildList {
    for (index in 0 until length()) {
        optString(index).takeIf(String::isNotEmpty)?.let(::add)
    }
}

private fun JSONObject.stringMap(): Map<String, String> = buildMap {
    keys().forEach { key -> put(key, optString(key)) }
}

private fun JSONObject.longMap(): Map<String, Long> = buildMap {
    keys().forEach { key ->
        val value = opt(key).longValue()
        put(key, value)
    }
}
