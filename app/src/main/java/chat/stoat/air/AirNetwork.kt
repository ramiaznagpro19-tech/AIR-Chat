package chat.stoat.air

import android.content.Context
import android.util.Log
import chat.stoat.e2ee.AirCrypto
import chat.stoat.e2ee.AirVault
import io.livekit.android.LiveKit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AirContact(val publicKey: String, val username: String)

data class AirConversation(
    val id: String,
    val isGroup: Boolean,
    val name: String,
    val members: Set<String>,
    val createdAt: Long,
)

/**
 * A message, a thread/forum post or a reply. Items are signed by their author, so they can be
 * relayed between members without anyone being able to forge or alter them.
 */
data class AirItem(
    val id: String,
    val conversation: String,
    val author: String,
    val createdAt: Long,
    val type: String,
    val text: String,
    val title: String = "",
    val tags: List<String> = emptyList(),
    val parent: String? = null,
    val signature: String = "",
) {
    fun signedBytes(): ByteArray = AirCrypto.sha256(
        JSONArray().apply {
            put(id); put(conversation); put(author); put(createdAt); put(type); put(text)
            put(title); put(JSONArray(tags)); put(parent ?: "")
        }.toString().toByteArray(Charsets.UTF_8)
    )

    fun isValid(): Boolean = runCatching {
        Secp256k1.verifySchnorr(signedBytes(), Secp256k1.unhex(author), Secp256k1.unhex(signature))
    }.getOrDefault(false)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("conversation", conversation); put("author", author)
        put("createdAt", createdAt); put("type", type); put("text", text); put("title", title)
        put("tags", JSONArray(tags)); put("parent", parent ?: ""); put("signature", signature)
    }

    companion object {
        const val MESSAGE = "msg"
        const val THREAD = "thread"
        const val FORUM = "forum"
        const val REPLY = "reply"

        fun fromJson(json: JSONObject) = AirItem(
            id = json.getString("id"),
            conversation = json.getString("conversation"),
            author = json.getString("author"),
            createdAt = json.getLong("createdAt"),
            type = json.getString("type"),
            text = json.optString("text").take(8000),
            title = json.optString("title").take(200),
            tags = json.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?: emptyList(),
            parent = json.optString("parent").ifEmpty { null },
            signature = json.getString("signature"),
        )
    }
}

/**
 * AIR Chat's own peer-to-peer messaging network.
 *
 * - Contacts are found by username.
 * - Conversations (one-to-one and groups) live only on members' phones, in encrypted storage.
 * - Phones talk to each other directly over WebRTC data channels (DTLS encrypted end to end).
 *   Public relays only carry short connection set-up messages, themselves end-to-end encrypted.
 * - Messages written while a contact is offline are delivered the next time both are online.
 */
object AirNetwork {
    private const val TAG = "AirNetwork"
    const val SIGNAL_KIND = 21420
    private const val HELLO_INTERVAL_MS = 60_000L

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var session: AirAccount.Session? = null
    private var vault: AirVault? = null
    private var subscriptionId: String? = null
    private var helloJob: Job? = null

    private val _contacts = MutableStateFlow<Map<String, AirContact>>(emptyMap())
    val contacts: StateFlow<Map<String, AirContact>> = _contacts.asStateFlow()

    private val _conversations = MutableStateFlow<Map<String, AirConversation>>(emptyMap())
    val conversations: StateFlow<Map<String, AirConversation>> = _conversations.asStateFlow()

    private val _items = MutableStateFlow<Map<String, AirItem>>(emptyMap())
    val items: StateFlow<Map<String, AirItem>> = _items.asStateFlow()

    private val _online = MutableStateFlow<Set<String>>(emptySet())
    val online: StateFlow<Set<String>> = _online.asStateFlow()

    private val _readUpTo = MutableStateFlow<Map<String, Long>>(emptyMap())
    val readUpTo: StateFlow<Map<String, Long>> = _readUpTo.asStateFlow()

    val selfKey: String? get() = session?.publicKey
    val selfUsername: String? get() = session?.username

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    // ------------------------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------------------------

