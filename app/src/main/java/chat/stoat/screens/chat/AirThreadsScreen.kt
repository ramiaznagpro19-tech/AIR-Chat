package chat.stoat.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import chat.stoat.api.StoatAPI
import chat.stoat.api.internals.ULID
import chat.stoat.composables.chat.displayNameInChannel
import chat.stoat.composables.chat.formatLongAsTime
import chat.stoat.composables.generic.UserAvatar
import chat.stoat.core.model.schemas.User
import chat.stoat.e2ee.AirE2EE
import chat.stoat.internals.extensions.zero
import chat.stoat.p2p.AirP2P
import chat.stoat.p2p.AirPost
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.air_cancel
import chat.stoat.shared.resources.air_create
import chat.stoat.shared.resources.air_e2ee_toggle
import chat.stoat.shared.resources.air_empty_title
import chat.stoat.shared.resources.air_forum
import chat.stoat.shared.resources.air_forum_empty
import chat.stoat.shared.resources.air_message_hint
import chat.stoat.shared.resources.air_new_forum_post
import chat.stoat.shared.resources.air_new_thread
import chat.stoat.shared.resources.air_p2p_alone
import chat.stoat.shared.resources.air_p2p_explainer
import chat.stoat.shared.resources.air_p2p_needs_e2ee
import chat.stoat.shared.resources.air_p2p_peers
import chat.stoat.shared.resources.air_replies_count
import chat.stoat.shared.resources.air_tags_hint
import chat.stoat.shared.resources.air_thread_from_message
import chat.stoat.shared.resources.air_threads
import chat.stoat.shared.resources.air_threads_and_forums
import chat.stoat.shared.resources.air_threads_empty
import chat.stoat.shared.resources.air_title_hint
import chat.stoat.shared.resources.back
import chat.stoat.shared.resources.ic_add_24dp
import chat.stoat.shared.resources.ic_arrow_back_24dp
import chat.stoat.shared.resources.ic_chat_24dp
import chat.stoat.shared.resources.ic_devices_24dp
import chat.stoat.shared.resources.ic_forum_24dp
import chat.stoat.shared.resources.ic_lock_24dp
import chat.stoat.shared.resources.ic_reply_24dp
import chat.stoat.shared.resources.ic_tag_24dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * AIR Chat: threads & forums of a conversation, stored on members' devices and synced
 * peer-to-peer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirThreadsScreen(
    navController: NavController,
    channelId: String,
    fromMessageId: String? = null,
) {
    val store = remember(channelId) { AirP2P.store(channelId) }
    val posts by store.posts.collectAsState()
    val peerCounts by AirP2P.peerCounts.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val revision = AirE2EE.revision
    val e2eeEnabled = AirE2EE.isEnabled(channelId)

    var selectedTab by remember { mutableIntStateOf(0) }
    var composeKind by remember { mutableStateOf<String?>(null) }
    var composeSource by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(channelId, e2eeEnabled) {
        if (e2eeEnabled) AirP2P.activate(channelId)
    }
    LaunchedEffect(fromMessageId) {
        if (fromMessageId != null) {
            selectedTab = 0
            composeSource = fromMessageId
            composeKind = AirPost.KIND_THREAD
        }
    }

    val kind = if (selectedTab == 0) AirPost.KIND_THREAD else AirPost.KIND_FORUM
    val replies = posts.values.filter { it.kind == AirPost.KIND_REPLY }.groupBy { it.parent }
    val roots = posts.values
        .filter { it.kind == kind }
        .sortedByDescending { root ->
            maxOf(root.createdAt, replies[root.id]?.maxOfOrNull { it.createdAt } ?: 0L)
        }

    composeKind?.let { composing ->
        AirComposePostDialog(
            kind = composing,
            initialBody = composeSource?.let { StoatAPI.messageCache[it]?.content } ?: "",
            onDismiss = {
                composeKind = null
                composeSource = null
            },
            onCreate = { title, body, tags ->
                val self = StoatAPI.selfId
                if (self != null) {
                    val post = AirPost.create(
                        channelId = channelId,
                        id = ULID.makeNext(),
                        kind = composing,
                        parent = null,
                        title = title,
                        body = body,
                        tags = tags,
                        author = self,
                        sourceMessage = composeSource,
                    )
                    AirP2P.publish(channelId, post)
                    navController.navigate("channel/$channelId/thread/${post.id}")
                }
                composeKind = null
                composeSource = null
            }
        )
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
                            text = stringResource(Res.string.air_threads_and_forums),
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
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(Res.string.air_threads)) },
                        icon = { Icon(painterResource(Res.drawable.ic_chat_24dp), null) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(Res.string.air_forum)) },
                        icon = { Icon(painterResource(Res.drawable.ic_forum_24dp), null) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (e2eeEnabled) {
                ExtendedFloatingActionButton(
                    onClick = { composeKind = kind },
                    icon = { Icon(painterResource(Res.drawable.ic_add_24dp), null) },
                    text = {
                        Text(
                            stringResource(
                                if (kind == AirPost.KIND_THREAD) Res.string.air_new_thread
                                else Res.string.air_new_forum_post
                            )
                        )
                    }
                )
            }
        },
        contentWindowInsets = WindowInsets.zero
    ) { pv ->
        if (!e2eeEnabled) {
            Box(
                modifier = Modifier
                    .padding(pv)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 48.dp)
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_lock_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(Res.string.air_p2p_needs_e2ee),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { AirE2EE.setEnabled(channelId, true) }) {
                        Text(stringResource(Res.string.air_e2ee_toggle))
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(pv)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "status") {
                AirP2PStatusCard(peers = peerCounts[channelId] ?: 0)
            }

            if (roots.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(
                            if (kind == AirPost.KIND_THREAD) Res.string.air_threads_empty
                            else Res.string.air_forum_empty
                        ),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp)
                    )
                }
            }

            items(roots, key = { it.id }) { post ->
                AirPostCard(
                    post = post,
                    channelId = channelId,
                    replyCount = replies[post.id]?.size ?: 0,
                    onClick = { navController.navigate("channel/$channelId/thread/${post.id}") }
                )
            }
        }
    }
}

