package chat.stoat.e2ee

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import chat.stoat.api.StoatAPI
import chat.stoat.api.internals.MessageInterceptor
import chat.stoat.api.internals.ULID
import chat.stoat.api.routes.channel.sendMessage
import chat.stoat.core.model.schemas.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * AIR Chat end-to-end encryption (E2EE).
 *
 * Every conversation (DM, group, server channel) has one or more symmetric channel keys that
 * only exist on members' devices. Message content is encrypted on the device with the channel
 * key before it leaves, and decrypted on the device when it arrives: the server only ever
 * stores and relays ciphertext.
 *
 * Channel keys are exchanged directly between devices with short control messages:
 *  - a *request* carries the requesting device's public identity key;
 *  - an *envelope* carries channel keys, encrypted for one device with a key derived by ECDH
 *    between the sender's and the recipient's identity keys.
 * Control messages are hidden in AIR Chat.
 *
 * All participants converge on the oldest channel key they know about, which is also used to
 * derive the key that encrypts voice and video calls.
 */
object AirE2EE {
    private const val TAG = "AirE2EE"

    const val VERSION = "AIRE2EE1"
    const val MESSAGE_PREFIX = "🔒 $VERSION.M."
    const val CONTROL_PREFIX = "🔑 $VERSION."
    private const val REQUEST_PREFIX = "${CONTROL_PREFIX}R."
    private const val ENVELOPE_PREFIX = "${CONTROL_PREFIX}E."
    /** Peer-to-peer signalling (threads & forums), sealed with the channel key. */
    const val P2P_PREFIX = "${CONTROL_PREFIX}P."

    /** Receives decrypted peer-to-peer signalling: (channelId, authorId, messageId, payload). */
    @Volatile
    var p2pSignalHandler: ((String, String?, String?, ByteArray) -> Unit)? = null

    /** Shown in place of a message whose key has not arrived yet. */
    const val PENDING_PLACEHOLDER = "🔒 …"

    /** The server rejects messages longer than this. */
    const val MAX_WIRE_LENGTH = 2000

    private const val REQUEST_MAX_AGE_MS = 15 * 60 * 1000L

    private lateinit var vault: AirVault
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private lateinit var identityPrivate: ByteArray
    private lateinit var identityPublic: ByteArray
    private lateinit var ownFingerprint: String

    /** channelId -> (keyId -> key) */
    private val channelKeys = mutableMapOf<String, MutableMap<String, ByteArray>>()
    private val disabledChannels = mutableSetOf<String>()

    /** channelId -> (device fingerprint -> userId) of devices seen in key exchanges. */
    private val channelDevices = mutableMapOf<String, MutableMap<String, String>>()
    private val answeredRequests = LinkedHashSet<String>()

    private val encryptedIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingCiphertext = ConcurrentHashMap<String, String>()
    private val requestedKeys = ConcurrentHashMap.newKeySet<String>()
    private val seenEnvelopes = ConcurrentHashMap.newKeySet<String>()

    /** Increments whenever a new channel key arrives, so the UI can retry pending messages. */
    private val _keyEpoch = MutableStateFlow(0)
    val keyEpoch: StateFlow<Int> = _keyEpoch.asStateFlow()

    /** Compose-observable revision for settings (enabled/disabled state, devices). */
    var revision by mutableIntStateOf(0)
        private set

    var isInstalled = false
        private set

    fun install(context: Context) {
        if (isInstalled) return
        vault = AirVault(context.applicationContext, "e2ee.vault")
        load()
        MessageInterceptor.incoming = ::incoming
        MessageInterceptor.outgoing = ::outgoing
        isInstalled = true
    }

    // ------------------------------------------------------------------------------------
    // Public API used by the UI
    // ------------------------------------------------------------------------------------

    fun isEnabled(channelId: String): Boolean = synchronized(lock) { channelId !in disabledChannels }

