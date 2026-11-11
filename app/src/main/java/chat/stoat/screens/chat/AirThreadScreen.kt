package chat.stoat.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import chat.stoat.api.StoatAPI
import chat.stoat.api.internals.ULID
import chat.stoat.composables.markdown.prose.ChatMarkdown
import chat.stoat.e2ee.AirE2EE
import chat.stoat.internals.extensions.zero
import chat.stoat.p2p.AirP2P
import chat.stoat.p2p.AirPost
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.air_e2ee_encrypted
import chat.stoat.shared.resources.air_empty_title
import chat.stoat.shared.resources.air_message_hint
import chat.stoat.shared.resources.air_replies_count
import chat.stoat.shared.resources.air_send
import chat.stoat.shared.resources.air_thread_from_message
import chat.stoat.shared.resources.back
import chat.stoat.shared.resources.ic_arrow_back_24dp
import chat.stoat.shared.resources.ic_lock_24dp
import chat.stoat.shared.resources.ic_send_24dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * AIR Chat: one thread or forum post with its replies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirThreadScreen(
    navController: NavController,
    channelId: String,
    postId: String,
) {
    val store = remember(channelId) { AirP2P.store(channelId) }
    val posts by store.posts.collectAsState()
    val root = posts[postId]
    val replies = posts.values
        .filter { it.kind == AirPost.KIND_REPLY && it.parent == postId }
        .sortedBy { it.createdAt }
    val serverId = StoatAPI.channelCache[channelId]?.server

    var draft by remember { mutableStateOf("") }

    LaunchedEffect(channelId) {
        if (AirE2EE.isEnabled(channelId)) AirP2P.activate(channelId)
    }

    Scaffold(
        topBar = {
            Column {
                AnimatedVisibility(LocalIsConnected.current) {
                    Spacer(
                        Modifier.height(
                            WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                        )
                    )
                }
                TopAppBar(
                    title = {
                        Text(
                            text = root?.title?.ifBlank { null }
                                ?: stringResource(Res.string.air_empty_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_arrow_back_24dp),
                                contentDescription = stringResource(Res.string.back)
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(AirPost.MAX_BODY) },
                        placeholder = { Text(stringResource(Res.string.air_message_hint)) },
                        leadingIcon = {
                            Icon(
                                painterResource(Res.drawable.ic_lock_24dp),
                                contentDescription = stringResource(Res.string.air_e2ee_encrypted),
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        maxLines = 5,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        enabled = draft.isNotBlank() && root != null && StoatAPI.selfId != null,
                        onClick = {
                            val self = StoatAPI.selfId ?: return@FilledIconButton
                            AirP2P.publish(
                                channelId,
                                AirPost.create(
                                    channelId = channelId,
                                    id = ULID.makeNext(),
                                    kind = AirPost.KIND_REPLY,
                                    parent = postId,
                                    title = "",
                                    body = draft.trim(),
                                    tags = emptyList(),
                                    author = self,
                                    sourceMessage = null,
                                )
                            )
                            draft = ""
                        }
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_send_24dp),
                            contentDescription = stringResource(Res.string.air_send)
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.zero
    ) { pv ->
        LazyColumn(
            modifier = Modifier
                .padding(pv)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (root != null) {
                item(key = "root") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AirAuthorLine(root.author, channelId, root.createdAt)
                            if (root.title.isNotBlank()) {
                                Text(root.title, style = MaterialTheme.typography.titleLarge)
                            }
                            if (root.body.isNotBlank()) {
                                ChatMarkdown(content = root.body, serverId = serverId)
                            }
                            if (root.tags.isNotEmpty()) AirTagRow(root.tags)
                            root.sourceMessage?.let { messageId ->
                                Text(
                                    text = stringResource(Res.string.air_thread_from_message),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                StoatAPI.messageCache[messageId]?.content?.let { quoted ->
                                    Text(
                                        text = quoted,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "count") {
                    Text(
                        text = stringResource(Res.string.air_replies_count, replies.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            } else {
                item(key = "missing") {
                    Text(
                        text = stringResource(Res.string.air_empty_title),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp)
                    )
                }
            }

            items(replies, key = { it.id }) { reply ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AirAuthorLine(reply.author, channelId, reply.createdAt)
                    Column(Modifier.padding(start = 32.dp)) {
                        ChatMarkdown(content = reply.body, serverId = serverId)
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}
