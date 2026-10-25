package familytv.launcher.remote

import java.io.File

/**
 * Where [TvRemote] keeps its pairing: our private key and certificate, and the TV's certificate.
 * Small named blobs, so it can be backed by files, prefs or anything else.
 */
interface CredentialStore {
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
}

/**
 * Stores each blob as a file in [dir]. On Android pass an app-private directory that is not
 * backed up, e.g. `File(context.noBackupFilesDir, "tv-remote")`.
 */
class FileCredentialStore(private val dir: File) : CredentialStore {

    override fun read(name: String): ByteArray? = file(name).takeIf { it.isFile }?.readBytes()

    override fun write(name: String, bytes: ByteArray) {
        dir.mkdirs()
        // Write then rename, so a crash never leaves half a key behind.
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file(name))) {
            tmp.delete()
            error("Could not save $name")
        }
    }

    override fun delete(name: String) {
        file(name).delete()
    }

    private fun file(name: String) = File(dir, name)
}
