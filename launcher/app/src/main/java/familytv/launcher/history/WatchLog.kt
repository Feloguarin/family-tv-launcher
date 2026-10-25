package familytv.launcher.history

import android.content.Context
import android.util.Log
import familytv.launcher.Audience
import familytv.launcher.Service
import familytv.launcher.Show
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One launch from the launcher: what was opened, for whom, and when (epoch millis). */
data class WatchEntry(
    val showId: String,
    val title: String,
    val service: Service?,
    val audience: Audience,
    val at: Long,
)

/**
 * Append-only log of shows opened from the launcher, kept as JSON lines in filesDir.
 * Feeds "Continue Watching", "Our shows" and the recommender. Thread-safe.
 *
 * @param maxEntries oldest entries are dropped once the log grows past this
 */
class WatchLog(
    private val file: File,
    private val maxEntries: Int = 2_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val lock = Any()
    private var cache: MutableList<WatchEntry>? = null // oldest first, loaded on first use

    /** Records a launch. Call when the show is actually opened, not when its tile is focused. */
    fun record(show: Show, audience: Audience, at: Long = clock()) {
        append(WatchEntry(show.id, show.title, show.service, audience, at))
    }

    fun append(entry: WatchEntry): Unit = synchronized(lock) {
        val entries = load()
        entries += entry
        if (entries.size > 1 && entries[entries.size - 2].at > entry.at) entries.sortBy { it.at }
        try {
            if (entries.size > maxEntries) {
                val keep = entries.takeLast(maxEntries)
                entries.clear()
                entries += keep
                rewrite(entries)
            } else {
                file.parentFile?.mkdirs()
                file.appendText(encode(entry) + "\n")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not write the watch log", e)
        }
    }

    /** Distinct shows for [audience], most recently opened first. */
    fun recent(audience: Audience, limit: Int = 10): List<WatchEntry> = synchronized(lock) {
        load().asReversed().asSequence()
            .filter { it.audience == audience }
            .distinctBy { it.showId }
            .take(limit)
            .toList()
    }

    /** The latest launch of [showId] for any audience, or null if it was never opened. */
    fun lastWatched(showId: String): WatchEntry? = synchronized(lock) {
        load().lastOrNull { it.showId == showId }
    }

    /** Every show id ever opened (any audience). */
    fun watchedIds(): Set<String> = synchronized(lock) { load().mapTo(HashSet()) { it.showId } }

    /** "Last watched last night", or null if [showId] was never opened. */
    fun lastWatchedLabel(showId: String): String? =
        lastWatched(showId)?.let { "Last watched " + whenLabel(it.at, clock(), zone()) }

    fun all(): List<WatchEntry> = synchronized(lock) { load().toList() }

    private fun load(): MutableList<WatchEntry> {
        cache?.let { return it }
        val entries = mutableListOf<WatchEntry>()
        if (file.exists()) {
            try {
                file.forEachLine { line -> decode(line)?.let(entries::add) }
            } catch (e: IOException) {
                Log.w(TAG, "Could not read the watch log", e)
            }
        }
        entries.sortBy { it.at } // stable, so equal timestamps keep file order
        return entries.also { cache = it }
    }

    private fun rewrite(entries: List<WatchEntry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(entries.joinToString("") { encode(it) + "\n" })
        if (!tmp.renameTo(file)) throw IOException("rename failed")
    }

    companion object {
        private const val TAG = "WatchLog"
        const val FILE_NAME = "watch-log.jsonl"

        @Volatile private var instance: WatchLog? = null

        /**
         * The app-wide log. Use this rather than a new instance: the log is cached in memory, so two
         * instances on the same file would drift apart.
         */
        fun get(context: Context): WatchLog = instance ?: synchronized(this) {
            instance ?: WatchLog(File(context.applicationContext.filesDir, FILE_NAME)).also { instance = it }
        }

        internal fun encode(e: WatchEntry): String = JSONObject()
            .put("showId", e.showId)
            .put("title", e.title)
            .put("service", e.service?.name ?: JSONObject.NULL)
            .put("audience", e.audience.name)
            .put("at", e.at)
            .toString()

        /** Returns null for blank or corrupt lines (a crash mid-write leaves at most one). */
        internal fun decode(line: String): WatchEntry? {
            if (line.isBlank()) return null
            return try {
                val o = JSONObject(line)
                WatchEntry(
                    showId = o.getString("showId"),
                    title = o.getString("title"),
                    service = o.optString("service").let { s -> Service.entries.firstOrNull { it.name == s } },
                    audience = Audience.valueOf(o.getString("audience")),
                    at = o.getLong("at"),
                )
            } catch (e: JSONException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
        private val MONTH_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

        /**
         * A couch-friendly time: "tonight", "last night", "today", "yesterday", "Tuesday", "Oct 12".
         * Evenings run 5 pm to 4 am, so a 1 am launch is still "last night" the next morning.
         */
        fun whenLabel(at: Long, now: Long, zone: ZoneId): String {
            val then = ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), zone)
            val current = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            val today = logicalDay(current)
            val day = logicalDay(then)
            val evening = then.hour >= 17 || then.hour < 4
            val daysAgo = ChronoUnit.DAYS.between(day, today)
            return when {
                daysAgo <= 0L -> if (evening) "tonight" else "today"
                daysAgo == 1L -> if (evening) "last night" else "yesterday"
                daysAgo < 7L -> then.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                then.year == current.year -> MONTH_DAY.format(then)
                else -> MONTH_DAY_YEAR.format(then)
            }
        }

        /** The day a moment "belongs" to: before 4 am counts as the previous evening. */
        private fun logicalDay(t: ZonedDateTime): LocalDate =
            if (t.hour < 4) t.toLocalDate().minusDays(1) else t.toLocalDate()
    }
}
