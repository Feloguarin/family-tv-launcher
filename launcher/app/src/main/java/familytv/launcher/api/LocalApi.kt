package familytv.launcher.api

import android.content.Context
import android.util.Log
import familytv.launcher.Show
import familytv.launcher.playback.PlaybackController
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.Socket

/**
 * The launcher's local HTTP API on port [PORT] for the e-paper controller and, later, voice.
 * Protocol: docs/controller-api.md.
 *
 * Keep one instance for the life of the process: [start] it once, and show [pairingCode] on screen
 * whenever it is non-null. Requests run on the server's own threads, so [controller] must be thread-safe.
 *
 * @param kidsShows what a kids request (the default) may play
 * @param allShows what a parents request may play
 */
class LocalApi(
    context: Context,
    controller: PlaybackController,
    kidsShows: () -> List<Show>,
    allShows: () -> List<Show>,
    private val port: Int = PORT,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pairing = Pairing(PrefsTokenStore(context), scope = scope)
    private val router = ApiRouter(
        controller, pairing, kidsShows, allShows, scope,
        onError = { Log.e(TAG, "Request failed", it) },
        // Codes only appear when a parent starts pairing on the TV, so LAN devices can't pop them up.
        allowNetworkPairStart = { false },
    )
    private var server: ApiHttpServer? = null

    /** The 6-digit code to show on the TV while a controller is pairing; null otherwise. */
    val pairingCode: StateFlow<String?> = pairing.code

    /** Starts listening. False if the port could not be opened (logged). Safe to call twice. */
    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        return try {
            server = ApiHttpServer(port, router).also { it.start(SOCKET_TIMEOUT_MS, true) }
            Log.i(TAG, "Local API listening on :$port")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Local API could not start on :$port", e)
            false
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        pairing.cancel()
    }

    /** Shows a pairing code from the TV side (e.g. "Pair a controller" in Parents), as POST /pair/start does. */
    fun startPairing(): Pairing.Start = pairing.start()

    fun cancelPairing() = pairing.cancel()

    fun listPairedDevices(): List<PairedDevice> = pairing.devices().map { PairedDevice(it.name, it.pairedAt) }

    /** Forgets a controller; its token stops working at once. */
    fun revoke(deviceName: String): Boolean = pairing.revoke(deviceName)

    companion object {
        const val PORT = 8765
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val TAG = "LocalApi"
    }
}

/** NanoHTTPD glue: drops non-LAN sockets before reading a byte, caps connections, hands requests to [router]. */
internal class ApiHttpServer(port: Int, private val router: ApiRouter) : NanoHTTPD(port) {

    init {
        setAsyncRunner(BoundedRunner(MAX_CONNECTIONS))
    }

    override fun createClientHandler(socket: Socket, input: InputStream): ClientHandler =
        if (LanAddress.isAllowed(socket.inetAddress?.address ?: ByteArray(0))) {
            super.createClientHandler(socket, input)
        } else {
            Log.w(TAG, "Refused ${socket.inetAddress?.hostAddress}")
            Refused(input, socket)
        }

    /** Closes a refused connection without serving it. */
    inner class Refused(input: InputStream, socket: Socket) : ClientHandler(input, socket) {
        override fun run() = close()
    }

    override fun serve(session: IHTTPSession): Response {
        val request = ApiRequest(session.method.name, session.uri, session.remoteIpAddress, session.headers) {
            readBody(session)
        }
        val reply = try {
            runBlocking { router.handle(request) }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed", e)
            ApiResponse(500, JSONObject().put("error", "internal").put("message", "something went wrong on the TV"))
        }
        Log.i(TAG, "${session.method} ${session.uri} -> ${reply.status} (${session.remoteIpAddress})")
        return newFixedLengthResponse(status(reply.status), "application/json; charset=utf-8", reply.json.toString()).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            reply.headers.forEach(::addHeader)
            // One request per connection: unread bodies can't leak into a next request, and slots free up fast.
            closeConnection(true)
        }
    }

    override fun useGzipWhenAccepted(r: Response) = false

    /** Reads exactly Content-Length bytes; the router has already checked it is at most 4 KB. */
    private fun readBody(session: IHTTPSession): ByteArray {
        val length = session.headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        if (length !in 0..ApiRouter.MAX_BODY_BYTES) throw IOException("bad length")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = session.inputStream.read(body, read, length - read)
            if (n < 0) throw IOException("body ended early")
            read += n
        }
        return body
    }

    private fun status(code: Int) = object : Response.IStatus {
        override fun getRequestStatus() = code
        override fun getDescription() = "$code ${REASONS[code] ?: "Status"}"
    }

    /** At most [max] connections at once (one thread each); extra ones are closed straight away. */
    private class BoundedRunner(private val max: Int) : AsyncRunner {
        private val running = mutableListOf<ClientHandler>()

        override fun exec(handler: ClientHandler) {
            if (handler is Refused) return handler.run()
            synchronized(running) {
                if (running.size >= max) return handler.close()
                running += handler
            }
            Thread(handler, "LocalApi").apply { isDaemon = true }.start()
        }

        override fun closed(handler: ClientHandler) {
            synchronized(running) { running -= handler }
        }

        override fun closeAll() {
            synchronized(running) { running.toList() }.forEach { it.close() }
        }
    }

    companion object {
        private const val TAG = "LocalApi"
        private const val MAX_CONNECTIONS = 8
        private val REASONS = mapOf(
            200 to "OK", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
            404 to "Not Found", 405 to "Method Not Allowed", 409 to "Conflict", 411 to "Length Required",
            413 to "Payload Too Large", 415 to "Unsupported Media Type", 429 to "Too Many Requests",
            500 to "Internal Server Error", 502 to "Bad Gateway", 503 to "Service Unavailable",
        )
    }
}
