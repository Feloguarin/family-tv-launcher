package familytv.launcher.api

/**
 * Who may talk to the local API.
 *
 * Clients: loopback and private LAN only (127/8, 10/8, 172.16/12, 192.168/16, ::1, fe80::/10, fc00::/7,
 * and IPv4-mapped IPv6 forms of those). Literals are parsed here, so a name can never trigger a DNS lookup.
 *
 * Host header: guards against DNS rebinding, where a web page on attacker.com re-points its own name at
 * the TV so the browser sends it our way. Such requests carry `Host: attacker.com`, so only IP literals
 * and local names are accepted.
 */
object LanAddress {

    fun isAllowed(ip: String?): Boolean = ip?.let(::parse)?.let(::isAllowed) ?: false

    fun isAllowed(address: ByteArray): Boolean = when (address.size) {
        4 -> isPrivateV4(address)
        16 -> isPrivateV6(address)
        else -> false
    }

    /** True for a missing Host (HTTP/1.0), IP literals, localhost, single-label names and .local/.lan/.home.arpa/.internal. */
    fun isAllowedHost(hostHeader: String?): Boolean {
        if (hostHeader == null) return true
        val value = hostHeader.trim().lowercase()
        val host = when {
            value.startsWith("[") -> value.substringBefore(']') + "]"
            value.count { it == ':' } == 1 -> value.substringBefore(':')
            else -> value
        }.trimEnd('.')
        if (host.isEmpty()) return false
        if (parse(host) != null) return true
        if (!host.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' }) return false
        return '.' !in host || LOCAL_SUFFIXES.any { host.endsWith(it) }
    }

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal")

    private fun isPrivateV4(b: ByteArray): Boolean {
        val first = b[0].toInt() and 0xff
        val second = b[1].toInt() and 0xff
        return first == 127 || first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
    }

    private fun isPrivateV6(b: ByteArray): Boolean {
        val first = b[0].toInt() and 0xff
        val second = b[1].toInt() and 0xff
        val zeros = { until: Int -> (0 until until).all { b[it] == 0.toByte() } }
        return when {
            zeros(15) && b[15] == 1.toByte() -> true // ::1
            first == 0xfe && (second and 0xc0) == 0x80 -> true // fe80::/10 link-local
            (first and 0xfe) == 0xfc -> true // fc00::/7 unique local
            zeros(10) && b[10] == 0xff.toByte() && b[11] == 0xff.toByte() -> isPrivateV4(b.copyOfRange(12, 16))
            else -> false
        }
    }

    /** Parses an IPv4 or IPv6 literal (brackets and a zone id like `%wlan0` allowed). Null for anything else. */
    internal fun parse(text: String): ByteArray? {
        val s = text.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
        return if (':' in s) parseV6(s) else parseV4(s)
    }

    private fun parseV4(s: String): ByteArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v.toByte()
        }
        return out
    }

    private fun parseV6(input: String): ByteArray? {
        var s = input
        if ('.' in s) { // embedded IPv4 tail: ::ffff:192.168.0.5
            val cut = s.lastIndexOf(':')
            val v4 = parseV4(s.substring(cut + 1)) ?: return null
            val hi = ((v4[0].toInt() and 0xff) shl 8) or (v4[1].toInt() and 0xff)
            val lo = ((v4[2].toInt() and 0xff) shl 8) or (v4[3].toInt() and 0xff)
            s = s.substring(0, cut + 1) + hi.toString(16) + ":" + lo.toString(16)
        }
        fun groups(part: String): List<Int>? = if (part.isEmpty()) emptyList() else part.split(':').map { g ->
            if (g.isEmpty() || g.length > 4 || !g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            g.toInt(16)
        }
        val halves = s.split("::")
        val all = when (halves.size) {
            1 -> groups(s) ?: return null
            2 -> {
                val head = groups(halves[0]) ?: return null
                val tail = groups(halves[1]) ?: return null
                val missing = 8 - head.size - tail.size
                if (missing < 1) return null
                head + List(missing) { 0 } + tail
            }
            else -> return null
        }
        if (all.size != 8) return null
        val out = ByteArray(16)
        all.forEachIndexed { i, g ->
            out[2 * i] = (g shr 8).toByte()
            out[2 * i + 1] = g.toByte()
        }
        return out
    }
}