@Composable
internal fun AirP2PStatusCard(peers: Int) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painterResource(Res.drawable.ic_devices_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = if (peers > 0) stringResource(Res.string.air_p2p_peers, peers)
                    else stringResource(Res.string.air_p2p_alone),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.air_p2p_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
internal fun AirAuthorLine(userId: String, channelId: String, createdAt: Long) {
    val user = StoatAPI.userCache[userId]
    Row(verticalAlignment = Alignment.CenterVertically) {
        UserAvatar(
            username = user?.let { User.resolveDefaultName(it) } ?: userId,
            userId = userId,
            avatar = user?.avatar,
            size = 24.dp
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = displayNameInChannel(userId, channelId),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = formatLongAsTime(createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun AirPostCard(post: AirPost, channelId: String, replyCount: Int, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AirAuthorLine(post.author, channelId, post.createdAt)
            Text(
                text = post.title.ifBlank { stringResource(Res.string.air_empty_title) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (post.body.isNotBlank()) {
                Text(
                    text = post.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (post.tags.isNotEmpty()) {
                AirTagRow(post.tags)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(Res.drawable.ic_reply_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(Res.string.air_replies_count, replyCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (post.sourceMessage != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(Res.string.air_thread_from_message),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AirTagRow(tags: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tags.forEach { tag ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_tag_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun AirComposePostDialog(
    kind: String,
    initialBody: String,
    onDismiss: () -> Unit,
    onCreate: (title: String, body: String, tags: List<String>) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var body by remember(initialBody) { mutableStateOf(initialBody) }
    var tags by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (kind == AirPost.KIND_THREAD) Res.string.air_new_thread
                    else Res.string.air_new_forum_post
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(AirPost.MAX_TITLE) },
                    label = { Text(stringResource(Res.string.air_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it.take(AirPost.MAX_BODY) },
                    label = { Text(stringResource(Res.string.air_message_hint)) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                if (kind == AirPost.KIND_FORUM) {
                    OutlinedTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        label = { Text(stringResource(Res.string.air_tags_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() || body.isNotBlank(),
                onClick = { onCreate(title.trim(), body.trim(), tags.split(',')) }
            ) {
                Text(stringResource(Res.string.air_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.air_cancel))
            }
        }
    )
}
