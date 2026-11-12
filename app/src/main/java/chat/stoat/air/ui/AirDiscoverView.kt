package chat.stoat.air.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.stoat.air.AirConversation
import chat.stoat.air.AirNetwork
import chat.stoat.air.AirOfficial
import chat.stoat.air.AirServers
import chat.stoat.api.routes.invites.joinInviteByCode
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.airdisc_description_hint
import chat.stoat.shared.resources.airdisc_empty
import chat.stoat.shared.resources.airdisc_intro
import chat.stoat.shared.resources.airdisc_join
import chat.stoat.shared.resources.airdisc_join_link
import chat.stoat.shared.resources.airdisc_joined
import chat.stoat.shared.resources.airdisc_link
import chat.stoat.shared.resources.airdisc_link_hint
import chat.stoat.shared.resources.airdisc_members
import chat.stoat.shared.resources.airdisc_my_servers
import chat.stoat.shared.resources.airdisc_official
import chat.stoat.shared.resources.airdisc_official_desc
import chat.stoat.shared.resources.airdisc_official_show
import chat.stoat.shared.resources.airdisc_progress
import chat.stoat.shared.resources.airdisc_request
import chat.stoat.shared.resources.airdisc_request_failed
import chat.stoat.shared.resources.airdisc_requested
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.io.ByteArrayOutputStream
import java.text.NumberFormat

private fun formatCount(n: Int) = NumberFormat.getIntegerInstance().format(n)

private fun decodeIcon(base64: String?) = base64?.let {
    runCatching {
        val bytes = Base64.decode(it, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

/** Small JPEG of the server picture, to publish with the listing. */
private fun smallIcon(server: AirConversation): String? {
    val icon = AirServers.state(server).icon ?: return null
    val bytes = AirNetwork.fileBytes(icon.id) ?: return null
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val scaled = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

/**
 * AIR Chat: "Discover AIR Chat". Lists the public servers with at least one million members,
 * lets the person join a server with its link, show the official server again, and ask for
 * their own servers to be listed once they reach one million members.
 */
@Composable
fun AirDiscoverView() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversations by AirNetwork.conversations.collectAsState()
    val hiddenVersion by AirOfficial.hiddenVersion.collectAsState()
    var listings by remember { mutableStateOf<List<AirServers.Listed>?>(null) }
    var link by remember { mutableStateOf("") }
    var joining by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        listings = runCatching { AirServers.fetchListings() }.getOrDefault(emptyList())
    }

    fun join(code: String) {
        joining = true
        scope.launch {
            val result = runCatching { joinInviteByCode(code.trim()) }.getOrNull()
            joining = false
            val ok = result?.ok == true
            Toast.makeText(
                context,
                if (ok) getString(Res.string.airdisc_joined) else getString(Res.string.airdisc_request_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val me = AirNetwork.selfKey
    val myServers = conversations.values.filter {
        it.kind == AirConversation.KIND_SERVER && it.owner == me && !AirOfficial.isOpen(it.id)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AirLogo(size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(Res.string.airdisc_intro), style = MaterialTheme.typography.bodyLarge)
            }
        }

        // Join with a link
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.airdisc_join_link), style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it.trim() },
                            placeholder = { Text(stringResource(Res.string.airdisc_link_hint)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(enabled = link.isNotBlank() && !joining, onClick = { join(link) }) {
                            if (joining) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(stringResource(Res.string.airdisc_join))
                        }
                    }
                }
            }
        }

        // Official server
        item(key = "official-$hiddenVersion") {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AirLogo(size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.airdisc_official), fontWeight = FontWeight.Bold)
                        Text(stringResource(Res.string.airdisc_official_show), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(Res.string.airdisc_official_desc), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(Res.string.airdisc_link, AirOfficial.LINK), style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = !AirOfficial.isHidden(), onCheckedChange = { show ->
                        AirOfficial.setHidden(!show)
                        if (show) AirOfficial.ensure()
                    })
                }
            }
        }

        // Listed servers
        val list = listings
        if (list == null) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else if (list.isEmpty()) {
            item {
                Text(
                    stringResource(Res.string.airdisc_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp)
                )
            }
        } else {
            items(list, key = { it.serverId }) { server ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val icon = remember(server.icon) { decodeIcon(server.icon) }
                        if (icon != null) {
                            Image(icon, null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)))
                        } else {
                            AirAvatar(server.name, size = 56.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(server.name, fontWeight = FontWeight.Bold)
                            Text(server.description, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            Text(
                                stringResource(Res.string.airdisc_members, formatCount(server.members)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Button(enabled = !joining, onClick = { join(server.link) }) { Text(stringResource(Res.string.airdisc_join)) }
                    }
                }
            }
        }

        // My servers: ask to be listed from one million members
        if (myServers.isNotEmpty()) {
            item {
                Text(
                    stringResource(Res.string.airdisc_my_servers),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(myServers, key = { "mine-" + it.id }) { server ->
                val state = AirServers.state(server)
                val members = server.members.size - state.removed.size
                var description by remember(server.id) { mutableStateOf(state.listing?.description ?: state.description ?: "") }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.name, fontWeight = FontWeight.Bold)
                        server.link?.let { Text(stringResource(Res.string.airdisc_link, it), style = MaterialTheme.typography.labelSmall) }
                        Text(stringResource(Res.string.airdisc_progress, formatCount(members)), style = MaterialTheme.typography.bodySmall)
                        if (members >= AirServers.DISCOVER_MIN_MEMBERS) {
                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it.take(512) },
                                label = { Text(stringResource(Res.string.airdisc_description_hint)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(onClick = {
                                scope.launch {
                                    val ok = AirServers.publishListing(server, description, smallIcon(server))
                                    Toast.makeText(
                                        context,
                                        getString(if (ok) Res.string.airdisc_requested else Res.string.airdisc_request_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(Res.string.airdisc_request))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private fun Modifier.roundedBackground() = this.background(androidx.compose.ui.graphics.Color.Transparent)
