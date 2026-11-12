package chat.stoat.air

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.IBinder
import android.util.Log
import chat.stoat.voice.AirVideoQuality
import io.livekit.android.LiveKit
import io.livekit.android.room.track.screencapture.ScreenCaptureService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.Camera2Enumerator
import livekit.org.webrtc.CameraVideoCapturer
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.DefaultVideoDecoderFactory
import livekit.org.webrtc.DefaultVideoEncoderFactory
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.ScreenCapturerAndroid
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoSource
import livekit.org.webrtc.VideoTrack
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * AIR Chat peer-to-peer calls: audio, video (up to 4K) and screen sharing, for direct messages,
 * groups and server channels.
 *
 * Every participant is connected directly to every other one (mesh), with WebRTC (DTLS-SRTP,
 * encrypted end to end). Connection set-up goes over the existing peer-to-peer link when there
 * is one, otherwise over the encrypted rendezvous signalling.
 */
object AirCall {
    private const val TAG = "AirCall"
    private const val MAX_PARTICIPANTS = 8

    enum class State { IDLE, OUTGOING, INCOMING, CONNECTING, ACTIVE }

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The person shown as the call's title (caller, or the other person in a DM). */
    private val _peer = MutableStateFlow<String?>(null)
    val peer: StateFlow<String?> = _peer.asStateFlow()

    private val _conversation = MutableStateFlow<String?>(null)
    val conversation: StateFlow<String?> = _conversation.asStateFlow()

    /** Other participants currently connected. */
    private val _participants = MutableStateFlow<Set<String>>(emptySet())
    val participants: StateFlow<Set<String>> = _participants.asStateFlow()

    private val _remoteVideos = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideos: StateFlow<Map<String, VideoTrack>> = _remoteVideos.asStateFlow()

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()

    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo.asStateFlow()

    private val _micOn = MutableStateFlow(true)
    val micOn: StateFlow<Boolean> = _micOn.asStateFlow()

    private val _cameraOn = MutableStateFlow(false)
    val cameraOn: StateFlow<Boolean> = _cameraOn.asStateFlow()

    private val _screenSharing = MutableStateFlow(false)
    val screenSharing: StateFlow<Boolean> = _screenSharing.asStateFlow()

    private val _quality = MutableStateFlow(AirVideoQuality.DEFAULT)
    val quality: StateFlow<AirVideoQuality> = _quality.asStateFlow()

    val eglBase: EglBase by lazy { EglBase.create() }

    private var factory: PeerConnectionFactory? = null
    private var callId: String? = null
    private var startWithVideo = false

    private class Link(val peer: String, val connection: PeerConnection) {
        var videoTransceiver: RtpTransceiver? = null
        var gathered = CompletableDeferred<Unit>()
        var connected = false
    }

