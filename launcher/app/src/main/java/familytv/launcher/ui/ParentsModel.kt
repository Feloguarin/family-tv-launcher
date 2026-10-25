package familytv.launcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import familytv.launcher.Audience
import familytv.launcher.Catalog
import familytv.launcher.KidsApp
import familytv.launcher.Service
import familytv.launcher.bedtime.BedtimeRules
import familytv.launcher.bedtime.BedtimeStore
import familytv.launcher.history.WatchLog
import familytv.launcher.recs.Pick
import familytv.launcher.recs.Recommender
import familytv.launcher.recs.Suggestion
import familytv.launcher.remote.LocalAdbKeys
import familytv.launcher.secrets.Secret
import familytv.launcher.secrets.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The TV control row's value. "On" also covers "not checked yet". */
fun tvControlLabel(enabled: Boolean, status: LocalAdbKeys.Status?): String = when {
    !enabled -> "Off"
    status == LocalAdbKeys.Status.NeedsApproval -> "Waiting for approval"
    status is LocalAdbKeys.Status.Unavailable -> "Not available"
    else -> "On"
}

/** A watched show for "Continue Watching": name and when it was last opened. */
data class RecentShow(val showId: String, val title: String, val service: Service?, val lastWatched: String)

/** Everything the Parents screen shows and changes. Reads happen off the main thread. */
class ParentsModel(private val app: KidsApp) {
    private val bedtimeStore = BedtimeStore(app)
    private val secrets = SecretStore.create(app)

    var recent by mutableStateOf<List<RecentShow>>(emptyList()); private set
    var picks by mutableStateOf<List<Pick>>(emptyList()); private set
    var picksLoading by mutableStateOf(false); private set
    var pending by mutableStateOf<List<Suggestion>>(emptyList()); private set
    var bedtime by mutableStateOf(bedtimeStore.rules); private set
    var hasClaudeKey by mutableStateOf(false); private set
    var hasTmdbKey by mutableStateOf(false); private set
    val kidsShowCount: Int get() = Catalog.ourShows.size
    var controllers by mutableStateOf<List<String>>(emptyList()); private set
    private val taste = familytv.launcher.recs.TasteStore(app)
    var favorites by mutableStateOf(taste.favorites); private set

    /** Saves "Shows we love" and fetches fresh picks from them right away. */
    fun saveFavorites(text: String) {
        taste.favorites = familytv.launcher.recs.TasteStore.parse(text)
        favorites = taste.favorites
        refresh(force = true)
    }

    // ---- TV control (Parents → Devices) --------------------------------------------------------

    var tvControlOn by mutableStateOf(app.tvControlEnabled); private set
    var tvControlBusy by mutableStateOf(false); private set

    /** Why the last "Turn on" didn't work, shown in the sheet. */
    var tvControlNote by mutableStateOf<String?>(null); private set
    val tvControlStatus: StateFlow<LocalAdbKeys.Status?> get() = app.tvControl.status

    /** Connects (the TV shows its prompt for a new key) and turns TV control on unless nothing answers. */
    fun turnOnTvControl() {
        if (tvControlBusy) return
        tvControlBusy = true
        tvControlNote = null
        app.scope.launch {
            try {
                when (val s = app.tvControl.connect(askAgain = true)) {
                    is LocalAdbKeys.Status.Unavailable -> tvControlNote = s.reason
                    else -> {
                        app.tvControlEnabled = true
                        tvControlOn = true
                    }
                }
            } finally {
                tvControlBusy = false
            }
        }
    }

    /** Turns it off and deletes the launcher's key, so turning on again asks the TV again. */
    fun turnOffTvControl() {
        app.tvControlEnabled = false
        tvControlOn = false
        tvControlNote = null
        app.scope.launch(Dispatchers.IO) { app.tvControl.forget() }
    }

    fun pairController() {
        app.api.startPairing()
    }

    fun revokeController(name: String) {
        app.api.revoke(name)
        controllers = app.api.listPairedDevices().map { it.name }
    }

    fun refresh(force: Boolean = false) {
        tvControlOn = app.tvControlEnabled
        // Keeps the TV control row truthful; cheap when already connected.
        if (tvControlOn) app.scope.launch { app.tvControl.connect() }
        app.scope.launch {
            val log = WatchLog.get(app)
            val rec = Recommender.get(app)
            withContext(Dispatchers.IO) {
                val entries = log.recent(Audience.PARENTS, 3)
                val now = System.currentTimeMillis()
                recent = entries.map { RecentShow(it.showId, it.title, it.service, "Last watched " + WatchLog.whenLabel(it.at, now, java.time.ZoneId.systemDefault())) }
                hasClaudeKey = secrets.hasAnthropicKey()
                hasTmdbKey = secrets.hasTmdbKey()
                pending = rec.pendingKids()
                controllers = app.api.listPairedDevices().map { it.name }
            }
            if (hasClaudeKey && hasTmdbKey) {
                picksLoading = picks.isEmpty()
                picksLoading = force || picks.isEmpty()
                picks = runCatching { rec.parentsPicks(forceRefresh = force) }.getOrDefault(picks)
                runCatching { rec.refreshKidsSuggestions() }
                pending = withContext(Dispatchers.IO) { rec.pendingKids() }
                picksLoading = false
            }
        }
    }

    fun saveBedtime(rules: BedtimeRules) {
        bedtimeStore.rules = rules
        bedtime = rules
    }

    fun approve(s: Suggestion) = decide { Recommender.get(app).approve(s.id) }
    fun skip(s: Suggestion) = decide { Recommender.get(app).reject(s.id) }

    private fun decide(action: () -> Unit) {
        app.scope.launch(Dispatchers.IO) {
            action()
            pending = Recommender.get(app).pendingKids()
        }
    }

    fun saveKey(secret: Secret, value: String) {
        val v = value.trim()
        if (v.isEmpty()) secrets.clear(secret) else secrets.set(secret, v)
        hasClaudeKey = secrets.hasAnthropicKey()
        hasTmdbKey = secrets.hasTmdbKey()
        refresh()
    }
}
