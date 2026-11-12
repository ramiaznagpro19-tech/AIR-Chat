package chat.stoat.e2ee

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AIR Chat: small encrypted-at-rest storage for secrets (identity key, channel keys) and for
 * local peer-to-peer data. Files are encrypted with an AES-256-GCM key that never leaves the
 * Android Keystore.
 */
class AirVault(context: Context, private val fileName: String) {
    private val dir = File(context.filesDir, "air_chat").apply { mkdirs() }
    private val file = File(dir, fileName)
    private val fallbackKeyFile = File(context.noBackupFilesDir, "air_chat_fallback.key")

    /**
     * Some emulators (BlueStacks, …) have a broken Android Keystore. There, a key stored in the
     * app's private storage is used instead.
     */
    private fun fallbackKey(): SecretKey = synchronized(LOCK) {
        if (!fallbackKeyFile.exists()) {
            fallbackKeyFile.writeBytes(AirCrypto.randomBytes(32))
        }
        javax.crypto.spec.SecretKeySpec(fallbackKeyFile.readBytes(), "AES")
    }

    private fun keyOrFallback(): SecretKey = try {
        masterKey()
    } catch (e: Exception) {
        fallbackKey()
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun read(): String? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val iv = bytes.copyOfRange(0, 12)
            fun decryptWith(key: SecretKey): String {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
            }
            try {
                decryptWith(masterKey())
            } catch (e: Exception) {
                decryptWith(fallbackKey())
            }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun write(content: String) {
        val sealed = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyOrFallback())
            cipher.iv + cipher.doFinal(content.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, fallbackKey())
            cipher.iv + cipher.doFinal(content.toByteArray(Charsets.UTF_8))
        }
        val tmp = File(file.parentFile, "$fileName.tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(file)) {
            file.writeBytes(sealed)
            tmp.delete()
        }
    }

    companion object {
        private const val KEY_ALIAS = "air_chat_vault_master"
        private val LOCK = Any()
    }
}
