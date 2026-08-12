package com.ali.menbaradkshk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun legacyWrappedLessonIsParsed() {
        val lesson = Lesson.fromMap(
            "lesson-1",
            mapOf(
                "data" to mapOf(
                    "name" to "عنوان قديم",
                    "audioUrl" to "https://example.test/lesson.mp3",
                    "categoryId" to "cat",
                    "subcategory" to mapOf("_id" to "sub"),
                    "views" to 12,
                    "featured" to true,
                ),
            ),
        )
        assertEquals("عنوان قديم", lesson.title)
        assertEquals("sub", lesson.subcategoryId)
        assertEquals(12L, lesson.views)
        assertTrue(lesson.featured)
    }

    @Test
    fun futureScheduledLessonIsNotPublished() {
        val future = Lesson(
            id = "future",
            title = "لاحقًا",
            categoryId = "",
            subcategoryId = "",
            audioUrl = "https://example.test/future.mp3",
            createdAtMs = 1L,
            publishAtMs = System.currentTimeMillis() + 60_000L,
        )
        assertFalse(future.isPublished)
    }
}
