package chat.stoat.voice

import android.content.Context
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.air_video_quality_1080
import chat.stoat.shared.resources.air_video_quality_1440
import chat.stoat.shared.resources.air_video_quality_2160
import chat.stoat.shared.resources.air_video_quality_720
import io.livekit.android.room.Room
import io.livekit.android.room.track.CustomVideoPreset
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.VideoPreset
import org.jetbrains.compose.resources.StringResource

/**
 * AIR Chat: native, smooth video calls and screen sharing from HD up to 4K.
 */
enum class AirVideoQuality(
    val label: StringResource,
    val camera: VideoPreset,
    val screen: VideoPreset,
    val codec: VideoCodec,
) {
    HD_720(
        label = Res.string.air_video_quality_720,
        camera = CustomVideoPreset(VideoCaptureParameter(1280, 720, 30), VideoEncoding(2_000_000, 30)),
        screen = CustomVideoPreset(VideoCaptureParameter(1280, 720, 30), VideoEncoding(2_500_000, 30)),
        codec = VideoCodec.VP8,
    ),
    FULL_HD_1080(
        label = Res.string.air_video_quality_1080,
        camera = CustomVideoPreset(VideoCaptureParameter(1920, 1080, 60), VideoEncoding(6_000_000, 60)),
        screen = CustomVideoPreset(VideoCaptureParameter(1920, 1080, 60), VideoEncoding(8_000_000, 60)),
        codec = VideoCodec.VP8,
    ),
    QHD_1440(
        label = Res.string.air_video_quality_1440,
        camera = CustomVideoPreset(VideoCaptureParameter(2560, 1440, 60), VideoEncoding(12_000_000, 60)),
        screen = CustomVideoPreset(VideoCaptureParameter(2560, 1440, 60), VideoEncoding(15_000_000, 60)),
        codec = VideoCodec.H264,
    ),
    UHD_4K(
        label = Res.string.air_video_quality_2160,
        camera = CustomVideoPreset(VideoCaptureParameter(3840, 2160, 30), VideoEncoding(20_000_000, 30)),
        screen = CustomVideoPreset(VideoCaptureParameter(3840, 2160, 30), VideoEncoding(25_000_000, 30)),
        codec = VideoCodec.H264,
    );

    /** Applies this quality to the camera and screen share defaults of [room]. */
    fun applyTo(room: Room) {
        room.videoTrackCaptureDefaults =
            room.videoTrackCaptureDefaults.copy(captureParams = camera.capture)
        room.videoTrackPublishDefaults = room.videoTrackPublishDefaults.copy(
            videoEncoding = camera.encoding,
            videoCodec = codec.codecName,
        )
        room.screenShareTrackCaptureDefaults = room.screenShareTrackCaptureDefaults.copy(
            isScreencast = true,
            captureParams = screen.capture,
        )
        room.screenShareTrackPublishDefaults = room.screenShareTrackPublishDefaults.copy(
            videoEncoding = screen.encoding,
            videoCodec = codec.codecName,
        )
    }

    companion object {
        private const val PREFS = "air_chat_prefs"
        private const val KEY = "video_quality"

        val DEFAULT = FULL_HD_1080

        fun load(context: Context): AirVideoQuality {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == name } ?: DEFAULT
        }

        fun save(context: Context, quality: AirVideoQuality) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, quality.name)
                .apply()
        }
    }
}
