package chat.stoat.air.ui

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import chat.stoat.air.AirCall
import chat.stoat.air.AirNetwork
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.airnet_call_accept
import chat.stoat.shared.resources.airnet_call_calling
import chat.stoat.shared.resources.airnet_call_camera
import chat.stoat.shared.resources.airnet_call_connecting
import chat.stoat.shared.resources.airnet_call_decline
import chat.stoat.shared.resources.airnet_call_encrypted
import chat.stoat.shared.resources.airnet_call_flip
import chat.stoat.shared.resources.airnet_call_hangup
import chat.stoat.shared.resources.airnet_call_incoming
import chat.stoat.shared.resources.airnet_call_mic
import chat.stoat.shared.resources.airnet_call_quality
import chat.stoat.shared.resources.airnet_call_screen
import chat.stoat.shared.resources.ic_call_24dp__fill
import chat.stoat.shared.resources.ic_call_end_24dp__fill
import chat.stoat.shared.resources.ic_cameraswitch_24dp
import chat.stoat.shared.resources.ic_lock_24dp
import chat.stoat.shared.resources.ic_mic_24dp
import chat.stoat.shared.resources.ic_mic_off_24dp
import chat.stoat.shared.resources.ic_mobile_share_24px
import chat.stoat.shared.resources.ic_videocam_24dp
import chat.stoat.shared.resources.ic_videocam_off_24dp
import chat.stoat.voice.AirVideoQuality
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.VideoTrack
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val CallGreen = Color(0xFF21B94D)
private val CallRed = Color(0xFFFE1A1D)

/** Requests microphone and camera permission, then runs [action]. */
@Composable
fun rememberCallPermissions(): (() -> Unit) -> Unit {
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) pending?.invoke()
        pending = null
    }
    return { action ->
        pending = action
        launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
    }
}

@Composable
fun AirCallOverlay() {
    val state by AirCall.state.collectAsState()
    if (state == AirCall.State.IDLE) return
    val peer by AirCall.peer.collectAsState()
    val remoteVideos by AirCall.remoteVideos.collectAsState()
    val localVideo by AirCall.localVideo.collectAsState()
    val conversationId by AirCall.conversation.collectAsState()
    val participants by AirCall.participants.collectAsState()
    val profiles by AirNetwork.profiles.collectAsState()
    val conversation = conversationId?.let { AirNetwork.conversations.value[it] }
    fun nameOf(key: String) = profiles[key]?.displayName ?: AirNetwork.username(key)
    val name = when {
        conversation != null && conversation.kind != chat.stoat.air.AirConversation.KIND_DM ->
            conversation.name.let { if (conversation.kind == chat.stoat.air.AirConversation.KIND_CHANNEL) "#$it" else it }
        else -> peer?.let { nameOf(it) } ?: ""
    }
    val withPermissions = rememberCallPermissions()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (state == AirCall.State.ACTIVE && remoteVideos.isNotEmpty()) {
            // one tile per participant sending video
            val tiles = remoteVideos.entries.toList()
            Column(Modifier.fillMaxSize()) {
                tiles.chunked(if (tiles.size <= 2) 1 else 2).forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        row.forEach { (_, track) ->
                            VideoView(track, Modifier.weight(1f).fillMaxSize().padding(2.dp))
                        }
                    }
                }
            }
        } else if (state == AirCall.State.ACTIVE && participants.size > 1) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(name, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                participants.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(8.dp)) {
                        row.forEach { key ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                AirAvatar(nameOf(key), size = 72.dp)
                                Spacer(Modifier.height(6.dp))
                                Text(nameOf(key), color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                AirAvatar(name.ifEmpty { "?" }, size = 112.dp)
                Spacer(Modifier.height(16.dp))
                Text(name, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        when (state) {
                            AirCall.State.INCOMING -> Res.string.airnet_call_incoming
                            AirCall.State.OUTGOING -> Res.string.airnet_call_calling
                            else -> Res.string.airnet_call_connecting
                        }
                    ),
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }

        localVideo?.let {
            VideoView(
                it,
                Modifier
                    .statusBarsPadding()
                    .padding(16.dp)
                    .align(Alignment.TopEnd)
                    .size(width = 110.dp, height = 170.dp)
                    .clip(MaterialTheme.shapes.large),
                mirror = !AirCall.screenSharing.collectAsState().value
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .statusBarsPadding()
                .padding(16.dp)
                .align(Alignment.TopStart)
        ) {
            Icon(painterResource(Res.drawable.ic_lock_24dp), null, Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(Res.string.airnet_call_encrypted),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium
            )
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
        ) {
            if (state == AirCall.State.INCOMING) {
                Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                    RoundButton(Res.drawable.ic_call_end_24dp__fill, stringResource(Res.string.airnet_call_decline), CallRed) {
                        AirCall.hangUp()
                    }
                    RoundButton(Res.drawable.ic_call_24dp__fill, stringResource(Res.string.airnet_call_accept), CallGreen) {
                        withPermissions { AirCall.accept() }
                    }
                }
            } else {
                CallControls()
            }
        }
    }
}

