package familytv.launcher.api

import familytv.launcher.Audience
import familytv.launcher.Show
import familytv.launcher.playback.PlayResult
import familytv.launcher.playback.PlaybackController
import familytv.launcher.voice.Resolution
import familytv.launcher.voice.ShowResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * One HTTP request, independent of NanoHTTPD so routing is unit-testable.
 *
 * @param headers lowercase names, as NanoHTTPD provides them
 * @param readBody reads the body; only called once the request passed every check that needs no body
 */
class ApiRequest(
    val method: String,
    val path: String,
    val remoteIp: String?,
    val headers: Map<String, String> = emptyMap(),
    val readBody: () -> ByteArray = { ByteArray(0) },
)

data class ApiResponse(val status: Int, val json: JSONObject, val headers: Map<String, String> = emptyMap())

/**
 * The local API's routes and checks. See docs/controller-api.md for the protocol.
 *
 * Order of checks: client address, browser guards (Origin/Host), route, content type and size, token,
 * then the body is read. One play runs at a time; if it takes longer than [playWaitMs] the request
 * answers 202 and the play carries on in [scope].
 *
 * @param onError told about crashes (in the controller or here); the client just gets a 500
 */
class ApiRouter(
    private val controller: PlaybackController,
    private val pairing: Pairing,
    private val kidsShows: () -> List<Show>,
    private val allShows: () -> List<Show>,
    private val scope: CoroutineScope,
    private val playWaitMs: Long = 15_000,
    private val onError: (Throwable) -> Unit = {},
    /** When false, codes can only be started on the TV (Parents → Devices), never by a LAN device. */
    private val allowNetworkPairStart: () -> Boolean = { true },
) {
    private enum class Route(val method: String, val path: String, val needsToken: Boolean = true) {
        STATUS("GET", "/status"),
        SHOWS("GET", "/shows"),
        PAIR_START("POST", "/pair/start", needsToken = false),
        PAIR_FINISH("POST", "/pair/finish", needsToken = false),
        PLAY("POST", "/play"),
        VOICE("POST", "/voice"),
        HOME("POST", "/home"),
    }

    private val playing = Mutex()

    suspend fun handle(request: ApiRequest): ApiResponse {
        rejectClient(request)?.let { return it }
        val route = Route.entries.firstOrNull { it.path == request.path } ?: return error(404, "no_route", "unknown endpoint")
        rejectRequest(request, route)?.let { return it }
        val body = try {
            request.readBody()
        } catch (e: IOException) {
            return error(400, "bad_request", "could not read the body")
        }
        if (body.size > MAX_BODY_BYTES) return error(413, "too_large", "body over $MAX_BODY_BYTES bytes")
        return try {
            when (route) {
                Route.STATUS -> status()
                Route.SHOWS -> ok("shows" to JSONArray(kidsShows().map(::showJson)))
                Route.PAIR_START -> pairStart()
                Route.PAIR_FINISH -> json(body)?.let(::pairFinish) ?: badJson()
                Route.PLAY -> json(body)?.let { play(it, it.string("query"), MAX_QUERY_CHARS, voice = false) } ?: badJson()
                Route.VOICE -> json(body)?.let { play(it, it.string("text"), MAX_TEXT_CHARS, voice = true) } ?: badJson()
                Route.HOME -> {
                    controller.goHome()
                    ok("result" to "home")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            error(500, "internal", "something went wrong on the TV")
        }
    }

    /** Who is asking: LAN only, and no browsers (controllers never send Origin; Host blocks DNS rebinding). */
    private fun rejectClient(request: ApiRequest): ApiResponse? = when {
        !LanAddress.isAllowed(request.remoteIp) -> error(403, "forbidden", "only clients on the home network")
        "origin" in request.headers || !LanAddress.isAllowedHost(request.headers["host"]) ->
            error(403, "forbidden", "browser requests are not accepted")
        else -> null
    }

    /** Everything else that needs no body: method, content type and size, token. */
    private fun rejectRequest(request: ApiRequest, route: Route): ApiResponse? {
        val headers = request.headers
        if (route.method != request.method) return error(405, "method_not_allowed", "use ${route.method} ${route.path}")
        if (request.method == "POST") {
            val type = headers["content-type"]?.substringBefore(';')?.trim()
            if (!type.equals("application/json", ignoreCase = true)) {
                return error(415, "unsupported_media_type", "send Content-Type: application/json")
            }
            if ("transfer-encoding" in headers) return error(411, "length_required", "send Content-Length, not chunked")
            val length = headers["content-length"]?.trim()?.let { it.toLongOrNull() ?: -1 } ?: 0
            if (length < 0) return error(400, "bad_request", "bad Content-Length")
            if (length > MAX_BODY_BYTES) return error(413, "too_large", "body over $MAX_BODY_BYTES bytes")
        }
        if (route.needsToken && pairing.authenticate(headers["authorization"]) == null) {
            return error(401, "unauthorized", "pair first, then send Authorization: Bearer <token>")
                .copy(headers = mapOf("WWW-Authenticate" to "Bearer"))
        }
        return null
    }

    private fun status(): ApiResponse {
        val s = controller.status()
        return ok(
            "screen" to s.screen,
            "nowPlaying" to s.nowPlaying,
            "bedtime" to s.bedtime,
            "minutesToBedtime" to s.minutesToBedtime,
        )
    }

    private fun pairStart(): ApiResponse {
        if (!allowNetworkPairStart()) {
            return error(403, "pair_on_tv", "start pairing on the TV: Parents → Devices → E-paper remote")
        }
        return pairStartNow()
    }

    private fun pairStartNow(): ApiResponse = when (val r = pairing.start()) {
        is Pairing.Start.Showing -> ok("result" to "code_shown", "expiresIn" to r.expiresInSeconds)
        is Pairing.Start.Locked -> locked(r.retryAfterSeconds)
    }

    private fun pairFinish(body: JSONObject): ApiResponse {
        val code = when (val c = body.opt("code")) {
            is String -> c
            is Int, is Long -> String.format(Locale.ROOT, "%06d", (c as Number).toLong())
            else -> return error(400, "bad_request", "\"code\" (6 digits) is required")
        }
        val name = body.string("deviceName") ?: return error(400, "bad_request", "\"deviceName\" is required")
        return when (val r = pairing.finish(code, name)) {
            is Pairing.Finish.Paired -> ok("token" to r.token, "deviceName" to r.deviceName)
            is Pairing.Finish.WrongCode -> response(401, "error" to "wrong_code", "attemptsLeft" to r.attemptsLeft)
            is Pairing.Finish.Locked -> locked(r.retryAfterSeconds)
            Pairing.Finish.NotPairing -> error(409, "not_pairing", "no code is showing; POST /pair/start first")
            Pairing.Finish.InvalidName -> error(400, "bad_request", "deviceName: 1-32 letters, digits, spaces, _ . ' -")
            Pairing.Finish.TooManyDevices -> error(409, "too_many_devices", "revoke a controller in Parents first")
        }
    }

    private suspend fun play(body: JSONObject, text: String?, maxChars: Int, voice: Boolean): ApiResponse {
        val field = if (voice) "text" else "query"
        if (text.isNullOrBlank() || text.length > maxChars) {
            return error(400, "bad_request", "\"$field\" is required, up to $maxChars characters")
        }
        val audience = when (body.opt("audience")) {
            null, JSONObject.NULL, "kids" -> Audience.KIDS
            "parents" -> Audience.PARENTS
            else -> return error(400, "bad_request", "\"audience\" is \"kids\" or \"parents\"")
        }
        // A kids request only ever sees the kids list, so it cannot reach a parents-only show.
        val shows = if (audience == Audience.KIDS) kidsShows() else allShows()
        val response = when (val r = ShowResolver(shows).resolve(text)) {
            Resolution.NotFound -> response(404, "result" to "not_found")
            is Resolution.Ambiguous -> response(409, "result" to "ambiguous", "candidates" to JSONArray(r.candidates.map(::showJson)))
            is Resolution.Match -> start(r.show, audience)
        }
        if (voice) response.json.put("heard", text)
        return response
    }

    private suspend fun start(show: Show, audience: Audience): ApiResponse {
        if (!playing.tryLock()) return error(503, "busy", "still opening the previous show")
        val job = scope.async {
            withTimeoutOrNull(PLAY_LIMIT_MS) { controller.play(show, audience) } ?: PlayResult.Failed(show, "timed out")
        }
        job.invokeOnCompletion { e ->
            playing.unlock()
            if (e != null && e !is CancellationException) onError(e)
        }
        val result = withTimeoutOrNull(playWaitMs) { job.await() }
            ?: return response(202, "result" to "started", "show" to showJson(show))
        return when (result) {
            is PlayResult.Playing -> response(200, "result" to "playing", "show" to showJson(result.show))
            is PlayResult.OpenedPage -> response(200, "result" to "opened_page", "show" to showJson(result.show))
            is PlayResult.Failed -> response(502, "result" to "failed", "show" to result.show?.let(::showJson), "reason" to result.reason)
        }
    }

    private fun showJson(show: Show) = obj("id" to show.id, "title" to show.title, "service" to show.service?.label)

    private fun locked(retryAfter: Long) = response(429, "error" to "locked", "retryAfter" to retryAfter)
        .copy(headers = mapOf("Retry-After" to retryAfter.toString()))

    private fun badJson() = error(400, "bad_request", "body must be a JSON object")

    companion object {
        const val MAX_BODY_BYTES = 4096
        private const val MAX_QUERY_CHARS = 200
        private const val MAX_TEXT_CHARS = 500

        /** A play still running after this is given up (and reported failed), so the API never stays busy. */
        private const val PLAY_LIMIT_MS = 60_000L

        private fun obj(vararg fields: Pair<String, Any?>) =
            JSONObject().apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }

        private fun response(status: Int, vararg fields: Pair<String, Any?>) = ApiResponse(status, obj(*fields))
        private fun ok(vararg fields: Pair<String, Any?>) = response(200, *fields)
        private fun error(status: Int, code: String, message: String) = response(status, "error" to code, "message" to message)

        private fun json(body: ByteArray): JSONObject? = try {
            val text = body.toString(Charsets.UTF_8).trim()
            if (text.isEmpty()) JSONObject() else JSONObject(text)
        } catch (e: JSONException) {
            null
        } catch (e: StackOverflowError) {
            null
        }

        /** A string field, or null when missing, JSON null or another type. */
        private fun JSONObject.string(key: String): String? = opt(key) as? String
    }
}
