package com.ali.menbaradkshk.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mosque
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

// لون إبراز ثابت لكل قسم بحسب معرّفه، مشتقّ من أيقونة التطبيق (جوهر سماوي +
// ذهب معتّق + حبر ليليّ). كلّها تحمل نصّاً أبيض بتباين لا يقلّ عن 5.98.
private val categoryPalette = listOf(
    Color(0xFF025864), // جوهر عميق (أساس الهوية)
    Color(0xFF036E79), // جوهر متوسّط
    Color(0xFF085956), // جوهر مخضرّ
    Color(0xFF186B65), // فيروزي هادئ
    Color(0xFF885618), // ذهب معتّق (Gold900)
    Color(0xFF020E1D), // حبر الأيقونة نفسه
    Color(0xFF14405E), // أزرق ليليّ
    Color(0xFF7A4514), // بنّي دافئ
)

private val categoryIcons = listOf(
    Icons.Filled.MenuBook,
    Icons.Filled.Mosque,
    Icons.Filled.AutoStories,
    Icons.Filled.School,
    Icons.Filled.Lightbulb,
    Icons.Filled.Favorite,
    Icons.Filled.Star,
    Icons.Filled.Headphones,
)

private fun hashOf(id: String, size: Int): Int {
    if (id.isEmpty()) return 0
    var hash = 0
    for (ch in id) hash = (hash + ch.code) % size
    return hash
}

fun colorForCategory(id: String): Color {
    if (id.isEmpty()) return Teal
    return categoryPalette[hashOf(id, categoryPalette.size)]
}

fun iconForCategory(id: String): ImageVector {
    if (id.isEmpty()) return Icons.Filled.Folder
    return categoryIcons[hashOf(id, categoryIcons.size)]
}