    private val links = ConcurrentHashMap<String, Link>()

    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var screenServiceConnection: ServiceConnection? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        _quality.value = AirVideoQuality.load(appContext)
    }

    @Synchronized
    private fun factory(): PeerConnectionFactory {
        factory?.let { return it }
        LiveKit.init(appContext)
        val audio = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(audio)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    private fun send(to: String, payload: JSONObject) {
        payload.put("cid", callId ?: payload.optString("cid"))
        payload.put("conv", _conversation.value ?: payload.optString("conv"))
        AirNetwork.sendCallMessage(to, payload)
    }

    private fun otherMembers(conversationId: String): Set<String> =
        AirNetwork.conversations.value[conversationId]?.members.orEmpty() - setOfNotNull(AirNetwork.selfKey)

    // ------------------------------------------------------------------------------------
    // Call flow
    // ------------------------------------------------------------------------------------

    /** Starts a call in a conversation (DM, group or server channel). */
    fun start(conversationId: String, video: Boolean) {
        if (_state.value != State.IDLE) return
        val others = otherMembers(conversationId)
        if (others.isEmpty()) return
        callId = AirNetworkSignals.newSessionId()
        _conversation.value = conversationId
        _peer.value = others.first()
        startWithVideo = video
        _state.value = State.OUTGOING
        prepareLocalMedia()
        if (video) scope.launch { setCamera(true) }
        others.take(MAX_PARTICIPANTS).forEach { member ->
            send(member, JSONObject().put("t", "call-ring").put("video", video))
        }
        // stop ringing if nobody answers
        val ringing = callId
        scope.launch {
            delay(45_000)
            if (callId == ringing && _state.value == State.OUTGOING) hangUp()
        }
    }

    fun accept() {
        if (_state.value != State.INCOMING) return
        val caller = _peer.value ?: return
        _state.value = State.CONNECTING
        prepareLocalMedia()
        if (startWithVideo) scope.launch { setCamera(true) }
        scope.launch { connectTo(caller) }
        send(caller, JSONObject().put("t", "call-join"))
    }

    fun hangUp() {
        links.keys.toList().forEach { send(it, JSONObject().put("t", "call-leave")) }
        if (_state.value == State.OUTGOING || _state.value == State.INCOMING) {
            _conversation.value?.let { conv ->
                otherMembers(conv).forEach { send(it, JSONObject().put("t", "call-leave")) }
            }
        }
        cleanUp()
    }

    internal fun onSignal(from: String, json: JSONObject) {
        scope.launch {
            try {
                handleSignal(from, json)
            } catch (e: Exception) {
                Log.w(TAG, "Call signal failed", e)
            }
        }
    }

    private suspend fun handleSignal(from: String, json: JSONObject) {
        val cid = json.optString("cid")
        val conv = json.optString("conv")
        when (json.optString("t")) {
            "call-ring" -> {
                val conversation = AirNetwork.conversations.value[conv] ?: return
                if (from !in conversation.members) return
                if (_state.value != State.IDLE) {
                    if (cid != callId) send(from, JSONObject().put("t", "call-busy").put("cid", cid).put("conv", conv))
                    return
                }
                callId = cid
                _conversation.value = conv
                _peer.value = from
                startWithVideo = json.optBoolean("video")
                _state.value = State.INCOMING
            }

            "call-join" -> {
                if (cid != callId) return
                // tell the newcomer who else is in the call, so it connects to them too
                val roster = links.filter { it.value.connected && it.key != from }.keys
                send(from, JSONObject().put("t", "call-roster").put("peers", JSONArray(roster.toList())))
                if (_state.value == State.OUTGOING) _state.value = State.CONNECTING
            }

            "call-roster" -> {
                if (cid != callId) return
                val peers = json.optJSONArray("peers") ?: return
                for (i in 0 until peers.length()) {
                    val member = peers.getString(i)
                    if (member != AirNetwork.selfKey && !links.containsKey(member) && links.size < MAX_PARTICIPANTS) {
                        connectTo(member)
                    }
                }
            }

            "call-offer" -> {
                if (cid != callId || _state.value == State.IDLE || _state.value == State.INCOMING) return
                answer(from, json.getString("sdp"))
            }

            "call-answer" -> {
                if (cid != callId) return
                links[from]?.connection?.applyDescription(
                    SessionDescription(SessionDescription.Type.ANSWER, json.getString("sdp")), false
                )
            }

            "call-leave", "call-busy" -> {
                if (cid != callId) return
                if (_state.value == State.INCOMING && from == _peer.value) {
                    cleanUp()
                    return
                }
                removeLink(from)
            }
        }
    }

    private suspend fun connectTo(peer: String) {
        val link = createLink(peer) ?: return
        val offer = link.connection.createDescription(true)
        link.connection.applyDescription(offer, true)
        withTimeoutOrNull(6_000) { link.gathered.await() }
        send(peer, JSONObject().put("t", "call-offer").put("sdp", link.connection.localDescription?.description ?: offer.description))
    }

    private suspend fun answer(peer: String, sdp: String) {
        val link = createLink(peer) ?: return
        link.connection.applyDescription(SessionDescription(SessionDescription.Type.OFFER, sdp), false)
        // The offer's video line gets its own transceiver: send our camera / screen on that one,
        // otherwise the person who answers never sees the video of the one who started the call.
        link.connection.transceivers
            .firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && it.mid != null }
            ?.let { negotiated ->
                if (negotiated !== link.videoTransceiver) {
                    link.videoTransceiver = negotiated
                    negotiated.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                    videoTrack?.let { negotiated.sender.setTrack(it, false) }
                    applyBitrate(link)
                }
            }
        val answer = link.connection.createDescription(false)
        link.connection.applyDescription(answer, true)
        withTimeoutOrNull(6_000) { link.gathered.await() }
        send(peer, JSONObject().put("t", "call-answer").put("sdp", link.connection.localDescription?.description ?: answer.description))
    }

    private fun createLink(peer: String): Link? {
        removeLink(peer, notify = false)
        var created: Link? = null
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) scope.launch {
                    _remoteVideos.value = _remoteVideos.value + (peer to track)
                    _remoteVideo.value = _remoteVideos.value.values.firstOrNull()
                }
            }

            override fun onDataChannel(channel: DataChannel) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                scope.launch {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            created?.connected = true
                            _participants.value = _participants.value + peer
                            if (_state.value != State.ACTIVE) {
                                _state.value = State.ACTIVE
                                audioMode(true)
                            }
                        }

                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.CLOSED -> if (links[peer] === created) removeLink(peer)

                        else -> Unit
                    }
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
        val connection = factory().createPeerConnection(AirNetwork.rtcConfiguration(), observer) ?: return null
        val link = Link(peer, connection)
        created = link
        audioTrack?.let { connection.addTrack(it, listOf("air-call")) }
        link.videoTransceiver = connection.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        )
        videoTrack?.let { link.videoTransceiver?.sender?.setTrack(it, false) }
        applyBitrate(link)
        links[peer] = link
        return link
    }

    private fun removeLink(peer: String, notify: Boolean = true) {
        val link = links.remove(peer) ?: return
        runCatching { link.connection.close() }
        _participants.value = _participants.value - peer
        _remoteVideos.value = _remoteVideos.value - peer
        _remoteVideo.value = _remoteVideos.value.values.firstOrNull()
        if (notify && links.isEmpty() && _state.value == State.ACTIVE) cleanUp()
    }

    private fun prepareLocalMedia() {
        if (audioTrack != null) return
        val f = factory()
        val source = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("air-audio", source).also { it.setEnabled(_micOn.value) }
    }

    // ------------------------------------------------------------------------------------
    // Media controls
    // ------------------------------------------------------------------------------------

    fun toggleMic() {
        val enabled = !_micOn.value
        audioTrack?.setEnabled(enabled)
        _micOn.value = enabled
    }

    fun toggleCamera() = setCamera(!_cameraOn.value)

    fun switchCamera() {
        (capturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun changeQuality(quality: AirVideoQuality) {
        _quality.value = quality
        AirVideoQuality.save(appContext, quality)
        val preset = if (_screenSharing.value) quality.screen else quality.camera
        if (_screenSharing.value || _cameraOn.value) {
            runCatching { capturer?.changeCaptureFormat(preset.capture.width, preset.capture.height, preset.capture.maxFps) }
        }
        links.values.forEach { applyBitrate(it) }
    }

    private fun setCamera(enabled: Boolean) {
        stopVideo()
        if (!enabled) return
        val enumerator = Camera2Enumerator(appContext)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return
        val camera = enumerator.createCapturer(name, null) ?: return
        val preset = _quality.value.camera
        startVideo(camera, isScreencast = false, preset.capture.width, preset.capture.height, preset.capture.maxFps)
        _cameraOn.value = true
        links.values.forEach { applyBitrate(it) }
    }

    /** Starts screen sharing with the permission result returned by MediaProjectionManager. */
    fun startScreenShare(permissionData: Intent) {
        if (_state.value == State.IDLE) return
        val serviceIntent = Intent(appContext, ScreenCaptureService::class.java)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                (binder as? ScreenCaptureService.ScreenCaptureBinder)?.service?.start(null, null)
                stopVideo()
                val screen = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
                    override fun onStop() {
                        scope.launch { stopScreenShare() }
                    }
                })
                val preset = _quality.value.screen
                startVideo(screen, isScreencast = true, preset.capture.width, preset.capture.height, preset.capture.maxFps)
                _screenSharing.value = true
                links.values.forEach { applyBitrate(it) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        screenServiceConnection = serviceConnection
        appContext.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun stopScreenShare() {
        if (!_screenSharing.value) return
        stopVideo()
        screenServiceConnection?.let { runCatching { appContext.unbindService(it) } }
        screenServiceConnection = null
    }

    private fun startVideo(source: VideoCapturer, isScreencast: Boolean, width: Int, height: Int, fps: Int) {
        val f = factory()
        val helper = SurfaceTextureHelper.create("air-capture", eglBase.eglBaseContext)
        val src = f.createVideoSource(isScreencast)
        source.initialize(helper, appContext, src.capturerObserver)
        source.startCapture(width, height, fps)
        val track = f.createVideoTrack("air-video", src)
        links.values.forEach { it.videoTransceiver?.sender?.setTrack(track, false) }
        capturer = source
        videoSource = src
        textureHelper = helper
        videoTrack = track
        _localVideo.value = track
    }

    private fun stopVideo() {
        links.values.forEach { it.videoTransceiver?.sender?.setTrack(null, false) }
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { textureHelper?.dispose() }
        capturer = null
        videoSource = null
        textureHelper = null
        videoTrack = null
        _localVideo.value = null
        _cameraOn.value = false
        _screenSharing.value = false
    }

    /** In a group, the available bandwidth is shared between participants. */
    private fun applyBitrate(link: Link) {
        val preset = if (_screenSharing.value) _quality.value.screen else _quality.value.camera
        val share = maxOf(1, links.size)
        val sender = link.videoTransceiver?.sender ?: return
        runCatching {
            val parameters = sender.parameters
            parameters.encodings.forEach {
                it.maxBitrateBps = preset.encoding.maxBitrate / share
                it.maxFramerate = preset.encoding.maxFps
            }
            sender.setParameters(parameters)
        }
    }

    @Suppress("DEPRECATION")
    private fun audioMode(inCall: Boolean) {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.mode = if (inCall) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        audio.isSpeakerphoneOn = inCall
    }

    private fun cleanUp() {
        stopVideo()
        screenServiceConnection?.let { runCatching { appContext.unbindService(it) } }
        screenServiceConnection = null
        links.values.forEach { runCatching { it.connection.close() } }
        links.clear()
        runCatching { audioTrack?.dispose() }
        audioTrack = null
        callId = null
        startWithVideo = false
        _remoteVideos.value = emptyMap()
        _remoteVideo.value = null
        _participants.value = emptySet()
        _micOn.value = true
        _peer.value = null
        _conversation.value = null
        _state.value = State.IDLE
        runCatching { audioMode(false) }
    }

    // ---- coroutine helpers ----

    private suspend fun PeerConnection.createDescription(offer: Boolean): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (continuation.isActive) continuation.resume(description)
                }

                override fun onCreateFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error ?: "failed"))
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }
            if (offer) createOffer(observer, MediaConstraints()) else createAnswer(observer, MediaConstraints())
        }

    private suspend fun PeerConnection.applyDescription(description: SessionDescription, local: Boolean) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {}
                override fun onCreateFailure(error: String?) {}
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error ?: "failed"))
                }
            }
            if (local) setLocalDescription(observer, description) else setRemoteDescription(observer, description)
        }
    }
}
