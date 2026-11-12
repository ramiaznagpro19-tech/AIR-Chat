package chat.stoat.air.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import chat.stoat.air.AirAccount
import chat.stoat.air.AirConversation
import chat.stoat.air.AirItem
import chat.stoat.air.AirNetwork
import chat.stoat.air.NostrRelayPool
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.airnet_about
import chat.stoat.shared.resources.airnet_account
import chat.stoat.shared.resources.airnet_add_by_username
import chat.stoat.shared.resources.airnet_back
import chat.stoat.shared.resources.airnet_cancel
import chat.stoat.shared.resources.airnet_conversations
import chat.stoat.shared.resources.airnet_create
import chat.stoat.shared.resources.airnet_empty_forum
import chat.stoat.shared.resources.airnet_empty_threads
import chat.stoat.shared.resources.airnet_encrypted
import chat.stoat.shared.resources.airnet_forum
import chat.stoat.shared.resources.airnet_group_members
import chat.stoat.shared.resources.airnet_group_name
import chat.stoat.shared.resources.airnet_logout
import chat.stoat.shared.resources.airnet_members_online
import chat.stoat.shared.resources.airnet_message
import chat.stoat.shared.resources.airnet_new_chat
import chat.stoat.shared.resources.airnet_new_group
import chat.stoat.shared.resources.airnet_new_post
import chat.stoat.shared.resources.airnet_new_thread
import chat.stoat.shared.resources.airnet_no_conversations
import chat.stoat.shared.resources.airnet_offline
import chat.stoat.shared.resources.airnet_online
import chat.stoat.shared.resources.airnet_relays
import chat.stoat.shared.resources.airnet_replies
import chat.stoat.shared.resources.airnet_send
import chat.stoat.shared.resources.airnet_settings
import chat.stoat.shared.resources.airnet_start
import chat.stoat.shared.resources.airnet_tags
import chat.stoat.shared.resources.airnet_threads
import chat.stoat.shared.resources.airnet_threads_forums
import chat.stoat.shared.resources.airnet_title
import chat.stoat.shared.resources.airnet_user_not_found
import chat.stoat.shared.resources.airnet_your_key
import chat.stoat.shared.resources.airnet_your_key_hint
import chat.stoat.shared.resources.ic_add_24dp
import chat.stoat.shared.resources.ic_arrow_back_24dp
import chat.stoat.shared.resources.ic_chat_24dp
import chat.stoat.shared.resources.ic_forum_24dp
import chat.stoat.shared.resources.ic_group_add_24dp
import chat.stoat.shared.resources.ic_lock_24dp
import chat.stoat.shared.resources.ic_logout_24dp
import chat.stoat.shared.resources.ic_reply_24dp
import chat.stoat.shared.resources.ic_send_24dp
import chat.stoat.shared.resources.ic_settings_24dp
import chat.stoat.shared.resources.ic_tag_24dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.text.DateFormat
import java.util.Date

private fun formatTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val format = if (now - millis < 24 * 3600 * 1000L) DateFormat.getTimeInstance(DateFormat.SHORT)
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return format.format(Date(millis))
}

@Composable
fun AirHome(onLogout: () -> Unit) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { AirConversationList(navController) }
        composable("chat/{id}") { entry ->
            AirChatScreen(navController, entry.arguments?.getString("id") ?: "")
        }
        composable("threads/{id}") { entry ->
            AirThreadsListScreen(navController, entry.arguments?.getString("id") ?: "")
        }
        composable("thread/{id}/{item}") { entry ->
            AirThreadDetailScreen(
                navController,
                entry.arguments?.getString("id") ?: "",
                entry.arguments?.getString("item") ?: ""
            )
        }
        composable("settings") { AirSettingsScreen(navController, onLogout) }
    }
}

internal fun encodeRoute(id: String) = java.net.URLEncoder.encode(id, "UTF-8")
internal fun decodeRoute(id: String) = java.net.URLDecoder.decode(id, "UTF-8")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackTopBar(navController: NavController, title: String, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(painterResource(Res.drawable.ic_arrow_back_24dp), stringResource(Res.string.airnet_back))
            }
        },
        actions = { actions() }
    )
}

