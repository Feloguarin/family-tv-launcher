package familytv.launcher.recs

import android.content.Context

/** "Shows we love": a few titles the parents type once, used to seed "For You" before there's history. */
class TasteStore(context: Context) {
    private val prefs = context.getSharedPreferences("taste", Context.MODE_PRIVATE)

    var favorites: List<String>
        get() = parse(prefs.getString(KEY, "").orEmpty())
        set(value) { prefs.edit().putString(KEY, value.joinToString("\n")).apply() }

    companion object {
        private const val KEY = "favorites"
        const val MAX = 10

        /** "Severance, The Bear\nSlow Horses" → titles, trimmed, de-duplicated, at most [MAX]. */
        fun parse(text: String): List<String> = text
            .split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 80 }
            .distinctBy { it.lowercase() }
            .take(MAX)
    }
}
