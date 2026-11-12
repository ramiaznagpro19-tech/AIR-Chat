package chat.stoat.air.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.stoat.api.routes.microservices.autumn.uploadToAutumn
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.ic_add_24dp
import io.ktor.http.ContentType
import org.jetbrains.compose.resources.painterResource

/** An image chosen on the phone, ready to be uploaded. */
class PickedImage(val bytes: ByteArray, val name: String, val contentType: String)

fun readPickedImage(context: Context, uri: Uri): PickedImage? = runCatching {
    val type = context.contentResolver.getType(uri) ?: "image/png"
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val extension = type.substringAfter('/', "png")
    PickedImage(bytes, "image.$extension", type)
}.getOrNull()

/** Uploads an image (tag: "avatars", "icons", "banners"…) and returns its id. */
suspend fun uploadPickedImage(image: PickedImage, tag: String): String =
    uploadToAutumn(image.bytes, image.name, tag, ContentType.parse(image.contentType))

/**
 * AIR Chat: round button to choose a picture (server icon, profile picture). Shows the chosen
 * picture, or a "+" until one is picked.
 */
@Composable
fun AirImagePicker(
    image: PickedImage?,
    onPicked: (PickedImage) -> Unit,
    size: Dp = 88.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { readPickedImage(context, it) }?.let(onPicked)
    }
    val bitmap = remember(image) {
        image?.let { runCatching { BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size)?.asImageBitmap() }.getOrNull() }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable {
                launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(painterResource(Res.drawable.ic_add_24dp), contentDescription = null, modifier = Modifier.size(size / 3))
        }
    }
}
