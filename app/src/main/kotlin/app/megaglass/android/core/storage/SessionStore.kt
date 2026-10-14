package app.megaglass.android.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the MEGA session (from dumpSession) encrypted with an AES-256-GCM key that never
 * leaves the Android Keystore. The file is excluded from backups (see data_extraction_rules).
 */
class SessionStore(context: Context) {

    private val file = File(context.noBackupFilesDir, "session.bin")

    fun save(session: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(session.toByteArray())
        val tmp = File(file.parentFile, "session.tmp")
        tmp.writeText(Base64.encodeToString(data, Base64.NO_WRAP))
        tmp.renameTo(file)
    }

    fun load(): String? = runCatching {
        if (!file.exists()) return null
        val data = Base64.decode(file.readText(), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_SIZE))
        String(cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE))
    }.getOrNull()

    fun clear() {
        file.delete()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "megaglass.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