// ----------------------------------------------------------------------------------------
// Conversation list
// ----------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AirConversationList(navController: NavController) {
    val conversations by AirNetwork.conversations.collectAsState()
    val allItems by AirNetwork.items.collectAsState()
    val online by AirNetwork.online.collectAsState()
    val readUpTo by AirNetwork.readUpTo.collectAsState()
    var showNewChat by remember { mutableStateOf(false) }
    var showNewGroup by remember { mutableStateOf(false) }

    val messagesByConversation = allItems.values.filter { it.type == AirItem.MESSAGE }.groupBy { it.conversation }
    val sorted = conversations.values.sortedByDescending { c ->
        messagesByConversation[c.id]?.maxOfOrNull { it.createdAt } ?: c.createdAt
    }

    if (showNewChat) {
        NewChatDialog(
            onDismiss = { showNewChat = false },
            onOpened = { conversation ->
                showNewChat = false
                navController.navigate("chat/${encodeRoute(conversation.id)}")
            }
        )
    }
    if (showNewGroup) {
        NewGroupDialog(
            onDismiss = { showNewGroup = false },
            onCreated = { conversation ->
                showNewGroup = false
                navController.navigate("chat/${encodeRoute(conversation.id)}")
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AirLogo(size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("AIR Chat", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { showNewGroup = true }) {
                        Icon(painterResource(Res.drawable.ic_group_add_24dp), stringResource(Res.string.airnet_new_group))
                    }
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(painterResource(Res.drawable.ic_settings_24dp), stringResource(Res.string.airnet_settings))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewChat = true },
                icon = { Icon(painterResource(Res.drawable.ic_chat_24dp), null) },
                text = { Text(stringResource(Res.string.airnet_new_chat)) }
            )
        }
    ) { pv ->
        if (sorted.isEmpty()) {
            Box(Modifier.padding(pv).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(48.dp)) {
                    AirLogo(size = 96.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(Res.string.airnet_no_conversations),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(pv).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                Text(
                    stringResource(Res.string.airnet_conversations),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(sorted, key = { it.id }) { conversation ->
                val last = messagesByConversation[conversation.id]?.maxByOrNull { it.createdAt }
                val title = AirNetwork.conversationTitle(conversation)
                val isOnline = conversation.members.any { it != AirNetwork.selfKey && it in online }
                val unread = last != null && last.author != AirNetwork.selfKey &&
                        last.createdAt > (readUpTo[conversation.id] ?: 0L)
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    leadingContent = { AirAvatar(title.ifEmpty { "?" }, online = isOnline) },
                    headlineContent = {
                        Text(
                            title,
                            fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = {
                        Text(
                            last?.let {
                                (if (conversation.isGroup) AirNetwork.username(it.author) + " : " else "") + it.text
                            } ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            last?.let {
                                Text(formatTime(it.createdAt), style = MaterialTheme.typography.labelSmall)
                            }
                            if (unread) {
                                Spacer(Modifier.height(4.dp))
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    },
                    modifier = Modifier.clickable {
                        navController.navigate("chat/${encodeRoute(conversation.id)}")
                    }
                )
            }
        }
    }
}

@Composable
private fun NewChatDialog(onDismiss: () -> Unit, onOpened: (AirConversation) -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.airnet_new_chat)) },
        text = {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.trim().lowercase(); notFound = false },
                label = { Text(stringResource(Res.string.airnet_add_by_username)) },
                singleLine = true,
                isError = notFound,
                supportingText = { if (notFound) Text(stringResource(Res.string.airnet_user_not_found)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = !working && AirAccount.isUsernameValid(username),
                onClick = {
                    working = true
                    scope.launch {
                        val contact = AirNetwork.addContact(username)
                        working = false
                        if (contact == null) notFound = true
                        else onOpened(AirNetwork.openDirect(contact.publicKey))
                    }
                }
            ) { Text(stringResource(Res.string.airnet_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.airnet_cancel)) } }
    )
}

@Composable
private fun NewGroupDialog(onDismiss: () -> Unit, onCreated: (AirConversation) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var members by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.airnet_new_group)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    label = { Text(stringResource(Res.string.airnet_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = members,
                    onValueChange = { members = it.lowercase(); missing = null },
                    label = { Text(stringResource(Res.string.airnet_group_members)) },
                    isError = missing != null,
                    supportingText = {
                        missing?.let { Text(stringResource(Res.string.airnet_user_not_found) + " ($it)") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && name.isNotBlank() && members.isNotBlank(),
                onClick = {
                    working = true
                    scope.launch {
                        val keys = mutableSetOf<String>()
                        for (raw in members.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
                            val contact = AirNetwork.addContact(raw)
                            if (contact == null) {
                                missing = raw
                                working = false
                                return@launch
                            }
                            keys.add(contact.publicKey)
                        }
                        working = false
                        onCreated(AirNetwork.createGroup(name, keys))
                    }
                }
            ) { Text(stringResource(Res.string.airnet_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.airnet_cancel)) } }
    )
}

// ----------------------------------------------------------------------------------------
// Chat
// ----------------------------------------------------------------------------------------

@Composable
private fun AirChatScreen(navController: NavController, rawId: String) {
    val conversationId = decodeRoute(rawId)
    val conversations by AirNetwork.conversations.collectAsState()
    val allItems by AirNetwork.items.collectAsState()
    val online by AirNetwork.online.collectAsState()
    val conversation = conversations[conversationId] ?: return
    val messages = allItems.values
        .filter { it.conversation == conversationId && it.type == AirItem.MESSAGE }
        .sortedByDescending { it.createdAt }
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(messages.size) { AirNetwork.markRead(conversationId) }

    val others = conversation.members.filter { it != AirNetwork.selfKey }
    val onlineCount = others.count { it in online }
    val subtitle = if (conversation.isGroup) stringResource(Res.string.airnet_members_online, onlineCount)
    else if (onlineCount > 0) stringResource(Res.string.airnet_online)
    else stringResource(Res.string.airnet_offline)

    Scaffold(
        topBar = {
            BackTopBar(navController, AirNetwork.conversationTitle(conversation), subtitle) {
                if (!conversation.isGroup) others.firstOrNull()?.let { CallButtons(it) }
                IconButton(onClick = { navController.navigate("threads/${encodeRoute(conversationId)}") }) {
                    Icon(painterResource(Res.drawable.ic_forum_24dp), stringResource(Res.string.airnet_threads_forums))
                }
            }
        },
        bottomBar = {
            Composer(
                value = draft,
                onValueChange = { draft = it },
                onSend = {
                    AirNetwork.post(conversationId, AirItem.MESSAGE, draft.trim())
                    draft = ""
                }
            )
        }
    ) { pv ->
        LazyColumn(
            reverseLayout = true,
            modifier = Modifier.padding(pv).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message, showAuthor = conversation.isGroup)
            }
            item(key = "e2ee") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_lock_24dp),
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(Res.string.airnet_encrypted),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: AirItem, showAuthor: Boolean) {
    val mine = message.author == AirNetwork.selfKey
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (mine) 18.dp else 4.dp,
                bottomEnd = if (mine) 4.dp else 18.dp
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (showAuthor && !mine) {
                    Text(
                        AirNetwork.username(message.author),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
                Text(
                    formatTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
private fun Composer(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit) {
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
                value = value,
                onValueChange = { onValueChange(it.take(8000)) },
                placeholder = { Text(stringResource(Res.string.airnet_message)) },
                maxLines = 5,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(enabled = value.isNotBlank(), onClick = onSend) {
                Icon(painterResource(Res.drawable.ic_send_24dp), stringResource(Res.string.airnet_send))
            }
        }
    }
}

// ----------------------------------------------------------------------------------------
// Threads & forums
// ----------------------------------------------------------------------------------------

@Composable
internal fun AirThreadsListScreen(navController: NavController, rawId: String) {
    val conversationId = decodeRoute(rawId)
    val allItems by AirNetwork.items.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var composing by remember { mutableStateOf(false) }
    val type = if (tab == 0) AirItem.THREAD else AirItem.FORUM
    val inConversation = allItems.values.filter { it.conversation == conversationId }
    val replies = inConversation.filter { it.type == AirItem.REPLY }.groupBy { it.parent }
    val roots = inConversation.filter { it.type == type }.sortedByDescending { root ->
        maxOf(root.createdAt, replies[root.id]?.maxOfOrNull { it.createdAt } ?: 0L)
    }

    if (composing) {
        NewPostDialog(
            forum = type == AirItem.FORUM,
            onDismiss = { composing = false },
            onCreate = { title, body, tags ->
                AirNetwork.post(conversationId, type, body, title, tags)
                composing = false
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                BackTopBar(navController, stringResource(Res.string.airnet_threads_forums))
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(Res.string.airnet_threads)) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(Res.string.airnet_forum)) })
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { composing = true },
                icon = { Icon(painterResource(Res.drawable.ic_add_24dp), null) },
                text = {
                    Text(stringResource(if (type == AirItem.THREAD) Res.string.airnet_new_thread else Res.string.airnet_new_post))
                }
            )
        }
    ) { pv ->
        LazyColumn(
            modifier = Modifier.padding(pv).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (roots.isEmpty()) {
                item {
                    Text(
                        stringResource(if (type == AirItem.THREAD) Res.string.airnet_empty_threads else Res.string.airnet_empty_forum),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp)
                    )
                }
            }
            items(roots, key = { it.id }) { root ->
                PostCard(root, replies[root.id]?.size ?: 0) {
                    navController.navigate("thread/${encodeRoute(conversationId)}/${root.id}")
                }
            }
        }
    }
}

