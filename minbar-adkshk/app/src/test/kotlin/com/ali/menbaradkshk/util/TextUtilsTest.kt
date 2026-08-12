package com.ali.menbaradkshk.util

import com.ali.menbaradkshk.data.Lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilsTest {
    @Test
    fun arabicSearchIgnoresDiacriticsAndAlefVariants() {
        assertTrue(arabicContains("إِنَّمَا الأَعْمَالُ بالنِّيَّات", "انما الاعمال"))
    }

    @Test
    fun durationFormatsHoursAndMinutes() {
        assertEquals("1:02:03", formatDuration(3_723_000L))
        assertEquals("2:05", formatDuration(125_000L))
    }

    @Test
    fun shareLinkPreservesLessonAndPosition() {
        val lesson = Lesson(
            id = "abc123",
            title = "درس الاختبار",
            categoryId = "c",
            subcategoryId = "s",
            audioUrl = "https://example.test/audio.mp3",
            createdAtMs = 1L,
        )
        assertEquals(
            "https://minbar-adkassahk.vercel.app/lesson/abc123?t=42",
            lessonShareLink(lesson, 42L),
        )
    }
}