@Composable
private fun CallControls() {
    val context = LocalContext.current
    val micOn by AirCall.micOn.collectAsState()
    val cameraOn by AirCall.cameraOn.collectAsState()
    val sharing by AirCall.screenSharing.collectAsState()
    val quality by AirCall.quality.collectAsState()
    var qualityMenu by remember { mutableStateOf(false) }

    val screenLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) AirCall.startScreenShare(data)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            TextButton(onClick = { qualityMenu = true }) {
                Text(
                    stringResource(Res.string.airnet_call_quality) + " · " + stringResource(quality.label),
                    color = Color.White
                )
            }
            DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                AirVideoQuality.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.label)) },
                        onClick = {
                            AirCall.changeQuality(option)
                            qualityMenu = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(
                if (micOn) Res.drawable.ic_mic_24dp else Res.drawable.ic_mic_off_24dp,
                stringResource(Res.string.airnet_call_mic),
                if (micOn) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.6f),
                small = true
            ) { AirCall.toggleMic() }
            RoundButton(
                if (cameraOn) Res.drawable.ic_videocam_24dp else Res.drawable.ic_videocam_off_24dp,
                stringResource(Res.string.airnet_call_camera),
                if (cameraOn) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.2f),
                small = true
            ) { AirCall.toggleCamera() }
            if (cameraOn) {
                RoundButton(Res.drawable.ic_cameraswitch_24dp, stringResource(Res.string.airnet_call_flip), Color.White.copy(alpha = 0.2f), small = true) {
                    AirCall.switchCamera()
                }
            }
            RoundButton(
                Res.drawable.ic_mobile_share_24px,
                stringResource(Res.string.airnet_call_screen),
                if (sharing) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.2f),
                small = true
            ) {
                if (sharing) {
                    AirCall.stopScreenShare()
                } else {
                    context.getSystemService(MediaProjectionManager::class.java)?.let {
                        screenLauncher.launch(it.createScreenCaptureIntent())
                    }
                }
            }
            RoundButton(Res.drawable.ic_call_end_24dp__fill, stringResource(Res.string.airnet_call_hangup), CallRed) {
                AirCall.hangUp()
            }
        }
    }
}

@Composable
private fun RoundButton(
    icon: DrawableResource,
    description: String,
    colour: Color,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = colour, contentColor = Color.White),
        modifier = Modifier.size(if (small) 56.dp else 72.dp)
    ) {
        Icon(painterResource(icon), description, Modifier.size(if (small) 24.dp else 32.dp))
    }
}

@Composable
private fun VideoView(track: VideoTrack, modifier: Modifier, mirror: Boolean = false) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(AirCall.eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                renderer = this
            }
        },
        update = { it.setMirror(mirror) },
        modifier = modifier
    )
    DisposableEffect(track, renderer) {
        val target = renderer
        if (target != null) runCatching { track.addSink(target) }
        onDispose {
            if (target != null) runCatching { track.removeSink(target) }
        }
    }
    DisposableEffect(Unit) {
        onDispose { renderer?.release() }
    }
}

@Composable
fun CallButtons(peer: String) {
    val withPermissions = rememberCallPermissions()
    androidx.compose.material3.IconButton(onClick = { withPermissions { AirCall.start(AirNetwork.openDirect(peer).id, video = false) } }) {
        Icon(painterResource(Res.drawable.ic_call_24dp__fill), null)
    }
    androidx.compose.material3.IconButton(onClick = { withPermissions { AirCall.start(AirNetwork.openDirect(peer).id, video = true) } }) {
        Icon(painterResource(Res.drawable.ic_videocam_24dp), null)
    }
}

@Suppress("unused")
private fun Modifier.fullWidth() = this.fillMaxWidth()
