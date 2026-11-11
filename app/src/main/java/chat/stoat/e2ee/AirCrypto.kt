package chat.stoat.e2ee

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AIR Chat cryptographic primitives.
 *
 * - Identity keys: ECDH on P-256 (available on every supported Android version).
 * - Key derivation: HKDF-SHA256.
 * - Symmetric encryption: AES-256-GCM with a random 96-bit IV.
 */
object AirCrypto {
    private val random = SecureRandom()
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun unb64(text: String): ByteArray =
        Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    // ---- Identity keys ----

    fun generateIdentity(): Pair<ByteArray, ByteArray> {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        return pair.private.encoded to pair.public.encoded
    }

    fun publicKey(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    fun privateKey(encoded: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))

    /** Short, human-comparable fingerprint of a public key. */
    fun fingerprint(publicKey: ByteArray): String = hex(sha256(publicKey).copyOfRange(0, 10))

    /** Pretty, grouped safety number for display. */
    fun safetyNumber(publicKey: ByteArray): String =
        fingerprint(publicKey).uppercase().chunked(4).joinToString(" ")

    fun sharedSecret(ownPrivate: ByteArray, peerPublic: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey(ownPrivate))
        agreement.doPhase(publicKey(peerPublic), true)
        return agreement.generateSecret()
    }

    // ---- HKDF ----

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            mac.update(previous)
            mac.update(info.toByteArray(Charsets.UTF_8))
            mac.update(counter.toByte())
            previous = mac.doFinal()
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOfRange(0, length)
    }

    // ---- AES-GCM ----

    fun seal(key: ByteArray, plaintext: ByteArray, aad: String): ByteArray {
        val iv = randomBytes(IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return iv + cipher.doFinal(plaintext)
    }

    fun open(key: ByteArray, sealed: ByteArray, aad: String): ByteArray {
        require(sealed.size > IV_LEN) { "Ciphertext too short" }
        val iv = sealed.copyOfRange(0, IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(sealed, IV_LEN, sealed.size - IV_LEN)
    }

    // ---- Compression (keeps encrypted messages within the server's length limit) ----

    fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }

    fun inflate(data: ByteArray, maxSize: Int = 1 shl 20): ByteArray {
        val inflater = Inflater(true)
        // "nowrap" inflaters need one extra dummy byte at the end of the input
        inflater.setInput(data + 0.toByte())
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, n)
            require(out.size() <= maxSize) { "Inflated data too large" }
        }
        inflater.end()
        return out.toByteArray()
    }
}
