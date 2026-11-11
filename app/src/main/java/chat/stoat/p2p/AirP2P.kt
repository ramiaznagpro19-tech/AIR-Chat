package chat.stoat.p2p

import android.content.Context
import android.util.Log
import chat.stoat.api.internals.ULID
import chat.stoat.e2ee.AirE2EE
import io.livekit.android.LiveKit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * AIR Chat peer-to-peer layer for threads & forums.
 *
 * Data never goes through a server: each device keeps its own encrypted copy, and devices of
 * a conversation's members sync with each other over direct WebRTC data channels (DTLS
 * encrypted). Only the short connection set-up messages (offer/answer) are relayed through the
 * conversation itself, sealed with its end-to-end encryption key, so nobody else can read them
 * or tamper with the connection.
 */
object AirP2P {
    private const val TAG = "AirP2P"
    private const val SIGNAL_MAX_AGE_MS = 2 * 60 * 1000L
    private const val HELLO_INTERVAL_MS = 45_000L
    private const val MAX_PEERS_PER_CHANNEL = 8
    private const val BATCH_SIZE = 25

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val stores = ConcurrentHashMap<String, AirPostStore>()
    private val peers = ConcurrentHashMap<String, Peer>()
    private val lastHello = ConcurrentHashMap<String, Long>()

    private val _peerCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val peerCounts: StateFlow<Map<String, Int>> = _peerCounts.asStateFlow()

    private var factory: PeerConnectionFactory? = null