@Composable
private fun AuthorLine(author: String, time: Long) {
    val name = AirNetwork.username(author)
    Row(verticalAlignment = Alignment.CenterVertically) {
        AirAvatar(name, size = 24.dp)
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(formatTime(time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TagRow(tags: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.take(4).forEach { tag ->
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Icon(
                        painterResource(Res.drawable.ic_tag_24dp), null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}

@Composable
private fun PostCard(post: AirItem, replyCount: Int, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AuthorLine(post.author, post.createdAt)
            if (post.title.isNotBlank()) Text(post.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (post.text.isNotBlank()) {
                Text(
                    post.text, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis
                )
            }
            if (post.tags.isNotEmpty()) TagRow(post.tags)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Res.drawable.ic_reply_24dp), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(Res.string.airnet_replies, replyCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
internal fun AirThreadDetailScreen(navController: NavController, rawId: String, itemId: String) {
    val conversationId = decodeRoute(rawId)
    val allItems by AirNetwork.items.collectAsState()
    val root = allItems[itemId]
    val replies = allItems.values.filter { it.type == AirItem.REPLY && it.parent == itemId }.sortedBy { it.createdAt }
    var draft by remember { mutableStateOf("") }

    Scaffold(
        topBar = { BackTopBar(navController, root?.title?.ifBlank { null } ?: stringResource(Res.string.airnet_threads)) },
        bottomBar = {
            Composer(draft, { draft = it }) {
                AirNetwork.post(conversationId, AirItem.REPLY, draft.trim(), parent = itemId)
                draft = ""
            }
        }
    ) { pv ->
        LazyColumn(
            modifier = Modifier.padding(pv).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            root?.let {
                item(key = "root") {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AuthorLine(it.author, it.createdAt)
                            if (it.title.isNotBlank()) Text(it.title, style = MaterialTheme.typography.titleLarge)
                            if (it.text.isNotBlank()) Text(it.text, style = MaterialTheme.typography.bodyLarge)
                            if (it.tags.isNotEmpty()) TagRow(it.tags)
                        }
                    }
                }
                item(key = "count") {
                    Text(
                        stringResource(Res.string.airnet_replies, replies.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            items(replies, key = { it.id }) { reply ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AuthorLine(reply.author, reply.createdAt)
                    Text(reply.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 32.dp))
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun NewPostDialog(forum: Boolean, onDismiss: () -> Unit, onCreate: (String, String, List<String>) -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (forum) Res.string.airnet_new_post else Res.string.airnet_new_thread)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(200) }, label = { Text(stringResource(Res.string.airnet_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body, { body = it.take(8000) }, label = { Text(stringResource(Res.string.airnet_message)) }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                if (forum) {
                    OutlinedTextField(tags, { tags = it }, label = { Text(stringResource(Res.string.airnet_tags)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() || body.isNotBlank(), onClick = { onCreate(title.trim(), body.trim(), tags.split(',')) }) {
                Text(stringResource(Res.string.airnet_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.airnet_cancel)) } }
    )
}

// ----------------------------------------------------------------------------------------
// Settings
// ----------------------------------------------------------------------------------------

@Composable
private fun AirSettingsScreen(navController: NavController, onLogout: () -> Unit) {
    val relays by NostrRelayPool.connectedCount.collectAsState()
    val key = AirNetwork.selfKey ?: ""
    Scaffold(topBar = { BackTopBar(navController, stringResource(Res.string.airnet_settings)) }) { pv ->
        Column(
            modifier = Modifier.padding(pv).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AirAvatar(AirNetwork.selfUsername ?: "?", size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(stringResource(Res.string.airnet_account), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(AirNetwork.selfUsername ?: "", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            AirInfoCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(Res.string.airnet_your_key), style = MaterialTheme.typography.labelLarge)
                    Text(
                        key.take(40).uppercase().chunked(4).joinToString(" "),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(stringResource(Res.string.airnet_your_key_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AirLogo(size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(Res.string.airnet_about), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(Res.string.airnet_relays, relays),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(Res.drawable.ic_logout_24dp), null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.airnet_logout))
            }
        }
    }
}
