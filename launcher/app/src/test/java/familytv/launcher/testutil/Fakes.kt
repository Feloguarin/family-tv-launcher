package familytv.launcher.testutil

import android.content.SharedPreferences
import familytv.launcher.secrets.Secret
import familytv.launcher.secrets.SecretStore

/** Plain in-memory secrets for JVM tests (no AndroidKeyStore here). */
class InMemorySecretStore(vararg initial: Pair<Secret, String>) : SecretStore {
    private val values = mutableMapOf(*initial)
    override fun get(secret: Secret) = values[secret]
    override fun set(secret: Secret, value: String) {
        if (value.isBlank()) values.remove(secret) else values[secret] = value.trim()
    }
    override fun clear(secret: Secret) {
        values.remove(secret)
    }
}

/** Minimal SharedPreferences that keeps strings in a map (enough for the secret store). */
class FakePrefs : SharedPreferences {
    val values = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = defValue
    override fun getLong(key: String, defValue: Long) = defValue
    override fun getFloat(key: String, defValue: Float) = defValue
    override fun getBoolean(key: String, defValue: Boolean) = defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { removals += values.keys }
        override fun commit(): Boolean {
            removals.forEach { values.remove(it) }
            values.putAll(changes)
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