    private val iceServers = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun.cloudflare.com:3478",
    ).map { PeerConnection.IceServer.builder(it).createIceServer() }

    private class Peer(
        val channelId: String,
        val fingerprint: String,
        val sessionId: String,
        val connection: PeerConnection,
    ) {
        val createdAt = System.currentTimeMillis()

        @Volatile
        var dataChannel: DataChannel? = null
        val gatheringComplete = CompletableDeferred<Unit>()
        val isOpen: Boolean get() = dataChannel?.state() == DataChannel.State.OPEN
    }

    fun install(context: Context) {
        appContext = context.applicationContext
        AirE2EE.p2pSignalHandler = ::onSignal
    }

    fun store(channelId: String): AirPostStore =
        stores.getOrPut(channelId) { AirPostStore(appContext, channelId) }

    /** Announces this device in the conversation so online peers connect and sync. */
    fun activate(channelId: String, force: Boolean = false) {
        store(channelId)
        val now = System.currentTimeMillis()
        val last = lastHello[channelId] ?: 0L
        if (!force && now - last < HELLO_INTERVAL_MS) return
        lastHello[channelId] = now
        scope.launch {
            AirE2EE.sendP2PSignal(channelId, signal("hello").toString().toByteArray())
        }
    }

    /** Publishes a new post: stored locally, then pushed to every connected peer. */
    fun publish(channelId: String, post: AirPost) {
        val added = store(channelId).merge(listOf(post))
        if (added.isEmpty()) return
        broadcast(channelId, added)
        activate(channelId)
    }

    // ------------------------------------------------------------------------------------
    // Signalling
    // ------------------------------------------------------------------------------------

    private fun signal(type: String) = JSONObject().apply {
        put("t", type)
        put("from", AirE2EE.ownDeviceFingerprint)
    }

    private fun onSignal(channelId: String, authorId: String?, messageId: String?, payload: ByteArray) {
        if (!::appContext.isInitialized) return
        val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
        val from = json.optString("from")
        val ownFingerprint = AirE2EE.ownDeviceFingerprint
        if (from.isEmpty() || from == ownFingerprint) return
        val to = json.optString("to")
        if (to.isNotEmpty() && to != ownFingerprint) return

        val sentAt = messageId?.let { runCatching { ULID.asTimestamp(it) }.getOrNull() } ?: return
        if (System.currentTimeMillis() - sentAt > SIGNAL_MAX_AGE_MS) return

        scope.launch {
            try {
                when (json.optString("t")) {
                    "hello" -> onHello(channelId, from)
                    "offer" -> onOffer(channelId, from, json.getString("sid"), json.getString("sdp"))
                    "answer" -> onAnswer(channelId, from, json.getString("sid"), json.getString("sdp"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Signalling failed", e)
            }
        }
    }

    private suspend fun onHello(channelId: String, from: String) {
        store(channelId)
        val existing = peers[key(channelId, from)]
        if (existing != null &&
            (existing.isOpen || System.currentTimeMillis() - existing.createdAt < 20_000)
        ) return
        if (peerCount(channelId) >= MAX_PEERS_PER_CHANNEL) return

        if (AirE2EE.ownDeviceFingerprint < from) {
            // The device with the smaller fingerprint starts the connection
            connect(channelId, from)
        } else {
            activate(channelId)
        }
    }

    private suspend fun connect(channelId: String, to: String) {
        closePeer(key(channelId, to))
        val sessionId = ULID.makeNext()
        val peer = newPeer(channelId, to, sessionId) ?: return
        val dataChannel = peer.connection.createDataChannel("air-chat-p2p", DataChannel.Init().apply {
            ordered = true
        })
        attach(peer, dataChannel)

        val offer = peer.connection.createDescription(offer = true)
        peer.connection.applyLocal(offer)
        withTimeoutOrNull(6_000) { peer.gatheringComplete.await() }
        val sdp = compactSdp(peer.connection.localDescription?.description ?: offer.description)

        AirE2EE.sendP2PSignal(channelId, signal("offer").apply {
            put("to", to)
            put("sid", sessionId)
            put("sdp", sdp)
        }.toString().toByteArray())
    }

    private suspend fun onOffer(channelId: String, from: String, sessionId: String, sdp: String) {
        store(channelId)
        closePeer(key(channelId, from))
        val peer = newPeer(channelId, from, sessionId) ?: return
        peer.connection.applyRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
        val answer = peer.connection.createDescription(offer = false)
        peer.connection.applyLocal(answer)
        withTimeoutOrNull(6_000) { peer.gatheringComplete.await() }
        val localSdp = compactSdp(peer.connection.localDescription?.description ?: answer.description)

        AirE2EE.sendP2PSignal(channelId, signal("answer").apply {
            put("to", from)
            put("sid", sessionId)
            put("sdp", localSdp)
        }.toString().toByteArray())
    }

    private suspend fun onAnswer(channelId: String, from: String, sessionId: String, sdp: String) {
        val peer = peers[key(channelId, from)] ?: return
        if (peer.sessionId != sessionId) return
        peer.connection.applyRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    /** Keeps the SDP short enough to be relayed in one message. */
    private fun compactSdp(sdp: String): String {
        var candidates = 0
        return sdp.lines().filter { line ->
            if (line.startsWith("a=candidate")) {
                val keep = !line.contains(" tcp ", ignoreCase = true) && candidates < 6
                if (keep) candidates++
                keep
            } else {
                line.isNotEmpty()
            }
        }.joinToString("\r\n", postfix = "\r\n")
    }

    // ------------------------------------------------------------------------------------
    // WebRTC
    // ------------------------------------------------------------------------------------

    @Synchronized
    private fun factory(): PeerConnectionFactory {
        factory?.let { return it }
        LiveKit.init(appContext)
        return PeerConnectionFactory.builder().createPeerConnectionFactory().also { factory = it }
    }

    private fun newPeer(channelId: String, fingerprint: String, sessionId: String): Peer? {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
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
                    created?.let { scope.launch { closePeer(key(it.channelId, it.fingerprint), it) } }
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                if (p0 == PeerConnection.IceGatheringState.COMPLETE) {
                    created?.gatheringComplete?.complete(Unit)
                }
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
        }
        val connection = factory().createPeerConnection(config, observer) ?: return null
        val peer = Peer(channelId, fingerprint, sessionId, connection)
        created = peer
        peers[key(channelId, fingerprint)] = peer
        return peer
    }

    private fun attach(peer: Peer, channel: DataChannel) {
        peer.dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}

            override fun onStateChange() {
                when (channel.state()) {
                    DataChannel.State.OPEN -> {
                        updatePeerCount(peer.channelId)
                        send(peer, JSONObject().apply {
                            put("t", "have")
                            put("ids", JSONArray(store(peer.channelId).ids().toList()))
                        })
                    }

                    DataChannel.State.CLOSED -> {
                        scope.launch { closePeer(key(peer.channelId, peer.fingerprint), peer) }
                    }

                    else -> Unit
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                onData(peer, String(bytes, Charsets.UTF_8))
            }
        })
        if (channel.state() == DataChannel.State.OPEN) updatePeerCount(peer.channelId)
    }

    private fun onData(peer: Peer, text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val store = store(peer.channelId)
        when (json.optString("t")) {
            "have" -> {
                val theirs = json.optJSONArray("ids")?.let { array ->
                    (0 until array.length()).map { array.getString(it) }.toSet()
                } ?: emptySet()
                sendPosts(peer, store.missingFrom(theirs))
            }

            "posts" -> {
                val array = json.optJSONArray("posts") ?: return
                val posts = (0 until array.length()).mapNotNull {
                    runCatching { AirPost.fromJson(array.getJSONObject(it)) }.getOrNull()
                }
                val added = store.merge(posts)
                // Relay new posts to the other peers so everyone converges quickly
                if (added.isNotEmpty()) broadcast(peer.channelId, added, except = peer.fingerprint)
            }
        }
    }

    private fun broadcast(channelId: String, posts: List<AirPost>, except: String? = null) {
        peers.values
            .filter { it.channelId == channelId && it.fingerprint != except && it.isOpen }
            .forEach { sendPosts(it, posts) }
    }

    private fun sendPosts(peer: Peer, posts: List<AirPost>) {
        posts.chunked(BATCH_SIZE).forEach { batch ->
            send(peer, JSONObject().apply {
                put("t", "posts")
                put("posts", JSONArray().apply { batch.forEach { put(it.toJson()) } })
            })
        }
    }

    private fun send(peer: Peer, json: JSONObject) {
        val channel = peer.dataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    private fun closePeer(key: String, only: Peer? = null) {
        val peer = peers[key] ?: return
        if (only != null && peer !== only) return
        peers.remove(key)
        runCatching { peer.dataChannel?.unregisterObserver() }
        runCatching { peer.dataChannel?.close() }
        runCatching { peer.connection.close() }
        updatePeerCount(peer.channelId)
    }

    private fun peerCount(channelId: String): Int =
        peers.values.count { it.channelId == channelId && it.isOpen }

    private fun updatePeerCount(channelId: String) {
        _peerCounts.value = _peerCounts.value + (channelId to peerCount(channelId))
    }

    private fun key(channelId: String, fingerprint: String) = "$channelId|$fingerprint"

    // ---- coroutine helpers for the callback-based WebRTC API ----

    private suspend fun PeerConnection.createDescription(offer: Boolean): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (continuation.isActive) continuation.resume(description)
                }

                override fun onCreateFailure(error: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(error ?: "createDescription failed"))
                    }
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }
            if (offer) createOffer(observer, MediaConstraints()) else createAnswer(observer, MediaConstraints())
        }

    private suspend fun PeerConnection.applyLocal(description: SessionDescription) =
        applyDescription(description, local = true)

    private suspend fun PeerConnection.applyRemote(description: SessionDescription) =
        applyDescription(description, local = false)

    private suspend fun PeerConnection.applyDescription(description: SessionDescription, local: Boolean) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {}
                override fun onCreateFailure(error: String?) {}
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(error ?: "setDescription failed"))
                    }
                }
            }
            if (local) setLocalDescription(observer, description) else setRemoteDescription(observer, description)
        }
    }
}
