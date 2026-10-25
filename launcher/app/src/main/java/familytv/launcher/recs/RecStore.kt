package familytv.launcher.recs

import android.util.Log
import familytv.launcher.Service
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The recommender's local state in one small JSON file: the cached "For You" picks, the kids
 * pending-approval queue, approved picks, and rejected ids. Thread-safe; writes are atomic (tmp + rename).
 */
class RecStore(private val file: File) {
    private class State(
        var parentsAt: Long? = null,
        var parentsPicks: List<Pick> = emptyList(),
        var kidsRefreshedAt: Long? = null,
        val pending: MutableList<Suggestion> = mutableListOf(),
        val approved: MutableList<Suggestion> = mutableListOf(),
        val rejectedIds: MutableSet<String> = linkedSetOf(),
        val rejectedTmdbIds: MutableSet<Int> = linkedSetOf(),
    )

    private var state: State? = null

    /** Cached "For You" picks and when they were made, or null if never computed. */
    @Synchronized
    fun parents(): Pair<Long, List<Pick>>? = load().let { s -> s.parentsAt?.let { it to s.parentsPicks } }

    @Synchronized
    fun setParents(picks: List<Pick>, at: Long) = update { it.parentsAt = at; it.parentsPicks = picks }

    @Synchronized
    fun kidsRefreshedAt(): Long? = load().kidsRefreshedAt

    @Synchronized
    fun pending(): List<Suggestion> = load().pending.toList()

    @Synchronized
    fun approved(): List<Suggestion> = load().approved.toList()

    /** Adds new suggestions to the queue (skipping any already known) and stamps the refresh time. */
    @Synchronized
    fun addPending(new: List<Suggestion>, refreshedAt: Long) = update { s ->
        new.filterNot { isKnown(s, it.id, it.tmdbId) }.distinctBy { it.id }.forEach { s.pending += it }
        s.kidsRefreshedAt = refreshedAt
    }

    /** Moves a pending suggestion to approved. Returns false if [id] isn't pending. */
    @Synchronized
    fun approve(id: String): Boolean {
        val s = load()
        val suggestion = s.pending.firstOrNull { it.id == id } ?: return false
        update { it.pending.remove(suggestion); it.approved += suggestion }
        return true
    }

    /** Rejects a pending (or previously approved) suggestion so it's never suggested again. */
    @Synchronized
    fun reject(id: String): Boolean {
        val s = load()
        val suggestion = (s.pending + s.approved).firstOrNull { it.id == id } ?: return false
        update {
            it.pending.removeAll { p -> p.id == id }
            it.approved.removeAll { p -> p.id == id }
            it.rejectedIds += suggestion.id
            it.rejectedTmdbIds += suggestion.tmdbId
        }
        return true
    }

    /** True if this show was already suggested, approved or rejected. */
    @Synchronized
    fun isKnown(id: String, tmdbId: Int): Boolean = isKnown(load(), id, tmdbId)

    private fun isKnown(s: State, id: String, tmdbId: Int) =
        id in s.rejectedIds || tmdbId in s.rejectedTmdbIds ||
            (s.pending + s.approved).any { it.id == id || it.tmdbId == tmdbId }

    private fun update(change: (State) -> Unit) {
        val s = load()
        change(s)
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(encode(s).toString())
            if (!tmp.renameTo(file)) throw IOException("rename failed")
        } catch (e: IOException) {
            Log.w(TAG, "Could not save recommendations", e)
        }
    }

    private fun load(): State {
        state?.let { return it }
        val s = if (file.exists()) {
            try {
                decode(JSONObject(file.readText()))
            } catch (e: IOException) {
                Log.w(TAG, "Could not read recommendations", e); State()
            } catch (e: JSONException) {
                Log.w(TAG, "Corrupt recommendations file; starting over", e); State()
            }
        } else {
            State()
        }
        return s.also { state = it }
    }

    private companion object {
        const val TAG = "RecStore"

        fun encode(s: State) = JSONObject()
            .put("parentsAt", s.parentsAt ?: JSONObject.NULL)
            .put("parentsPicks", JSONArray(s.parentsPicks.map(::encodePick)))
            .put("kidsRefreshedAt", s.kidsRefreshedAt ?: JSONObject.NULL)
            .put("pending", JSONArray(s.pending.map(::encodeSuggestion)))
            .put("approved", JSONArray(s.approved.map(::encodeSuggestion)))
            .put("rejectedIds", JSONArray(s.rejectedIds.toList()))
            .put("rejectedTmdbIds", JSONArray(s.rejectedTmdbIds.toList()))

        fun decode(o: JSONObject): State {
            val rejectedIds = o.optJSONArray("rejectedIds")
            val rejectedTmdb = o.optJSONArray("rejectedTmdbIds")
            return State(
                parentsAt = if (o.isNull("parentsAt")) null else o.optLong("parentsAt"),
                parentsPicks = o.optJSONArray("parentsPicks").objects().mapNotNull(::decodePick),
                kidsRefreshedAt = if (o.isNull("kidsRefreshedAt")) null else o.optLong("kidsRefreshedAt"),
                pending = o.optJSONArray("pending").objects().mapNotNull(::decodeSuggestion).toMutableList(),
                approved = o.optJSONArray("approved").objects().mapNotNull(::decodeSuggestion).toMutableList(),
                rejectedIds = (0 until (rejectedIds?.length() ?: 0)).mapTo(linkedSetOf()) { rejectedIds!!.getString(it) },
                rejectedTmdbIds = (0 until (rejectedTmdb?.length() ?: 0)).mapTo(linkedSetOf()) { rejectedTmdb!!.getInt(it) },
            )
        }

        fun service(name: String?) = Service.entries.firstOrNull { it.name == name }

        fun encodePick(p: Pick) = JSONObject()
            .put("tmdbId", p.tmdbId).put("title", p.title).put("service", p.service.name).put("why", p.why)

        fun decodePick(o: JSONObject): Pick? {
            val title = o.str("title") ?: return null
            val service = service(o.str("service")) ?: return null
            return Pick(o.optInt("tmdbId"), title, service, o.str("why").orEmpty())
        }

        fun encodeSuggestion(s: Suggestion) = JSONObject()
            .put("id", s.id).put("tmdbId", s.tmdbId).put("title", s.title).put("service", s.service.name)
            .put("learn", s.learn).put("score", s.score).put("rating", s.rating)

        fun decodeSuggestion(o: JSONObject): Suggestion? {
            val id = o.str("id") ?: return null
            val title = o.str("title") ?: return null
            val service = service(o.str("service")) ?: return null
            return Suggestion(
                id, o.optInt("tmdbId"), title, service,
                learn = o.str("learn").orEmpty(), score = o.optInt("score"), rating = o.str("rating").orEmpty(),
            )
        }
    }
}
