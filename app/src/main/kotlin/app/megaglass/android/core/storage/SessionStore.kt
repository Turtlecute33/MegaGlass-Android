package app.megaglass.android.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.AEADBadTagException
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the MEGA session (from dumpSession) encrypted with an AES-256-GCM key that never
 * leaves the Android Keystore. The file is excluded from backups (see data_extraction_rules).
 */
class SessionStore(context: Context) {

    private val file = AtomicFile(File(context.noBackupFilesDir, "session.bin"))

    @Synchronized
    fun save(session: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(session.toByteArray())
        val encoded = Base64.encode(data, Base64.NO_WRAP)
        val out = file.startWrite()
        try {
            out.write(encoded)
            file.finishWrite(out)
            if (!file.readFully().contentEquals(encoded)) throw IOException("Cannot store the saved session")
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    @Synchronized
    fun load(): String? {
        if (!file.baseFile.exists()) return null
        // IO or Keystore availability errors are recoverable; they must not be mistaken for
        // a missing session and trigger deletion of local account data.
        val encoded = file.readFully()
        val data = try { Base64.decode(encoded, Base64.NO_WRAP) } catch (_: IllegalArgumentException) { return null }
        if (data.size < IV_SIZE + 16) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_SIZE))
        return try { String(cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)) }
        catch (_: AEADBadTagException) { null }
    }

    @Synchronized
    fun clear() {
        file.delete()
        val legacy = File(file.baseFile.parentFile, "session.tmp")
        legacy.delete()
        if (listOf(file.baseFile, File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new"), legacy).any { it.exists() }) {
            throw IOException("Cannot remove the saved session")
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        // StrongBox first, then the TEE (MEGAGLASS-SEC-009). ProviderException also covers
        // StrongBoxUnavailableException and vendor StrongBox faults.
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (_: ProviderException) {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    private fun spec(strongBox: Boolean) =
        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .build()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "megaglass.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