    fun start(newSession: AirAccount.Session) {
        if (session?.publicKey == newSession.publicKey) return
        stop()
        session = newSession
        vault = AirVault(appContext, "net_${newSession.publicKey.take(16)}.vault")
        load()
        NostrRelayPool.start()
        subscriptionId = NostrRelayPool.subscribe(
            listOf(
                JSONObject()
                    .put("kinds", JSONArray().put(SIGNAL_KIND))
                    .put("#p", JSONArray().put(newSession.publicKey))
                    .put("since", System.currentTimeMillis() / 1000 - 30)
            )
        ) { onSignalEvent(it) }
        helloJob = scope.launch {
            while (isActive) {
                helloEveryone()
                delay(HELLO_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        helloJob?.cancel()
        subscriptionId?.let { NostrRelayPool.unsubscribe(it) }
        subscriptionId = null
        peers.keys.toList().forEach { closePeer(it) }
        session = null
        vault = null
        _contacts.value = emptyMap()
        _conversations.value = emptyMap()
        _items.value = emptyMap()
        _online.value = emptySet()
        _readUpTo.value = emptyMap()
    }

    // ------------------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------------------

    fun username(publicKey: String): String =
        if (publicKey == selfKey) selfUsername ?: publicKey.take(8)
        else _contacts.value[publicKey]?.username ?: publicKey.take(8)

    /** Adds a contact by username. Returns the contact, or null if the username does not exist. */
    suspend fun addContact(username: String): AirContact? {
        val name = AirAccount.normalize(username)
        val owner = AirAccount.ownerOf(name) ?: return null
        if (owner == selfKey) return null
        val contact = AirContact(owner, name)
        synchronized(lock) {
            _contacts.value = _contacts.value + (owner to contact)
            save()
        }
        sendHello(owner)
        return contact
    }

    fun directConversationId(other: String): String {
        val me = selfKey ?: ""
        return "dm:" + listOf(me, other).sorted().joinToString(":")
    }

    fun openDirect(other: String): AirConversation {
        val id = directConversationId(other)
        _conversations.value[id]?.let { return it }
        val conversation = AirConversation(
            id = id,
            isGroup = false,
            name = "",
            members = setOf(selfKey ?: "", other),
            createdAt = System.currentTimeMillis()
        )
        synchronized(lock) {
            _conversations.value = _conversations.value + (id to conversation)
            save()
        }
        return conversation
    }

    fun createGroup(name: String, members: Set<String>): AirConversation {
        val me = selfKey ?: error("Not logged in")
        val conversation = AirConversation(
            id = "group:" + AirCrypto.hex(AirCrypto.randomBytes(12)),
            isGroup = true,
            name = name.trim().take(64),
            members = members + me,
            createdAt = System.currentTimeMillis()
        )
        synchronized(lock) {
            _conversations.value = _conversations.value + (conversation.id to conversation)
            save()
        }
        members.forEach { member ->
            sendSignal(member, JSONObject().put("t", "invite").put("conv", conversationJson(conversation)))
            peers[member]?.let { if (it.isOpen) sendConversation(it, conversation) }
        }
        return conversation
    }

    fun conversationTitle(conversation: AirConversation): String =
        if (conversation.isGroup) conversation.name
        else conversation.members.firstOrNull { it != selfKey }?.let { username(it) } ?: ""

    fun post(
        conversationId: String,
        type: String,
        text: String,
        title: String = "",
        tags: List<String> = emptyList(),
        parent: String? = null,
    ) {
        val current = session ?: return
        val unsigned = AirItem(
            id = "%013d".format(System.currentTimeMillis()) + AirCrypto.hex(AirCrypto.randomBytes(6)),
            conversation = conversationId,
            author = current.publicKey,
            createdAt = System.currentTimeMillis(),
            type = type,
            text = text.take(8000),
            title = title.take(200),
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.take(8),
            parent = parent,
        )
        val item = unsigned.copy(
            signature = Secp256k1.hex(Secp256k1.signSchnorr(unsigned.signedBytes(), current.privateKey))
        )
        addItems(listOf(item))
        broadcastItems(conversationId, listOf(item), except = null)
        _conversations.value[conversationId]?.members?.forEach { member ->
            if (member != selfKey && peers[member]?.isOpen != true) sendHello(member)
        }
    }

    fun markRead(conversationId: String) {
        val latest = _items.value.values.filter { it.conversation == conversationId }
            .maxOfOrNull { it.createdAt } ?: return
        if ((_readUpTo.value[conversationId] ?: 0) >= latest) return
        synchronized(lock) {
            _readUpTo.value = _readUpTo.value + (conversationId to latest)
            save()
        }
    }

    // ------------------------------------------------------------------------------------
    // Signalling over relays (end-to-end encrypted)
    // ------------------------------------------------------------------------------------

    private fun signalKey(other: String): ByteArray {
        val current = session ?: error("Not logged in")
        val shared = Secp256k1.sharedSecret(current.privateKey, Secp256k1.unhex(other))
        return AirCrypto.hkdf(shared, "AIR Chat".toByteArray(), "AIR Chat signalling v1")
    }

    internal fun sendSignal(to: String, payload: JSONObject) {
        val current = session ?: return
        scope.launch {
            try {
                payload.put("from_name", current.username)
                val sealed = AirCrypto.seal(signalKey(to), payload.toString().toByteArray(), "S|$to")
                val event = NostrEvent.create(
                    current.privateKey,
                    SIGNAL_KIND,
                    listOf(listOf("p", to)),
                    AirCrypto.b64(sealed)
                )
                NostrRelayPool.send(event)
            } catch (e: Exception) {
                Log.w(TAG, "Could not send signal", e)
            }
        }
    }

    private val lastHelloSent = ConcurrentHashMap<String, Long>()

    private fun sendHello(to: String) {
        val now = System.currentTimeMillis()
        if (now - (lastHelloSent[to] ?: 0L) < 20_000) return
        lastHelloSent[to] = now
        sendSignal(to, JSONObject().put("t", "hello"))
    }

    private fun helloEveryone() {
        val targets = _contacts.value.keys + _conversations.value.values.flatMap { it.members }
        targets.filter { it != selfKey && peers[it]?.isOpen != true }.toSet().forEach { sendHello(it) }
    }

    private val handledSignals = ConcurrentHashMap.newKeySet<String>()

    private fun onSignalEvent(event: NostrEvent) {
        val current = session ?: return
        if (!handledSignals.add(event.id)) return
        if (event.firstTag("p") != current.publicKey) return
        if (System.currentTimeMillis() / 1000 - event.createdAt > 120) return
        val from = event.pubkey
        if (from == current.publicKey) return

        val json = try {
            JSONObject(
                String(AirCrypto.open(signalKey(from), AirCrypto.unb64(event.content), "S|${current.publicKey}"))
            )
        } catch (e: Exception) {
            return
        }

        scope.launch {
            try {
                rememberSender(from, json.optString("from_name"))
                when (json.optString("t")) {
                    "hello" -> onHello(from)
                    "offer" -> onOffer(from, json.getString("sid"), json.getString("sdp"))
                    "answer" -> onAnswer(from, json.getString("sid"), json.getString("sdp"))
                    "invite" -> json.optJSONObject("conv")?.let { mergeConversation(from, it) }
                    "call-offer", "call-answer", "call-end" -> AirCall.onSignal(from, json)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Signal handling failed", e)
            }
        }
    }

    /** Someone we do not know yet wrote to us: check their username before showing it. */
    private suspend fun rememberSender(from: String, claimedName: String) {
        if (_contacts.value.containsKey(from) || claimedName.isEmpty()) return
        val owner = runCatching { AirAccount.ownerOf(claimedName) }.getOrNull()
        if (owner != from) return
        synchronized(lock) {
            _contacts.value = _contacts.value + (from to AirContact(from, AirAccount.normalize(claimedName)))
            save()
        }
    }

    private suspend fun onHello(from: String) {
        val existing = peers[from]
        if (existing != null && (existing.isOpen || System.currentTimeMillis() - existing.createdAt < 20_000)) return
        if ((selfKey ?: return) < from) connect(from) else sendHello(from)
    }

    // ------------------------------------------------------------------------------------
    // Direct connections (WebRTC data channels)
    // ------------------------------------------------------------------------------------

    private class Peer(val publicKey: String, val sessionId: String, val connection: PeerConnection) {
        val createdAt = System.currentTimeMillis()
        @Volatile
        var channel: DataChannel? = null
        val gathered = CompletableDeferred<Unit>()
        val syncedConversations: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val isOpen: Boolean get() = channel?.state() == DataChannel.State.OPEN
    }

    private val peers = ConcurrentHashMap<String, Peer>()
    private var factory: PeerConnectionFactory? = null

    private val iceServers by lazy {
        listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478",
        ).map { PeerConnection.IceServer.builder(it).createIceServer() }
    }

    @Synchronized
    internal fun peerConnectionFactory(): PeerConnectionFactory {
        factory?.let { return it }
        LiveKit.init(appContext)
        return PeerConnectionFactory.builder().createPeerConnectionFactory().also { factory = it }
    }

    internal fun rtcConfiguration() = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }

    private suspend fun connect(to: String) {
        closePeer(to)
        val sessionId = AirCrypto.hex(AirCrypto.randomBytes(8))
        val peer = newPeer(to, sessionId) ?: return
        attach(peer, peer.connection.createDataChannel("air-chat", DataChannel.Init().apply { ordered = true }))
        val offer = peer.connection.createDescription(true)
        peer.connection.applyDescription(offer, local = true)
        withTimeoutOrNull(6_000) { peer.gathered.await() }
        sendSignal(
            to,
            JSONObject().put("t", "offer").put("sid", sessionId)
                .put("sdp", peer.connection.localDescription?.description ?: offer.description)
        )
    }

    private suspend fun onOffer(from: String, sessionId: String, sdp: String) {
        closePeer(from)
        val peer = newPeer(from, sessionId) ?: return
        peer.connection.applyDescription(SessionDescription(SessionDescription.Type.OFFER, sdp), local = false)
        val answer = peer.connection.createDescription(false)
        peer.connection.applyDescription(answer, local = true)
        withTimeoutOrNull(6_000) { peer.gathered.await() }
        sendSignal(
            from,
            JSONObject().put("t", "answer").put("sid", sessionId)
                .put("sdp", peer.connection.localDescription?.description ?: answer.description)
        )
    }

    private suspend fun onAnswer(from: String, sessionId: String, sdp: String) {
        val peer = peers[from] ?: return
        if (peer.sessionId != sessionId) return
        peer.connection.applyDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp), local = false)
    }

