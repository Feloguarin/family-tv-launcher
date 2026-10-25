package familytv.launcher.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.Random

/**
 * Pairing and token auth for the local API.
 *
 * A controller asks to pair, the TV shows a 6-digit [code] for 2 minutes, and a person types it on the
 * controller. The right code returns a random 32-byte token (base64url); only its SHA-256 is stored.
 *
 * Brute force: wrong codes add up across codes (an expiring code does not reset them). The 5th kills the
 * current code and locks pairing for 1 minute; each further lockout doubles, up to 1 hour, until a
 * pairing succeeds. Asking to pair again while a code is showing keeps the same code.
 *
 * @param clock monotonic milliseconds, for expiry and lockouts
 * @param scope clears [code] when it expires; null in tests that only use [clock]
 */
class Pairing(
    private val store: TokenStore,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom(),
    private val scope: CoroutineScope? = null,
) {
    sealed interface Start {
        data class Showing(val expiresInSeconds: Long) : Start
        data class Locked(val retryAfterSeconds: Long) : Start
    }

    sealed interface Finish {
        /** [token] goes to the controller once; the TV keeps only its hash. */
        data class Paired(val token: String, val deviceName: String) : Finish
        data class WrongCode(val attemptsLeft: Int) : Finish
        data class Locked(val retryAfterSeconds: Long) : Finish
        data object NotPairing : Finish
        data object InvalidName : Finish
        data object TooManyDevices : Finish
    }

    private val current = MutableStateFlow<String?>(null)

    /** The code to show on the TV while a controller is pairing; null otherwise. */
    val code: StateFlow<String?> = current.asStateFlow()

    private var expiresAt = 0L
    private var failures = 0
    private var lockouts = 0
    private var lockedUntil = 0L
    private var expiryJob: Job? = null
    private var cache: List<StoredDevice>? = null

    @Synchronized
    fun start(): Start {
        val now = clock()
        if (now < lockedUntil) return Start.Locked(secondsUntil(lockedUntil, now))
        expireIfStale(now)
        if (current.value == null) {
            current.value = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))
            expiresAt = now + CODE_TTL_MS
            expiryJob?.cancel()
            expiryJob = scope?.launch {
                delay(CODE_TTL_MS)
                synchronized(this@Pairing) { expireIfStale(clock()) }
            }
        }
        return Start.Showing(secondsUntil(expiresAt, now))
    }

    /** Hides the code (e.g. the parent dismissed it on the TV). */
    @Synchronized
    fun cancel() = clearCode()

    @Synchronized
    fun finish(code: String, deviceName: String): Finish {
        val now = clock()
        if (now < lockedUntil) return Finish.Locked(secondsUntil(lockedUntil, now))
        val name = deviceName.trim()
        if (!DEVICE_NAME.matches(name)) return Finish.InvalidName
        expireIfStale(now)
        val expected = current.value ?: return Finish.NotPairing
        if (devices().none { it.name == name } && devices().size >= MAX_DEVICES) return Finish.TooManyDevices

        if (!MessageDigest.isEqual(code.trim().toByteArray(), expected.toByteArray())) {
            failures++
            if (failures < MAX_FAILURES) return Finish.WrongCode(MAX_FAILURES - failures)
            lockedUntil = now + (LOCKOUT_MS shl lockouts.coerceAtMost(6)).coerceAtMost(MAX_LOCKOUT_MS)
            lockouts++
            failures = 0
            clearCode()
            return Finish.Locked(secondsUntil(lockedUntil, now))
        }

        clearCode()
        failures = 0
        lockouts = 0
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        save(devices().filter { it.name != name } + StoredDevice(name, sha256(token), wallClock()))
        return Finish.Paired(token, name)
    }

    /** The paired device's name for an `Authorization: Bearer <token>` header, or null. */
    @Synchronized
    fun authenticate(authorization: String?): String? {
        val header = authorization?.trim() ?: return null
        if (header.length > MAX_AUTH_HEADER || !header.startsWith("Bearer ", ignoreCase = true)) return null
        val token = header.substring(7).trim()
        if (token.isEmpty()) return null
        val hash = sha256(token).toByteArray()
        var match: String? = null
        // Constant time: hash first, compare with isEqual, and never stop early.
        for (device in devices()) if (MessageDigest.isEqual(hash, device.tokenHash.toByteArray())) match = device.name
        return match
    }

    @Synchronized
    fun devices(): List<StoredDevice> = cache ?: store.load().also { cache = it }

    @Synchronized
    fun revoke(deviceName: String): Boolean {
        val before = devices()
        val after = before.filter { it.name != deviceName }
        if (after.size == before.size) return false
        save(after)
        return true
    }

    private fun save(devices: List<StoredDevice>) {
        store.save(devices)
        cache = devices
    }

    private fun expireIfStale(now: Long) {
        if (current.value != null && now >= expiresAt) clearCode()
    }

    private fun clearCode() {
        current.value = null
        expiryJob?.cancel()
        expiryJob = null
    }

    private fun secondsUntil(at: Long, now: Long) = ((at - now + 999) / 1000).coerceAtLeast(1)

    companion object {
        const val CODE_TTL_MS = 2 * 60_000L
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 60_000L
        const val MAX_LOCKOUT_MS = 60 * 60_000L
        const val MAX_DEVICES = 16
        private const val TOKEN_BYTES = 32
        private const val MAX_AUTH_HEADER = 256

        /** 1–32 letters, digits, spaces, `_ . ' -` (shown in Parents; no control characters or markup). */
        private val DEVICE_NAME = Regex("[\\p{L}\\p{N} _.'-]{1,32}")

        internal fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { ((it.toInt() and 0xff) or 0x100).toString(16).substring(1) }
    }
}
