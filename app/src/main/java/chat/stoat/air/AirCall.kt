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
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * AIR Chat peer-to-peer calls: audio, video (up to 4K) and screen sharing, sent directly between
 * the two phones (WebRTC, DTLS-SRTP: encrypted end to end). Only the connection set-up goes
 * through the encrypted rendezvous signalling.
 */
object AirCall {
    private const val TAG = "AirCall"

    enum class State { IDLE, OUTGOING, INCOMING, CONNECTING, ACTIVE }

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _peer = MutableStateFlow<String?>(null)
    val peer: StateFlow<String?> = _peer.asStateFlow()

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
    private var connection: PeerConnection? = null
    private var sessionId: String? = null
    private var pendingOffer: String? = null
    private var gathered = CompletableDeferred<Unit>()

    private var audioTrack: AudioTrack? = null
    private var videoTransceiver: RtpTransceiver? = null
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

    // ------------------------------------------------------------------------------------
    // Call flow
    // ------------------------------------------------------------------------------------

    fun start(to: String, video: Boolean) {
        if (_state.value != State.IDLE) return
        _peer.value = to
        _state.value = State.OUTGOING
        sessionId = AirNetworkSignals.newSessionId()
        scope.launch {
            try {
                val pc = createConnection() ?: return@launch hangUp()
                addLocalMedia(pc)
                if (video) setCamera(true)
                val offer = pc.createDescription(true)
                pc.applyDescription(offer, true)
                withTimeoutOrNull(6_000) { gathered.await() }
                AirNetworkSignals.send(
                    to,
                    JSONObject().put("t", "call-offer").put("sid", sessionId)
                        .put("video", video)
                        .put("sdp", pc.localDescription?.description ?: offer.description)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not start call", e)
                hangUp()
            }
        }
    }

    fun accept() {
        if (_state.value != State.INCOMING) return
        val to = _peer.value ?: return
        val offer = pendingOffer ?: return
        _state.value = State.CONNECTING
        scope.launch {
            try {
                val pc = createConnection() ?: return@launch hangUp()
                pc.applyDescription(SessionDescription(SessionDescription.Type.OFFER, offer), false)
                addLocalMedia(pc)
                val answer = pc.createDescription(false)
                pc.applyDescription(answer, true)
                withTimeoutOrNull(6_000) { gathered.await() }
                AirNetworkSignals.send(
                    to,
                    JSONObject().put("t", "call-answer").put("sid", sessionId)
                        .put("sdp", pc.localDescription?.description ?: answer.description)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not accept call", e)
                hangUp()
            }
        }
    }

    fun hangUp() {
        val to = _peer.value
        if (to != null && _state.value != State.IDLE) {
            AirNetworkSignals.send(to, JSONObject().put("t", "call-end").put("sid", sessionId ?: ""))
        }
        cleanUp()
    }

    internal fun onSignal(from: String, json: JSONObject) {
        scope.launch {
            when (json.optString("t")) {
                "call-offer" -> {
                    if (_state.value != State.IDLE) {
                        AirNetworkSignals.send(from, JSONObject().put("t", "call-end").put("sid", json.optString("sid")))
                        return@launch
                    }
                    _peer.value = from
                    sessionId = json.optString("sid")
                    pendingOffer = json.getString("sdp")
                    _state.value = State.INCOMING
                }

                "call-answer" -> {
                    if (from != _peer.value || json.optString("sid") != sessionId) return@launch
                    _state.value = State.CONNECTING
                    runCatching {
                        connection?.applyDescription(
                            SessionDescription(SessionDescription.Type.ANSWER, json.getString("sdp")), false
                        )
                    }.onFailure { hangUp() }
                }

                "call-end" -> {
                    if (from == _peer.value && json.optString("sid") == sessionId) cleanUp()
                }
            }
        }
    }

    private fun createConnection(): PeerConnection? {
        gathered = CompletableDeferred()
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) _remoteVideo.value = track
            }

            override fun onDataChannel(channel: DataChannel) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                scope.launch {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            _state.value = State.ACTIVE
                            audioMode(true)
                        }

                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.CLOSED -> cleanUp()

                        else -> Unit
                    }
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                if (p0 == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
        }
        return factory().createPeerConnection(AirNetwork.rtcConfiguration(), observer).also { connection = it }
    }

    private fun addLocalMedia(pc: PeerConnection) {
        val f = factory()
        val audioSource = f.createAudioSource(MediaConstraints())
        val audio = f.createAudioTrack("air-audio", audioSource)
        audioTrack = audio
        pc.addTrack(audio, listOf("air-call"))

        // One video slot, used by the camera or by screen sharing
        val existing = pc.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
        videoTransceiver = existing?.also {
            it.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        } ?: pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        )
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
        when {
            _screenSharing.value -> {
                val preset = quality.screen
                runCatching { capturer?.changeCaptureFormat(preset.capture.width, preset.capture.height, preset.capture.maxFps) }
                applyBitrate(preset.encoding.maxBitrate, preset.encoding.maxFps)
            }

            _cameraOn.value -> {
                val preset = quality.camera
                runCatching { capturer?.changeCaptureFormat(preset.capture.width, preset.capture.height, preset.capture.maxFps) }
                applyBitrate(preset.encoding.maxBitrate, preset.encoding.maxFps)
            }
        }
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
        applyBitrate(preset.encoding.maxBitrate, preset.encoding.maxFps)
        _cameraOn.value = true
    }

    /** Starts screen sharing with the permission result returned by MediaProjectionManager. */
    fun startScreenShare(permissionData: Intent) {
        if (connection == null) return
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
                applyBitrate(preset.encoding.maxBitrate, preset.encoding.maxFps)
                _screenSharing.value = true
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
        val videoSrc = f.createVideoSource(isScreencast)
        source.initialize(helper, appContext, videoSrc.capturerObserver)
        source.startCapture(width, height, fps)
        val track = f.createVideoTrack("air-video", videoSrc)
        videoTransceiver?.sender?.setTrack(track, false)
        capturer = source
        videoSource = videoSrc
        textureHelper = helper
        _localVideo.value = track
    }

    private fun stopVideo() {
        videoTransceiver?.sender?.setTrack(null, false)
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { _localVideo.value?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { textureHelper?.dispose() }
        capturer = null
        videoSource = null
        textureHelper = null
        _localVideo.value = null
        _cameraOn.value = false
        _screenSharing.value = false
    }

    private fun applyBitrate(maxBitrate: Int, maxFps: Int) {
        val sender = videoTransceiver?.sender ?: return
        runCatching {
            val parameters = sender.parameters
            parameters.encodings.forEach {
                it.maxBitrateBps = maxBitrate
                it.maxFramerate = maxFps
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
        runCatching { audioTrack?.dispose() }
        audioTrack = null
        runCatching { connection?.close() }
        connection = null
        videoTransceiver = null
        pendingOffer = null
        sessionId = null
        _remoteVideo.value = null
        _micOn.value = true
        _peer.value = null
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
