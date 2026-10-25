package familytv.launcher.secrets

import familytv.launcher.testutil.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

/** Same encryption code as on the TV, with a software AES key standing in for the AndroidKeyStore one. */
class KeystoreSecretStoreTest {
    private val prefs = FakePrefs()
    private val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store = KeystoreSecretStore(prefs) { aesKey }

    // Obviously fake values: no real keys anywhere in the repo.
    private val anthropic = "sk-ant-test-not-a-real-key"
    private val tmdb = "test-tmdb-key-0000"

    @Test fun roundTripsAndReportsPresence() {
        assertFalse(store.hasAnthropicKey())
        assertFalse(store.hasTmdbKey())
        store.set(Secret.ANTHROPIC, "  $anthropic \n")
        store.set(Secret.TMDB, tmdb)
        assertEquals(anthropic, store.get(Secret.ANTHROPIC))
        assertEquals(tmdb, store.get(Secret.TMDB))
        assertTrue(store.hasAnthropicKey())
        assertTrue(store.hasTmdbKey())
    }

    @Test fun storesCiphertextNotPlaintext() {
        store.set(Secret.ANTHROPIC, anthropic)
        val stored = prefs.values[Secret.ANTHROPIC.prefName] as String
        assertFalse(stored.contains(anthropic))
        assertFalse(stored.contains("sk-ant"))
        // A fresh IV per write: same value, different ciphertext.
        store.set(Secret.ANTHROPIC, anthropic)
        assertNotEquals(stored, prefs.values[Secret.ANTHROPIC.prefName])
    }

    @Test fun clearAndBlankRemoveTheValue() {
        store.set(Secret.TMDB, tmdb)
        store.clear(Secret.TMDB)
        assertNull(store.get(Secret.TMDB))
        store.set(Secret.ANTHROPIC, anthropic)
        store.set(Secret.ANTHROPIC, "   ")
        assertNull(store.get(Secret.ANTHROPIC))
        assertFalse(prefs.contains(Secret.ANTHROPIC.prefName))
    }

    @Test fun ciphertextIsBoundToItsSlot() {
        store.set(Secret.ANTHROPIC, anthropic)
        prefs.values[Secret.TMDB.prefName] = prefs.values[Secret.ANTHROPIC.prefName]
        assertNull(store.get(Secret.TMDB)) // authentication fails, so the copied value is dropped
        assertFalse(prefs.contains(Secret.TMDB.prefName))
        assertEquals(anthropic, store.get(Secret.ANTHROPIC))
    }

    @Test fun tamperedOrGarbledValuesAreDropped() {
        store.set(Secret.ANTHROPIC, anthropic)
        val (iv, sealed) = (prefs.values[Secret.ANTHROPIC.prefName] as String).split(':')
        val flipped = sealed.replaceRange(0, 1, if (sealed[0] == 'A') "B" else "A")
        prefs.values[Secret.ANTHROPIC.prefName] = "$iv:$flipped"
        assertNull(store.get(Secret.ANTHROPIC))

        prefs.values[Secret.TMDB.prefName] = "not base64 at all"
        assertNull(store.get(Secret.TMDB))
        assertFalse(store.hasTmdbKey())
    }

    @Test fun aDifferentKeyCannotRead() {
        store.set(Secret.ANTHROPIC, anthropic)
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(KeystoreSecretStore(prefs) { otherKey }.get(Secret.ANTHROPIC))
    }
}