    private fun newPeer(publicKey: String, sessionId: String): Peer? {
        var created: Peer? = null
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
            override fun onDataChannel(channel: DataChannel) {
                created?.let { attach(it, channel) }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (newState == PeerConnection.PeerConnectionState.FAILED ||
                    newState == PeerConnection.PeerConnectionState.CLOSED
                ) {
                    created?.let { peer -> scope.launch { closePeer(peer.publicKey, peer) } }
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                if (p0 == PeerConnection.IceGatheringState.COMPLETE) created?.gathered?.complete(Unit)
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
        }
        val connection = peerConnectionFactory().createPeerConnection(rtcConfiguration(), observer) ?: return null
        val peer = Peer(publicKey, sessionId, connection)
        created = peer
        peers[publicKey] = peer
        return peer
    }

    private fun attach(peer: Peer, channel: DataChannel) {
        peer.channel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                when (channel.state()) {
                    DataChannel.State.OPEN -> onPeerOpen(peer)
                    DataChannel.State.CLOSED -> scope.launch { closePeer(peer.publicKey, peer) }
                    else -> Unit
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                runCatching { onData(peer, JSONObject(String(bytes, Charsets.UTF_8))) }
                    .onFailure { Log.w(TAG, "Bad data from peer", it) }
            }
        })
        if (channel.state() == DataChannel.State.OPEN) onPeerOpen(peer)
    }

    private fun onPeerOpen(peer: Peer) {
        _online.value = _online.value + peer.publicKey
        _conversations.value.values
            .filter { peer.publicKey in it.members }
            .forEach { conversation ->
                if (conversation.isGroup) sendConversation(peer, conversation)
                sendSync(peer, conversation.id)
            }
    }

    private fun sendSync(peer: Peer, conversationId: String) {
        if (!peer.syncedConversations.add(conversationId)) return
        send(peer, JSONObject().put("t", "sync").put("conv", conversationId)
            .put("ids", JSONArray(itemIdsOf(conversationId))))
    }

    private fun onData(peer: Peer, json: JSONObject) {
        when (json.optString("t")) {
            "conv" -> json.optJSONObject("conv")?.let { raw ->
                mergeConversation(peer.publicKey, raw)
                conversationFromJson(raw)?.let { c ->
                    if (conversationFor(peer.publicKey, c.id) != null) sendSync(peer, c.id)
                }
            }

            "sync" -> {
                val conversationId = json.getString("conv")
                val conversation = conversationFor(peer.publicKey, conversationId)
                    ?: (if (conversationId == directConversationId(peer.publicKey)) openDirect(peer.publicKey) else null)
                    ?: return
                sendSync(peer, conversation.id)
                val theirs = json.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    ?: emptySet()
                val missing = _items.value.values.filter { it.conversation == conversation.id && it.id !in theirs }
                sendItems(peer, missing)
            }

            "items" -> {
                val array = json.optJSONArray("items") ?: return
                val incoming = (0 until array.length()).mapNotNull {
                    runCatching { AirItem.fromJson(array.getJSONObject(it)) }.getOrNull()
                }
                val accepted = incoming.filter { item ->
                    val conversation = conversationFor(peer.publicKey, item.conversation)
                        ?: (if (item.conversation == directConversationId(peer.publicKey)) openDirect(peer.publicKey) else null)
                    conversation != null && item.author in conversation.members && item.isValid()
                }
                val added = addItems(accepted)
                added.groupBy { it.conversation }.forEach { (conversationId, list) ->
                    broadcastItems(conversationId, list, except = peer.publicKey)
                }
            }
        }
    }

    /** The conversation, if both this device and [peer] are members of it. */
    private fun conversationFor(peer: String, conversationId: String): AirConversation? {
        val conversation = _conversations.value[conversationId] ?: return null
        return conversation.takeIf { peer in it.members && selfKey in it.members }
    }

    private fun mergeConversation(from: String, json: JSONObject) {
        val incoming = conversationFromJson(json) ?: return
        if (from !in incoming.members || selfKey !in incoming.members) return
        synchronized(lock) {
            val existing = _conversations.value[incoming.id]
            if (existing != null && from !in existing.members) return
            val merged = existing?.copy(members = existing.members + incoming.members) ?: incoming
            _conversations.value = _conversations.value + (merged.id to merged)
            save()
        }
        incoming.members.filter { it != selfKey && peers[it]?.isOpen != true }.forEach { sendHello(it) }
    }

    private fun sendConversation(peer: Peer, conversation: AirConversation) {
        send(peer, JSONObject().put("t", "conv").put("conv", conversationJson(conversation)))
    }

    private fun broadcastItems(conversationId: String, list: List<AirItem>, except: String?) {
        val conversation = _conversations.value[conversationId] ?: return
        conversation.members
            .filter { it != selfKey && it != except }
            .mapNotNull { peers[it] }
            .filter { it.isOpen }
            .forEach { sendItems(it, list) }
    }

    private fun sendItems(peer: Peer, list: List<AirItem>) {
        list.chunked(20).forEach { batch ->
            send(peer, JSONObject().put("t", "items").put("items", JSONArray().apply { batch.forEach { put(it.toJson()) } }))
        }
    }

    private fun send(peer: Peer, json: JSONObject) {
        val channel = peer.channel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(json.toString().toByteArray(Charsets.UTF_8)), false))
    }

    private fun closePeer(publicKey: String, only: Peer? = null) {
        val peer = peers[publicKey] ?: return
        if (only != null && peer !== only) return
        peers.remove(publicKey)
        runCatching { peer.channel?.unregisterObserver() }
        runCatching { peer.channel?.close() }
        runCatching { peer.connection.close() }
        _online.value = _online.value - publicKey
    }

    // ------------------------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------------------------

    private fun itemIdsOf(conversationId: String): List<String> =
        _items.value.values.filter { it.conversation == conversationId }.map { it.id }

    private fun addItems(list: List<AirItem>): List<AirItem> {
        synchronized(lock) {
            val current = _items.value
            val added = list.filter { it.id !in current }
            if (added.isEmpty()) return emptyList()
            _items.value = current + added.associateBy { it.id }
            // a message in a direct conversation also makes its author a contact
            save()
            return added
        }
    }

    private fun conversationJson(c: AirConversation) = JSONObject().apply {
        put("id", c.id); put("group", c.isGroup); put("name", c.name)
        put("members", JSONArray(c.members.toList())); put("createdAt", c.createdAt)
    }

    private fun conversationFromJson(json: JSONObject): AirConversation? = runCatching {
        AirConversation(
            id = json.getString("id"),
            isGroup = json.getBoolean("group"),
            name = json.optString("name").take(64),
            members = json.getJSONArray("members").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
            createdAt = json.optLong("createdAt"),
        )
    }.getOrNull()

    private fun load() {
        val raw = vault?.read() ?: return
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        json.optJSONArray("contacts")?.let { a ->
            _contacts.value = (0 until a.length()).map { a.getJSONObject(it) }
                .associate { it.getString("key") to AirContact(it.getString("key"), it.getString("username")) }
        }
        json.optJSONArray("conversations")?.let { a ->
            _conversations.value = (0 until a.length()).mapNotNull { conversationFromJson(a.getJSONObject(it)) }
                .associateBy { it.id }
        }
        json.optJSONArray("items")?.let { a ->
            _items.value = (0 until a.length()).mapNotNull { runCatching { AirItem.fromJson(a.getJSONObject(it)) }.getOrNull() }
                .associateBy { it.id }
        }
        json.optJSONObject("read")?.let { o ->
            _readUpTo.value = o.keys().asSequence().associateWith { o.getLong(it) }
        }
    }

    private fun save() {
        val target = vault ?: return
        val json = JSONObject()
        json.put("contacts", JSONArray().apply {
            _contacts.value.values.forEach { put(JSONObject().put("key", it.publicKey).put("username", it.username)) }
        })
        json.put("conversations", JSONArray().apply { _conversations.value.values.forEach { put(conversationJson(it)) } })
        json.put("items", JSONArray().apply { _items.value.values.sortedBy { it.createdAt }.forEach { put(it.toJson()) } })
        json.put("read", JSONObject(_readUpTo.value.mapValues { it.value }))
        runCatching { target.write(json.toString()) }.onFailure { Log.e(TAG, "Could not save", it) }
    }

    // ---- coroutine helpers for the callback-based WebRTC API ----

    internal suspend fun PeerConnection.createDescription(offer: Boolean): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (continuation.isActive) continuation.resume(description)
                }

                override fun onCreateFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error ?: "createDescription failed"))
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }
            if (offer) createOffer(observer, MediaConstraints()) else createAnswer(observer, MediaConstraints())
        }

    internal suspend fun PeerConnection.applyDescription(description: SessionDescription, local: Boolean) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {}
                override fun onCreateFailure(error: String?) {}
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error ?: "setDescription failed"))
                }
            }
            if (local) setLocalDescription(observer, description) else setRemoteDescription(observer, description)
        }
    }
}

/** Bridge used by [AirCall] to reach the encrypted signalling of [AirNetwork]. */
internal object AirNetworkSignals {
    fun newSessionId(): String = AirCrypto.hex(AirCrypto.randomBytes(8))
    fun send(to: String, payload: JSONObject) = AirNetwork.sendSignal(to, payload)
}
