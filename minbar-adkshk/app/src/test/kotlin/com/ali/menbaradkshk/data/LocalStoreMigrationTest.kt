package com.ali.menbaradkshk.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LocalStoreMigrationTest {
    @Test
    fun flutterPreferencesAreCopiedWithoutDeletingRollbackData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val today = LocalDate.now()
        val legacyToday = "${today.year}-${today.monthValue}-${today.dayOfMonth}"
        val listPrefix = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!"
        legacy.edit()
            .putString(
                "flutter.${LocalStore.KEY_FAVORITES}",
                listPrefix + JSONArray(listOf("lesson-a", "lesson-b")),
            )
            .putString(
                "flutter.${LocalStore.KEY_DAILY_SECONDS}",
                JSONObject(mapOf(legacyToday to 180L)).toString(),
            )
            .putString(
                "flutter.${LocalStore.KEY_KNOWN_SUBMISSION_STATUSES}",
                listPrefix + JSONArray(listOf("sub-1=approved", "sub-2=rejected")),
            )
            .commit()

        val store = LocalStore.get(context)

        assertEquals(listOf("lesson-a", "lesson-b"), store.favoriteIds())
        assertEquals(180L, store.todaySeconds())
        assertEquals("approved", store.knownSubmissionStatuses()["sub-1"])
        assertEquals("rejected", store.knownSubmissionStatuses()["sub-2"])
        assertTrue(store.migrationSummary().completed)
        assertTrue(legacy.contains("flutter.${LocalStore.KEY_FAVORITES}"))
    }
}