    fun setEnabled(channelId: String, enabled: Boolean) {
        synchronized(lock) {
            if (enabled) disabledChannels.remove(channelId) else disabledChannels.add(channelId)
            save()
        }
        revision++
    }

    fun hasKey(channelId: String): Boolean =
        synchronized(lock) { !channelKeys[channelId].isNullOrEmpty() }

    fun keyCount(channelId: String): Int = synchronized(lock) { channelKeys[channelId]?.size ?: 0 }

    /** Devices (fingerprint to userId) that took part in key exchanges in this channel. */
    fun devices(channelId: String): Map<String, String> =
        synchronized(lock) { channelDevices[channelId]?.toMap() ?: emptyMap() }

    fun ownSafetyNumber(): String = AirCrypto.safetyNumber(identityPublic)

    fun safetyNumberOf(fingerprint: String): String =
        fingerprint.uppercase().chunked(4).joinToString(" ")

    fun isEncrypted(messageId: String?): Boolean = messageId != null && messageId in encryptedIds

    fun isPending(messageId: String?): Boolean = messageId != null && messageId in pendingCiphertext

    fun isControl(content: String?): Boolean = content?.startsWith(CONTROL_PREFIX) == true

    fun isControl(message: Message): Boolean = isControl(message.content)

    /** Re-attempts decryption of a message that was waiting for its key. */
    fun retry(message: Message): Message? {
        val ciphertext = pendingCiphertext[message.id ?: return null] ?: return null
        val decrypted = incoming(message.copy(content = ciphertext))
        return if (isPending(message.id)) null else decrypted
    }

    /** Asks the other members' devices for every key of this channel. */
    fun requestKeys(channelId: String) {
        sendRequest(channelId, "*")
    }

    /**
     * Shared secret used to encrypt calls in this channel, or null when the channel is not
     * end-to-end encrypted (yet).
     */
    fun callKey(channelId: String): String? {
        if (!isEnabled(channelId)) return null
        val key = currentKey(channelId) ?: return null
        return AirCrypto.b64(AirCrypto.hkdf(key.second, channelId.toByteArray(), "AIR Chat call key"))
    }

    /** Encrypts small control payloads (e.g. peer-to-peer signalling) for a channel. */
    fun sealForChannel(channelId: String, payload: ByteArray, purpose: String): String? {
        val (keyId, key) = currentKey(channelId) ?: return null
        val sealed = AirCrypto.seal(key, AirCrypto.deflate(payload), "$purpose|$channelId|$keyId")
        return "$keyId.${AirCrypto.b64(sealed)}"
    }

    fun openForChannel(channelId: String, sealed: String, purpose: String): ByteArray? {
        val keyId = sealed.substringBefore('.', "")
        val body = sealed.substringAfter('.', "")
        val key = synchronized(lock) { channelKeys[channelId]?.get(keyId) } ?: run {
            requestKey(channelId, keyId)
            return null
        }
        return try {
            AirCrypto.inflate(AirCrypto.open(key, AirCrypto.unb64(body), "$purpose|$channelId|$keyId"))
        } catch (e: Exception) {
            null
        }
    }

    val ownDeviceFingerprint: String get() = ownFingerprint

    val ownPublicKey: String get() = AirCrypto.b64(identityPublic)

    /** Signs [data] with this device's identity key (ECDSA P-256). */
    fun sign(data: ByteArray): String {
        val signature = java.security.Signature.getInstance("SHA256withECDSA")
        signature.initSign(AirCrypto.privateKey(identityPrivate))
        signature.update(data)
        return AirCrypto.b64(signature.sign())
    }

    fun verify(publicKey: String, data: ByteArray, signatureB64: String): Boolean = try {
        val signature = java.security.Signature.getInstance("SHA256withECDSA")
        signature.initVerify(AirCrypto.publicKey(AirCrypto.unb64(publicKey)))
        signature.update(data)
        signature.verify(AirCrypto.unb64(signatureB64))
    } catch (e: Exception) {
        false
    }

