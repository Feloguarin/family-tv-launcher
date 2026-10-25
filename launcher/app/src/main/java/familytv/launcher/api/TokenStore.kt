package familytv.launcher.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A paired controller as shown in Parents. */
data class PairedDevice(val name: String, val pairedAt: Long)

/** A paired controller as stored: only the SHA-256 (hex) of its token, never the token itself. */
data class StoredDevice(val name: String, val tokenHash: String, val pairedAt: Long)

/** Where paired devices are kept. An interface so pairing and auth are unit-testable without Android. */
interface TokenStore {
    fun load(): List<StoredDevice>
    fun save(devices: List<StoredDevice>)
}

class InMemoryTokenStore(initial: List<StoredDevice> = emptyList()) : TokenStore {
    @Volatile var devices = initial
        private set

    override fun load() = devices
    override fun save(devices: List<StoredDevice>) {
        this.devices = devices.toList()
    }
}

/** App-private SharedPreferences (the manifest has allowBackup=false, so they stay on the TV). */
class PrefsTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences("local_api_devices", Context.MODE_PRIVATE)

    override fun load(): List<StoredDevice> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map {
            val o = array.getJSONObject(it)
            StoredDevice(o.getString("name"), o.getString("hash"), o.getLong("pairedAt"))
        }
    }.getOrDefault(emptyList())

    override fun save(devices: List<StoredDevice>) {
        val array = JSONArray()
        devices.forEach { array.put(JSONObject().put("name", it.name).put("hash", it.tokenHash).put("pairedAt", it.pairedAt)) }
        prefs.edit().putString(KEY, array.toString()).commit()
    }

    private companion object {
        const val KEY = "devices"
    }
}
