package com.ali.menbaradkshk.data

import com.google.firebase.Timestamp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

private fun unwrap(raw: Map<String, Any?>): Map<String, Any?> {
    val inner = raw["data"]
    @Suppress("UNCHECKED_CAST")
    return if (inner is Map<*, *>) {
        inner.entries.associate { (key, value) -> key.toString() to value }
    } else {
        raw
    }
}

internal fun Any?.text(): String = this?.toString()?.trim().orEmpty()

internal fun Any?.longValue(): Long = when (this) {
    is Long -> this
    is Int -> toLong()
    is Number -> toLong()
    is String -> toLongOrNull() ?: 0L
    else -> 0L
}

internal fun Any?.timeMillis(): Long = when (this) {
    null -> 0L
    is Timestamp -> toDate().time
    is Date -> time
    is Number -> toLong()
    is String -> runCatching { java.time.Instant.parse(this).toEpochMilli() }.getOrDefault(0L)
    is Map<*, *> -> this["seconds"].longValue() * 1_000L
    else -> 0L
}

data class Category(
    val id: String,
    val name: String,
    val createdAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("_id", id)
        .put("name", name)
        .put("createdAt", createdAtMs)

    companion object {
        fun fromMap(id: String, raw: Map<String, Any?>): Category {
            val data = unwrap(raw)
            return Category(
                id = id,
                name = data["name"].text(),
                createdAtMs = data["createdAt"].timeMillis(),
            )
        }

        fun fromJson(json: JSONObject): Category = Category(
            id = json.optString("_id"),
            name = json.optString("name"),
            createdAtMs = json.opt("createdAt").timeMillis(),
        )
    }
}

data class Subcategory(
    val id: String,
    val name: String,
    val categoryId: String,
    val createdAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("_id", id)
        .put("name", name)
        .put("categoryId", categoryId)
        .put("createdAt", createdAtMs)

    companion object {
        fun fromMap(id: String, raw: Map<String, Any?>): Subcategory {
            val data = unwrap(raw)
            return Subcategory(
                id = id,
                name = data["name"].text(),
                categoryId = data["categoryId"].text(),
                createdAtMs = data["createdAt"].timeMillis(),
            )
        }

        fun fromJson(json: JSONObject): Subcategory = Subcategory(
            id = json.optString("_id"),
            name = json.optString("name"),
            categoryId = json.optString("categoryId"),
            createdAtMs = json.opt("createdAt").timeMillis(),
        )
    }
}

data class Lesson(
    val id: String,
    val title: String,
    val categoryId: String,
    val subcategoryId: String,
    val audioUrl: String,
    val createdAtMs: Long,
    val views: Long = 0L,
    val speaker: String = "",
    val description: String = "",
    val durationMs: Long = 0L,
    val featured: Boolean = false,
    /** نهاية مدّة التمييز (0 = دائم) — بانقضائها يسقط من «مختارات المنبر». */
    val featuredUntilMs: Long = 0L,
    val publishAtMs: Long? = null,
) {
    /// العنوان كما يُعرض: أي عنوان غير فارغ يُحترم مهما قصر («صوم»، «حج»)،
    /// ولا يُستبدل باسم المتحدّث إلا حين لا عنوان أصلاً.
    val displayTitle: String
        get() = title.trim().takeIf { it.isNotBlank() }
            ?: speaker.trim().takeIf { it.isNotEmpty() }
            ?: "درس صوتي"

    val isPublished: Boolean
        get() = publishAtMs == null || publishAtMs <= System.currentTimeMillis()

    fun toJson(): JSONObject = JSONObject()
        .put("_id", id)
        .put("title", title)
        .put("categoryId", categoryId)
        .put("subcategoryId", subcategoryId)
        .put("audioUrl", audioUrl)
        .put("createdAt", createdAtMs)
        .put("views", views)
        .put("speaker", speaker)
        .put("description", description)
        .put("durationMs", durationMs)
        .put("featured", featured)
        .put("featuredUntilMs", featuredUntilMs)
        .apply { publishAtMs?.let { put("publishAt", it) } }

    companion object {
        private fun subcategoryId(data: Map<String, Any?>): String {
            data["subcategoryId"].text().takeIf { it.isNotEmpty() }?.let { return it }
            val nested = data["subcategory"]
            return if (nested is Map<*, *>) nested["_id"].text() else ""
        }

        fun fromMap(id: String, raw: Map<String, Any?>): Lesson {
            val data = unwrap(raw)
            val publishAt = data["publishAt"]?.timeMillis()?.takeIf { it > 0L }
            return Lesson(
                id = id,
                title = data["title"].text().ifEmpty { data["name"].text() },
                categoryId = data["categoryId"].text(),
                subcategoryId = subcategoryId(data),
                audioUrl = data["audioUrl"].text(),
                createdAtMs = data["createdAt"].timeMillis(),
                views = data["views"].longValue(),
                speaker = (data["speaker"] ?: data["sheikh"] ?: data["reader"]).text(),
                description = data["description"].text(),
                durationMs = (data["durationMs"] ?: data["duration"]).longValue(),
                featured = data["featured"] == true,
                featuredUntilMs = data["featuredUntil"].timeMillis(),
                publishAtMs = publishAt,
            )
        }

        fun fromJson(json: JSONObject): Lesson = Lesson(
            id = json.optString("_id"),
            title = json.optString("title").ifEmpty { json.optString("name") },
            categoryId = json.optString("categoryId"),
            subcategoryId = json.optString("subcategoryId").ifEmpty {
                json.optJSONObject("subcategory")?.optString("_id").orEmpty()
            },
            audioUrl = json.optString("audioUrl"),
            createdAtMs = json.opt("createdAt").timeMillis(),
            views = json.opt("views").longValue(),
            speaker = json.optString("speaker").ifEmpty {
                json.optString("sheikh").ifEmpty { json.optString("reader") }
            },
            description = json.optString("description"),
            durationMs = json.opt("durationMs").longValue().takeIf { it > 0L }
                ?: json.opt("duration").longValue(),
            featured = json.optBoolean("featured", false),
            featuredUntilMs = json.optLong("featuredUntilMs", 0L),
            publishAtMs = json.opt("publishAt").timeMillis().takeIf { it > 0L },
        )
    }
}

data class Playlist(
    val id: String,
    val name: String,
    val lessonIds: List<String>,
    val createdAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("lessonIds", JSONArray(lessonIds))
        .put("createdAt", createdAtMs)

    companion object {
        fun fromJson(json: JSONObject): Playlist {
            val items = json.optJSONArray("lessonIds") ?: JSONArray()
            return Playlist(
                id = json.optString("id"),
                name = json.optString("name"),
                lessonIds = buildList {
                    for (index in 0 until items.length()) add(items.optString(index))
                },
                createdAtMs = json.opt("createdAt").timeMillis(),
            )
        }
    }
}

data class Bookmark(
    val lessonId: String,
    val positionMs: Long,
    val note: String,
    val savedAtMs: Long,
)

data class LessonSubmission(
    val id: String,
    val title: String,
    val categoryName: String,
    val subcategoryName: String,
    val status: String,
    val rejectReason: String,
    val storagePath: String,
    val createdAtMs: Long,
    val decidedAtMs: Long,
)

data class NotificationItem(
    val id: String,
    val title: String,
    val body: String,
    val type: String,
    val refId: String,
    val createdAtMs: Long,
)