    /** User known to own the device with this fingerprint in this channel, if any. */
    fun userOfDevice(channelId: String, fingerprint: String): String? =
        synchronized(lock) { channelDevices[channelId]?.get(fingerprint) }

    fun rememberDeviceOf(channelId: String, publicKey: String, userId: String?) {
        runCatching { rememberDevice(channelId, AirCrypto.fingerprint(AirCrypto.unb64(publicKey)), userId) }
    }

    /** Sends a sealed peer-to-peer signalling message in [channelId]. */
    suspend fun sendP2PSignal(channelId: String, payload: ByteArray): Boolean {
        if (!isEnabled(channelId)) return false
        if (currentKey(channelId) == null) createKey(channelId)
        val sealed = sealForChannel(channelId, payload, "P") ?: return false
        val content = "$P2P_PREFIX$sealed"
        if (content.length > MAX_WIRE_LENGTH) return false
        return try {
            sendMessage(channelId = channelId, content = content)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not send peer-to-peer signal", e)
            false
        }
    }

    // ------------------------------------------------------------------------------------
    // Interceptors
    // ------------------------------------------------------------------------------------

    private fun outgoing(channelId: String, content: String): String {
        if (content.startsWith(CONTROL_PREFIX) || content.startsWith(MESSAGE_PREFIX)) return content
        if (content.isEmpty() || !isEnabled(channelId)) return content

        val (keyId, key) = currentKey(channelId) ?: createKey(channelId)
        val sealed = AirCrypto.seal(
            key,
            AirCrypto.deflate(content.toByteArray(Charsets.UTF_8)),
            "M|$channelId|$keyId"
        )
        val wire = "$MESSAGE_PREFIX$keyId.${AirCrypto.b64(sealed)}"
        if (wire.length > MAX_WIRE_LENGTH) {
            throw MessageTooLongForEncryptionException()
        }
        return wire
    }

    private fun incoming(message: Message): Message {
        val content = message.content ?: return message
        val channelId = message.channel ?: return message

        if (content.startsWith(CONTROL_PREFIX)) {
            handleControl(message, content, channelId)
            return message
        }
        if (!content.startsWith(MESSAGE_PREFIX)) return message

        val body = content.removePrefix(MESSAGE_PREFIX)
        val keyId = body.substringBefore('.')
        val payload = body.substringAfter('.', "")
        val key = synchronized(lock) { channelKeys[channelId]?.get(keyId) }

        if (key == null) {
            message.id?.let { pendingCiphertext[it] = content }
            requestKey(channelId, keyId)
            return message.copy(content = PENDING_PLACEHOLDER)
        }

        return try {
            val plain = AirCrypto.inflate(
                AirCrypto.open(key, AirCrypto.unb64(payload), "M|$channelId|$keyId")
            )
            message.id?.let {
                encryptedIds.add(it)
                pendingCiphertext.remove(it)
            }
            message.copy(content = String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt message ${message.id}", e)
            message.id?.let { pendingCiphertext[it] = content }
            message.copy(content = PENDING_PLACEHOLDER)
        }
    }

    // ------------------------------------------------------------------------------------
    // Key exchange
    // ------------------------------------------------------------------------------------

    private fun handleControl(message: Message, content: String, channelId: String) {
        try {
            when {
                content.startsWith(REQUEST_PREFIX) -> handleRequest(message, content, channelId)
                content.startsWith(ENVELOPE_PREFIX) -> handleEnvelope(message, content, channelId)
                content.startsWith(P2P_PREFIX) -> {
                    val payload = openForChannel(channelId, content.removePrefix(P2P_PREFIX), "P")
                    if (payload != null) p2pSignalHandler?.invoke(channelId, message.author, message.id, payload)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid control message ${message.id}", e)
        }
    }

    private fun handleRequest(message: Message, content: String, channelId: String) {
        val parts = content.removePrefix(REQUEST_PREFIX).split('.')
        if (parts.size < 2) return
        val requesterPublic = AirCrypto.unb64(parts[0])
        val requestedKeyId = parts[1]
        val requesterFingerprint = AirCrypto.fingerprint(requesterPublic)

        rememberDevice(channelId, requesterFingerprint, message.author)
        if (requesterFingerprint == ownFingerprint) return

        val sentAt = message.id?.let { runCatching { ULID.asTimestamp(it) }.getOrNull() } ?: return
        if (System.currentTimeMillis() - sentAt > REQUEST_MAX_AGE_MS) return

        val keys = synchronized(lock) {
            val all = channelKeys[channelId] ?: return
            if (requestedKeyId == "*") all.toMap() else all.filterKeys { it == requestedKeyId }
        }
        if (keys.isEmpty()) return

        val answerId = "$channelId|$requesterFingerprint|${keys.keys.sorted().joinToString(",")}"
        synchronized(lock) { if (answerId in answeredRequests) return }

        scope.launch {
            // Let one member answer: wait a little, and skip if someone else already did.
            delay(Random.nextLong(300, 3000))
            if ("$channelId|$requesterFingerprint" in seenEnvelopes) return@launch
            synchronized(lock) {
                if (!answeredRequests.add(answerId)) return@launch
                while (answeredRequests.size > 500) answeredRequests.remove(answeredRequests.first())
                save()
            }
            sendEnvelope(channelId, requesterPublic, keys)
        }
    }

    private fun handleEnvelope(message: Message, content: String, channelId: String) {
        val parts = content.removePrefix(ENVELOPE_PREFIX).split('.')
        if (parts.size < 3) return
        val recipientFingerprint = parts[0]
        val senderPublic = AirCrypto.unb64(parts[1])
        val sealed = AirCrypto.unb64(parts[2])

        seenEnvelopes.add("$channelId|$recipientFingerprint")
        rememberDevice(channelId, AirCrypto.fingerprint(senderPublic), message.author)
        if (recipientFingerprint != ownFingerprint) return

        val wrapKey = AirCrypto.hkdf(
            AirCrypto.sharedSecret(identityPrivate, senderPublic),
            channelId.toByteArray(),
            "AIR Chat key envelope"
        )
        val json = JSONObject(
            String(AirCrypto.open(wrapKey, sealed, "E|$channelId|$recipientFingerprint"), Charsets.UTF_8)
        )
        var added = false
        synchronized(lock) {
            val map = channelKeys.getOrPut(channelId) { mutableMapOf() }
            json.keys().forEach { keyId ->
                if (keyId !in map) {
                    map[keyId] = AirCrypto.unb64(json.getString(keyId))
                    added = true
                }
            }
            if (added) save()
        }
        if (added) {
            _keyEpoch.value = _keyEpoch.value + 1
            revision++
        }
    }

    private fun requestKey(channelId: String, keyId: String) {
        if (!requestedKeys.add("$channelId|$keyId")) return
        sendRequest(channelId, keyId)
    }

    private fun sendRequest(channelId: String, keyId: String) {
        if (StoatAPI.selfId == null) return
        scope.launch {
            try {
                sendMessage(
                    channelId = channelId,
                    content = "$REQUEST_PREFIX${AirCrypto.b64(identityPublic)}.$keyId"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not request key", e)
                requestedKeys.remove("$channelId|$keyId")
            }
        }
    }

    private suspend fun sendEnvelope(
        channelId: String,
        recipientPublic: ByteArray,
        keys: Map<String, ByteArray>
    ) {
        val recipientFingerprint = AirCrypto.fingerprint(recipientPublic)
        val wrapKey = AirCrypto.hkdf(
            AirCrypto.sharedSecret(identityPrivate, recipientPublic),
            channelId.toByteArray(),
            "AIR Chat key envelope"
        )
        val payload = JSONObject().apply {
            keys.forEach { (id, key) -> put(id, AirCrypto.b64(key)) }
        }.toString().toByteArray(Charsets.UTF_8)
        val sealed = AirCrypto.seal(wrapKey, payload, "E|$channelId|$recipientFingerprint")
        try {
            sendMessage(
                channelId = channelId,
                content = "$ENVELOPE_PREFIX$recipientFingerprint.${AirCrypto.b64(identityPublic)}.${AirCrypto.b64(sealed)}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not send key envelope", e)
        }
    }

    private fun rememberDevice(channelId: String, fingerprint: String, userId: String?) {
        if (userId == null) return
        synchronized(lock) {
            val devices = channelDevices.getOrPut(channelId) { mutableMapOf() }
            if (devices[fingerprint] == userId) return
            devices[fingerprint] = userId
            save()
        }
        revision++
    }

    // ------------------------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------------------------

    /** Oldest known key: every device converges on it once keys have been exchanged. */
    private fun currentKey(channelId: String): Pair<String, ByteArray>? = synchronized(lock) {
        channelKeys[channelId]?.minByOrNull { it.key }?.let { it.key to it.value }
    }

    private fun createKey(channelId: String): Pair<String, ByteArray> {
        val created = synchronized(lock) {
            currentKey(channelId)?.let { return it }
            val keyId = "%012x".format(System.currentTimeMillis()) + AirCrypto.hex(AirCrypto.randomBytes(4))
            val key = AirCrypto.randomBytes(32)
            channelKeys.getOrPut(channelId) { mutableMapOf() }[keyId] = key
            save()
            keyId to key
        }
        _keyEpoch.value = _keyEpoch.value + 1
        revision++
        return created
    }

    // ------------------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------------------

    private fun load() {
        val json = vault.read()?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json == null || !json.has("identityPrivate")) {
            val (private, public) = AirCrypto.generateIdentity()
            identityPrivate = private
            identityPublic = public
            ownFingerprint = AirCrypto.fingerprint(public)
            save()
            return
        }

        identityPrivate = AirCrypto.unb64(json.getString("identityPrivate"))
        identityPublic = AirCrypto.unb64(json.getString("identityPublic"))
        ownFingerprint = AirCrypto.fingerprint(identityPublic)

        json.optJSONObject("channels")?.let { channels ->
            channels.keys().forEach { channelId ->
                val keys = channels.getJSONObject(channelId)
                channelKeys[channelId] = mutableMapOf<String, ByteArray>().apply {
                    keys.keys().forEach { put(it, AirCrypto.unb64(keys.getString(it))) }
                }
            }
        }
        json.optJSONArray("disabled")?.let { array ->
            for (i in 0 until array.length()) disabledChannels.add(array.getString(i))
        }
        json.optJSONObject("devices")?.let { devices ->
            devices.keys().forEach { channelId ->
                val map = devices.getJSONObject(channelId)
                channelDevices[channelId] = mutableMapOf<String, String>().apply {
                    map.keys().forEach { put(it, map.getString(it)) }
                }
            }
        }
        json.optJSONArray("answered")?.let { array ->
            for (i in 0 until array.length()) answeredRequests.add(array.getString(i))
        }
    }

    private fun save() {
        val json = JSONObject()
        json.put("identityPrivate", AirCrypto.b64(identityPrivate))
        json.put("identityPublic", AirCrypto.b64(identityPublic))
        json.put("channels", JSONObject().apply {
            channelKeys.forEach { (channelId, keys) ->
                put(channelId, JSONObject().apply {
                    keys.forEach { (id, key) -> put(id, AirCrypto.b64(key)) }
                })
            }
        })
        json.put("disabled", JSONArray(disabledChannels.toList()))
        json.put("devices", JSONObject().apply {
            channelDevices.forEach { (channelId, devices) -> put(channelId, JSONObject(devices.toMap())) }
        })
        json.put("answered", JSONArray(answeredRequests.toList()))
        try {
            vault.write(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Could not save E2EE state", e)
        }
    }
}

class MessageTooLongForEncryptionException :
    IllegalStateException("This message is too long to be end-to-end encrypted")
