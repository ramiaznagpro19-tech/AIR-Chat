package chat.stoat.air

import android.content.Context
import android.util.Log
import chat.stoat.e2ee.AirCrypto
import chat.stoat.e2ee.AirVault
import io.livekit.android.LiveKit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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

data class AirContact(val publicKey: String, val username: String)

data class AirConversation(
    val id: String,
    val isGroup: Boolean,
    val name: String,
    val members: Set<String>,
    val createdAt: Long,
    /** "dm", "group", "server" or "channel" (a text channel of a server). */
    val kind: String = if (isGroup) KIND_GROUP else KIND_DM,
    /** For a server channel: the server conversation it belongs to. */
    val parent: String? = null,
    /** Creator of a group or server. */
    val owner: String? = null,
    /** For a server: private key of its invitations, known by every member. */
    val secret: String? = null,
    /** For a server: its public link, e.g. air.chat.abnf372H */
    val link: String? = null,
) {
    companion object {
        const val KIND_DM = "dm"
        const val KIND_GROUP = "group"
        const val KIND_SERVER = "server"
        const val KIND_CHANNEL = "channel"
    }
}

/**
 * A message, a thread/forum post or a reply. Items are signed by their author, so they can be
 * relayed between members without anyone being able to forge or alter them.
 */
data class AirItem(
    val id: String,
    val conversation: String,
    val author: String,
    val createdAt: Long,
    val type: String,
    val text: String,
    val title: String = "",
    val tags: List<String> = emptyList(),
    val parent: String? = null,
    val signature: String = "",
) {
    fun signedBytes(): ByteArray = AirCrypto.sha256(
        JSONArray().apply {
            put(id); put(conversation); put(author); put(createdAt); put(type); put(text)
            put(title); put(JSONArray(tags)); put(parent ?: "")
        }.toString().toByteArray(Charsets.UTF_8)
    )

    fun isValid(): Boolean = runCatching {
        Secp256k1.verifySchnorr(signedBytes(), Secp256k1.unhex(author), Secp256k1.unhex(signature))
    }.getOrDefault(false)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("conversation", conversation); put("author", author)
        put("createdAt", createdAt); put("type", type); put("text", text); put("title", title)
        put("tags", JSONArray(tags)); put("parent", parent ?: ""); put("signature", signature)
    }

    companion object {
        const val MESSAGE = "msg"
        const val THREAD = "thread"
        const val FORUM = "forum"
        const val REPLY = "reply"
        const val EDIT = "edit"
        const val DELETE = "delete"
        const val REACT = "react"
        const val UNREACT = "unreact"
        /** Server administration (roles, bans, emojis, settings…), see [AirServers]. */
        const val OP = "op"
        /** "X joined the server" */
        const val JOIN = "join"

        fun fromJson(json: JSONObject) = AirItem(
            id = json.getString("id"),
            conversation = json.getString("conversation"),
            author = json.getString("author"),
            createdAt = json.getLong("createdAt"),
            type = json.getString("type"),
            text = json.optString("text").take(8000),
            title = json.optString("title").take(200),
            tags = json.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?: emptyList(),
            parent = json.optString("parent").ifEmpty { null },
            signature = json.getString("signature"),
        )
    }
}

/**
 * AIR Chat's own peer-to-peer messaging network.
 *
 * - Contacts are found by username.
 * - Conversations (one-to-one and groups) live only on members' phones, in encrypted storage.
 * - Phones talk to each other directly over WebRTC data channels (DTLS encrypted end to end).
 *   Public relays only carry short connection set-up messages, themselves end-to-end encrypted.
 * - Messages written while a contact is offline are delivered the next time both are online.
 */
object AirNetwork {
    private const val TAG = "AirNetwork"
    const val SIGNAL_KIND = 21420
    private const val HELLO_INTERVAL_MS = 60_000L

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var session: AirAccount.Session? = null
    private var vault: AirVault? = null
    private var subscriptionId: String? = null
    private var helloJob: Job? = null

    private val _contacts = MutableStateFlow<Map<String, AirContact>>(emptyMap())
    val contacts: StateFlow<Map<String, AirContact>> = _contacts.asStateFlow()

    private val _conversations = MutableStateFlow<Map<String, AirConversation>>(emptyMap())
    val conversations: StateFlow<Map<String, AirConversation>> = _conversations.asStateFlow()

    private val _items = MutableStateFlow<Map<String, AirItem>>(emptyMap())
    val items: StateFlow<Map<String, AirItem>> = _items.asStateFlow()

    private val _online = MutableStateFlow<Set<String>>(emptySet())
    val online: StateFlow<Set<String>> = _online.asStateFlow()

    private val _readUpTo = MutableStateFlow<Map<String, Long>>(emptyMap())
    val readUpTo: StateFlow<Map<String, Long>> = _readUpTo.asStateFlow()

    val selfKey: String? get() = session?.publicKey
    val selfUsername: String? get() = session?.username

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    // ------------------------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------------------------

    fun start(newSession: AirAccount.Session) {
        if (session?.publicKey == newSession.publicKey) return
        stop()
        session = newSession
        vault = AirVault(appContext, "net3_${newSession.publicKey.take(16)}.vault")
        load()
        NostrRelayPool.start()
        subscriptionId = NostrRelayPool.subscribe(
            listOf(
                JSONObject()
                    .put("kinds", JSONArray().put(SIGNAL_KIND))
                    .put("#p", JSONArray().put(newSession.publicKey))
                    .put("since", System.currentTimeMillis() / 1000 - 30)
            )
        ) { onSignalEvent(it) }
        refreshInviteSubscriptions()
        AirOfficial.ensure()
        // the chief of AIR Chat is the very first account ever created
        scope.launch {
            runCatching { AirAccount.resolveOwner() }
            AirOfficial.ensure()
        }
        AirNetworkService.start(appContext)
        helloJob = scope.launch {
            while (isActive) {
                helloEveryone()
                delay(HELLO_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        helloJob?.cancel()
        inviteSubscriptions.values.forEach { NostrRelayPool.unsubscribe(it) }
        inviteSubscriptions.clear()
        if (::appContext.isInitialized) AirNetworkService.stop(appContext)
        subscriptionId?.let { NostrRelayPool.unsubscribe(it) }
        subscriptionId = null
        peers.keys.toList().forEach { closePeer(it) }
        session = null
        vault = null
        _contacts.value = emptyMap()
        _conversations.value = emptyMap()
        _items.value = emptyMap()
        _online.value = emptySet()
        _readUpTo.value = emptyMap()
        _acks.value = emptyMap()
        _names.value = emptyMap()
        _profiles.value = emptyMap()
        fileMetas.clear()
        settings.clear()
    }

    // ------------------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------------------

    fun username(publicKey: String): String =
        if (publicKey == selfKey) selfUsername ?: publicKey.take(8)
        else _contacts.value[publicKey]?.username ?: _names.value[publicKey] ?: publicKey.take(8)

    /** Usernames of people we are not friends with (members of groups and servers). */
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()
    private val resolving = ConcurrentHashMap.newKeySet<String>()

    /** Looks up, in the background, the usernames of accounts we do not know yet. */
    fun resolveNames(keys: Collection<String>) {
        val unknown = keys.filter {
            it != selfKey && !_contacts.value.containsKey(it) && !_names.value.containsKey(it) && resolving.add(it)
        }
        if (unknown.isEmpty()) return
        scope.launch {
            try {
                val events = NostrRelayPool.query(
                    listOf(
                        JSONObject()
                            .put("kinds", JSONArray().put(AirAccount.USERNAME_KIND))
                            .put("authors", JSONArray(unknown))
                    )
                )
                val found = mutableMapOf<String, String>()
                events.groupBy { it.pubkey }.forEach { (key, list) ->
                    val name = list.minByOrNull { it.createdAt }
                        ?.let { runCatching { JSONObject(it.content).optString("username") }.getOrNull() }
                        ?.takeIf { AirAccount.isUsernameValid(it) } ?: return@forEach
                    // the name only belongs to this account if its reservation is the oldest
                    if (AirAccount.ownerOf(name) == key) found[key] = AirAccount.normalize(name)
                }
                if (found.isNotEmpty()) synchronized(lock) {
                    _names.value = _names.value + found
                    save()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve usernames", e)
            } finally {
                unknown.forEach { resolving.remove(it) }
            }
        }
    }

    /** Adds a contact by username. Returns the contact, or null if the username does not exist. */
    suspend fun addContact(username: String): AirContact? {
        val name = AirAccount.normalize(username)
        val owner = AirAccount.ownerOf(name) ?: return null
        if (owner == selfKey) return null
        val contact = AirContact(owner, name)
        synchronized(lock) {
            _contacts.value = _contacts.value + (owner to contact)
            save()
        }
        sendHello(owner)
        return contact
    }

    fun directConversationId(other: String): String {
        val me = selfKey ?: ""
        return "dm:" + listOf(me, other).sorted().joinToString(":")
    }

    fun openDirect(other: String): AirConversation {
        val id = directConversationId(other)
        _conversations.value[id]?.let { return it }
        val conversation = AirConversation(
            id = id,
            isGroup = false,
            name = "",
            members = setOf(selfKey ?: "", other),
            createdAt = System.currentTimeMillis()
        )
        synchronized(lock) {
            _conversations.value = _conversations.value + (id to conversation)
            save()
        }
        return conversation
    }

    fun createGroup(name: String, members: Set<String>): AirConversation {
        val me = selfKey ?: error("Not logged in")
        val conversation = AirConversation(
            id = "group:" + AirCrypto.hex(AirCrypto.randomBytes(12)),
            isGroup = true,
            name = name.trim().take(64),
            members = members + me,
            createdAt = System.currentTimeMillis()
        )
        synchronized(lock) {
            _conversations.value = _conversations.value + (conversation.id to conversation)
            save()
        }
        members.forEach { member ->
            sendSignal(member, JSONObject().put("t", "invite").put("conv", conversationJson(conversation)))
            peers[member]?.let { if (it.isOpen) sendConversation(it, conversation) }
        }
        return conversation
    }

    fun conversationTitle(conversation: AirConversation): String =
        if (conversation.isGroup) conversation.name
        else conversation.members.firstOrNull { it != selfKey }?.let { username(it) } ?: ""

    fun post(
        conversationId: String,
        type: String,
        text: String,
        title: String = "",
        tags: List<String> = emptyList(),
        parent: String? = null,
    ): AirItem? {
        val current = session ?: return null
        val unsigned = AirItem(
            id = chat.stoat.api.internals.ULID.makeNext(),
            conversation = conversationId,
            author = current.publicKey,
            createdAt = System.currentTimeMillis(),
            type = type,
            text = text.take(8000),
            title = title.take(200),
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.take(8),
            parent = parent,
        )
        val item = unsigned.copy(
            signature = Secp256k1.hex(Secp256k1.signSchnorr(unsigned.signedBytes(), current.privateKey))
        )
        addItems(listOf(item))
        broadcastItems(conversationId, listOf(item), except = null)
        _conversations.value[conversationId]?.members?.forEach { member ->
            if (member != selfKey && peers[member]?.isOpen != true) sendHello(member)
        }
        return item
    }

    // ------------------------------------------------------------------------------------
    // Servers, groups and contacts
    // ------------------------------------------------------------------------------------

    private fun newConversationId(prefix: String) = prefix + ":" + AirCrypto.hex(AirCrypto.randomBytes(12))

    private fun putConversation(conversation: AirConversation) {
        synchronized(lock) {
            _conversations.value = _conversations.value + (conversation.id to conversation)
            save()
        }
        if (conversation.kind == AirConversation.KIND_SERVER) refreshInviteSubscriptions()
    }

    /** Sends a conversation to all its members (open peers now, the others by invitation). */
    private fun shareConversation(conversation: AirConversation, only: Set<String>? = null) {
        conversation.members.filter { it != selfKey && (only == null || it in only) }.forEach { member ->
            val peer = peers[member]
            if (peer?.isOpen == true) sendConversation(peer, conversation)
            else sendSignal(member, JSONObject().put("t", "invite").put("conv", conversationJson(conversation)))
        }
    }

    fun channelsOf(serverId: String): List<AirConversation> =
        _conversations.value.values
            .filter { it.kind == AirConversation.KIND_CHANNEL && it.parent == serverId }
            .sortedBy { it.createdAt }

    fun createServer(name: String): Pair<AirConversation, AirConversation> {
        val me = selfKey ?: error("Not logged in")
        val now = System.currentTimeMillis()
        val server = AirConversation(
            id = newConversationId("server"), isGroup = true, name = name.trim().take(64),
            members = setOf(me), createdAt = now, kind = AirConversation.KIND_SERVER, owner = me,
            secret = Secp256k1.hex(Secp256k1.randomPrivateKey())
        )
        val general = AirConversation(
            id = newConversationId("channel"), isGroup = true, name = "general",
            members = setOf(me), createdAt = now + 1, kind = AirConversation.KIND_CHANNEL,
            parent = server.id, owner = me
        )
        putConversation(server)
        putConversation(general)
        AirServers.ensureJoinItem(server.id)
        // reserve its public link (air.chat.xxxx000X) in the background
        scope.launch { ensureLink(server.id) }
        return server to general
    }

    fun createServerChannel(serverId: String, name: String): AirConversation? {
        val server = _conversations.value[serverId] ?: return null
        val me = selfKey ?: return null
        if (server.owner != me) return null
        val channel = AirConversation(
            id = newConversationId("channel"), isGroup = true, name = name.trim().take(64),
            members = server.members, createdAt = System.currentTimeMillis(),
            kind = AirConversation.KIND_CHANNEL, parent = server.id, owner = me
        )
        putConversation(channel)
        shareConversation(channel)
        return channel
    }

    fun renameConversation(conversationId: String, name: String): AirConversation? {
        val conversation = _conversations.value[conversationId] ?: return null
        if (conversation.owner != null && conversation.owner != selfKey) return null
        val renamed = conversation.copy(name = name.trim().take(64))
        putConversation(renamed)
        shareConversation(renamed)
        return renamed
    }

    fun addMembers(conversationId: String, newMembers: Set<String>): AirConversation? {
        val conversation = _conversations.value[conversationId] ?: return null
        if (conversation.kind == AirConversation.KIND_DM) return null
        val updated = conversation.copy(members = conversation.members + newMembers)
        putConversation(updated)
        shareConversation(updated)
        if (conversation.kind == AirConversation.KIND_SERVER) {
            channelsOf(conversation.id).forEach { channel ->
                val c = channel.copy(members = channel.members + newMembers)
                putConversation(c)
                shareConversation(c)
            }
        }
        return updated
    }

    /** Leaves (hides) a conversation on this device. */
    fun leaveConversation(conversationId: String) {
        if (AirOfficial.isOpen(conversationId)) {
            AirOfficial.setHidden(true)
            return
        }
        synchronized(lock) {
            val ids = setOf(conversationId) + channelsOf(conversationId).map { it.id }
            _conversations.value = _conversations.value - ids
            save()
        }
    }

    /** Adds or replaces a conversation created on this device (used by the official server). */
    internal fun upsertConversation(conversation: AirConversation) = putConversation(conversation)

    fun removeContact(publicKey: String) {
        synchronized(lock) {
            _contacts.value = _contacts.value - publicKey
            save()
        }
    }

    // ---- Server invitations ----

    /**
     * Invitation code for a server: the public key of its invitations + server id + name.
     * Any member online can let the person in, since every member knows the matching key.
     */
    fun inviteCode(serverId: String): String? {
        var server = _conversations.value[serverId] ?: return null
        if (server.secret == null) {
            if (server.owner != selfKey) return null
            server = server.copy(secret = Secp256k1.hex(Secp256k1.randomPrivateKey()))
            putConversation(server)
            shareConversation(server)
        }
        val invitePublic = Secp256k1.publicKey(Secp256k1.unhex(server.secret!!))
        val payload = invitePublic + Secp256k1.unhex(serverId.substringAfter(':')) +
                server.name.toByteArray(Charsets.UTF_8).take(40).toByteArray()
        return AirCrypto.b64(payload)
    }

    /** Gives a server its unique public link, if it does not have one yet. */
    suspend fun ensureLink(serverId: String): String? {
        val server = _conversations.value[serverId] ?: return null
        server.link?.let { return it }
        if (server.owner != selfKey) return null
        val secret = server.secret ?: Secp256k1.hex(Secp256k1.randomPrivateKey())
        val invitePublic = Secp256k1.hex(Secp256k1.publicKey(Secp256k1.unhex(secret)))
        val link = AirServers.claimLink(server.copy(secret = secret), invitePublic) ?: return null
        val updated = (_conversations.value[serverId] ?: server).copy(secret = secret, link = link)
        putConversation(updated)
        shareConversation(updated)
        return link
    }

    data class InviteInfo(val inviteKey: String, val serverId: String, val name: String)

    fun parseInvite(code: String): InviteInfo? = runCatching {
        val bytes = AirCrypto.unb64(code.trim())
        require(bytes.size >= 44)
        InviteInfo(
            inviteKey = Secp256k1.hex(bytes.copyOfRange(0, 32)),
            serverId = "server:" + Secp256k1.hex(bytes.copyOfRange(32, 44)),
            name = String(bytes.copyOfRange(44, bytes.size), Charsets.UTF_8)
        )
    }.getOrNull()

    /** Asks the members of a server to add this account. One of them must be online. */
    fun requestJoin(invite: InviteInfo) {
        sendSignal(invite.inviteKey, JSONObject().put("t", "join").put("server", invite.serverId))
    }

    private fun onJoinRequest(from: String, serverId: String) {
        val server = _conversations.value[serverId] ?: return
        if (server.kind != AirConversation.KIND_SERVER || selfKey !in server.members) return
        if (AirServers.state(server).bans.containsKey(from)) return
        if (from in AirServers.state(server).removed) {
            post(serverId, AirItem.OP, JSONObject().put("user", from).toString(), title = "readd")
        }
        val updated = addMembers(serverId, setOf(from)) ?: return
        // make sure the new member receives the server and every channel
        shareConversation(updated, only = setOf(from))
        channelsOf(serverId).forEach { shareConversation(it, only = setOf(from)) }
    }

    /** Invitation keys of the servers we are in, to receive join requests. */
    private val inviteSubscriptions = ConcurrentHashMap<String, String>()

    private fun refreshInviteSubscriptions() {
        if (session == null) return
        val wanted = _conversations.value.values
            .filter { it.kind == AirConversation.KIND_SERVER && it.secret != null }
            .associate { Secp256k1.hex(Secp256k1.publicKey(Secp256k1.unhex(it.secret!!))) to it.secret!! }
        (inviteSubscriptions.keys - wanted.keys).forEach { key ->
            inviteSubscriptions.remove(key)?.let { NostrRelayPool.unsubscribe(it) }
        }
        (wanted.keys - inviteSubscriptions.keys).forEach { invitePublic ->
            inviteSubscriptions[invitePublic] = NostrRelayPool.subscribe(
                listOf(
                    JSONObject()
                        .put("kinds", JSONArray().put(SIGNAL_KIND))
                        .put("#p", JSONArray().put(invitePublic))
                        .put("since", System.currentTimeMillis() / 1000 - 30)
                )
            ) { event -> onInviteSignal(event, invitePublic, wanted[invitePublic]!!) }
        }
    }

    private fun onInviteSignal(event: NostrEvent, invitePublic: String, invitePrivate: String) {
        if (!handledSignals.add(event.id)) return
        if (System.currentTimeMillis() / 1000 - event.createdAt > 120) return
        val from = event.pubkey
        if (from == selfKey) return
        val json = try {
            val shared = Secp256k1.sharedSecret(Secp256k1.unhex(invitePrivate), Secp256k1.unhex(from))
            val key = AirCrypto.hkdf(shared, "AIR Chat".toByteArray(), "AIR Chat signalling v1")
            JSONObject(String(AirCrypto.open(key, AirCrypto.unb64(event.content), "S|$invitePublic")))
        } catch (e: Exception) {
            return
        }
        if (json.optString("t") != "join") return
        scope.launch {
            rememberSender(from, json.optString("from_name"))
            onJoinRequest(from, json.optString("server"))
        }
    }

    // ---- Profiles (display name and avatar, shared directly with connected peers) ----

    data class Profile(val displayName: String?, val avatar: String?, val updatedAt: Long)

    private val _profiles = MutableStateFlow<Map<String, Profile>>(emptyMap())
    val profiles: StateFlow<Map<String, Profile>> = _profiles.asStateFlow()

    fun ownProfile(): Profile = selfKey?.let { _profiles.value[it] } ?: Profile(null, null, 0)

    fun updateOwnProfile(displayName: String?, avatar: String?) {
        val me = selfKey ?: return
        val profile = Profile(displayName?.trim()?.take(32)?.ifBlank { null }, avatar, System.currentTimeMillis())
        synchronized(lock) {
            _profiles.value = _profiles.value + (me to profile)
            save()
        }
        peers.values.filter { it.isOpen }.forEach { sendProfile(it) }
    }

    private fun profileJson(p: Profile) = JSONObject().apply {
        put("displayName", p.displayName ?: ""); put("avatar", p.avatar ?: ""); put("updatedAt", p.updatedAt)
        p.avatar?.let { id -> fileMeta(id)?.let { put("avatarMeta", it.toJson()) } }
    }

    private fun profileFromJson(json: JSONObject) = Profile(
        displayName = json.optString("displayName").take(32).ifBlank { null },
        avatar = json.optString("avatar").ifBlank { null },
        updatedAt = json.optLong("updatedAt"),
    )

    private fun sendProfile(peer: Peer) {
        val own = selfKey?.let { _profiles.value[it] } ?: return
        send(peer, JSONObject().put("t", "profile").put("profile", profileJson(own)))
    }

    private fun onProfile(peer: Peer, json: JSONObject) {
        val incoming = profileFromJson(json)
        json.optJSONObject("avatarMeta")?.let { meta -> runCatching { rememberFileMeta(FileMeta.fromJson(meta)) } }
        val current = _profiles.value[peer.publicKey]
        if (current != null && current.updatedAt >= incoming.updatedAt) return
        synchronized(lock) {
            _profiles.value = _profiles.value + (peer.publicKey to incoming)
            save()
        }
        incoming.avatar?.let { id -> if (!hasFile(id)) requestFile(id) }
    }

    // ---- Files (attachments and avatars), sent directly between phones ----

    data class FileMeta(
        val id: String,
        val tag: String,
        val filename: String,
        val contentType: String,
        val size: Long,
        val width: Int?,
        val height: Int?,
        val sha256: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("tag", tag); put("filename", filename); put("contentType", contentType)
            put("size", size); width?.let { put("width", it) }; height?.let { put("height", it) }
            put("sha256", sha256)
        }

        companion object {
            fun fromJson(json: JSONObject) = FileMeta(
                id = json.getString("id"),
                tag = json.optString("tag", "attachments"),
                filename = json.optString("filename", "file").take(128),
                contentType = json.optString("contentType", "application/octet-stream"),
                size = json.optLong("size"),
                width = if (json.has("width")) json.optInt("width") else null,
                height = if (json.has("height")) json.optInt("height") else null,
                sha256 = json.getString("sha256"),
            )
        }
    }

    const val MAX_FILE_SIZE = 25L * 1024 * 1024
    private const val CHUNK_SIZE = 16 * 1024

    private val fileMetas = ConcurrentHashMap<String, FileMeta>()
    private val _filesVersion = MutableStateFlow(0)
    val filesVersion: StateFlow<Int> = _filesVersion.asStateFlow()

    private fun filesDir() = java.io.File(appContext.filesDir, "air_files").apply { mkdirs() }
    private fun fileOf(id: String) = java.io.File(filesDir(), id.filter { it.isLetterOrDigit() })

    fun fileMeta(id: String): FileMeta? = fileMetas[id]
    fun hasFile(id: String): Boolean = fileOf(id).exists()
    fun fileBytes(id: String): ByteArray? = fileOf(id).takeIf { it.exists() }?.readBytes()

    private fun rememberFileMeta(meta: FileMeta) {
        if (fileMetas.putIfAbsent(meta.id, meta) == null) synchronized(lock) { save() }
    }

    /** Stores a file chosen on this phone and returns its description. */
    fun storeFile(tag: String, filename: String, contentType: String, bytes: ByteArray): FileMeta {
        require(bytes.size <= MAX_FILE_SIZE) { "File too large" }
        val id = chat.stoat.api.internals.ULID.makeNext()
        var width: Int? = null
        var height: Int? = null
        if (contentType.startsWith("image/")) {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth > 0) {
                width = options.outWidth
                height = options.outHeight
            }
        }
        val meta = FileMeta(
            id, tag, filename.take(128), contentType, bytes.size.toLong(), width, height,
            AirCrypto.hex(AirCrypto.sha256(bytes))
        )
        fileOf(id).writeBytes(bytes)
        fileMetas[id] = meta
        synchronized(lock) { save() }
        return meta
    }

    /** Message attachments are carried in the message as "f:" tags. */
    fun attachmentsOf(item: AirItem): List<FileMeta> = item.tags.filter { it.startsWith("f:") }.mapNotNull {
        runCatching { FileMeta.fromJson(JSONObject(it.removePrefix("f:"))) }.getOrNull()
    }

    private val wantedFiles = ConcurrentHashMap<String, Long>()
    private val incomingParts = ConcurrentHashMap<String, Array<ByteArray?>>()

    /** Asks connected peers for a file we do not have. */
    fun requestFile(id: String) {
        val now = System.currentTimeMillis()
        if (now - (wantedFiles[id] ?: 0L) < 10_000) return
        wantedFiles[id] = now
        peers.values.filter { it.isOpen }.forEach { send(it, JSONObject().put("t", "fget").put("id", id)) }
    }

    /** Waits (up to [timeoutMs]) for a file to arrive from a peer. */
    suspend fun awaitFile(id: String, timeoutMs: Long = 20_000): ByteArray? {
        fileBytes(id)?.let { return it }
        requestFile(id)
        return withTimeoutOrNull(timeoutMs) {
            while (!hasFile(id)) {
                delay(300)
                requestFile(id)
            }
            fileBytes(id)
        }
    }

    private fun onFileRequest(peer: Peer, id: String) {
        val file = fileOf(id)
        if (!file.exists()) return
        val meta = fileMetas[id]
        scope.launch {
            val bytes = file.readBytes()
            val total = (bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
            for (index in 0 until maxOf(total, 1)) {
                val channel = peer.channel ?: return@launch
                while (channel.bufferedAmount() > 2L * 1024 * 1024) delay(50)
                val part = bytes.copyOfRange(index * CHUNK_SIZE, minOf(bytes.size, (index + 1) * CHUNK_SIZE))
                send(peer, JSONObject().apply {
                    put("t", "fpart"); put("id", id); put("i", index); put("n", maxOf(total, 1))
                    put("d", AirCrypto.b64(part))
                    if (index == 0 && meta != null) put("meta", meta.toJson())
                })
            }
        }
    }

    private fun onFilePart(json: JSONObject) {
        val id = json.getString("id")
        if (hasFile(id)) return
        json.optJSONObject("meta")?.let { runCatching { rememberFileMeta(FileMeta.fromJson(it)) } }
        val total = json.getInt("n")
        if (total <= 0 || total > MAX_FILE_SIZE / CHUNK_SIZE + 1) return
        val parts = incomingParts.getOrPut(id) { arrayOfNulls(total) }
        if (parts.size != total) return
        val index = json.getInt("i")
        if (index !in 0 until total) return
        parts[index] = AirCrypto.unb64(json.getString("d"))
        if (parts.any { it == null }) return
        incomingParts.remove(id)
        val bytes = java.io.ByteArrayOutputStream().apply { parts.forEach { write(it!!) } }.toByteArray()
        val expected = fileMetas[id]?.sha256
        // without a known hash we cannot check the file: ignore it
        if (expected == null || AirCrypto.hex(AirCrypto.sha256(bytes)) != expected) return
        fileOf(id).writeBytes(bytes)
        _filesVersion.value = _filesVersion.value + 1
    }

    // ---- Read state ----

    private val _acks = MutableStateFlow<Map<String, String>>(emptyMap())
    val acks: StateFlow<Map<String, String>> = _acks.asStateFlow()

    fun ack(conversationId: String, messageId: String) {
        if ((_acks.value[conversationId] ?: "") >= messageId) return
        synchronized(lock) {
            _acks.value = _acks.value + (conversationId to messageId)
            save()
        }
    }

    // ---- Local settings (synced settings of the interface, kept on this device) ----

    private val settings = ConcurrentHashMap<String, Pair<Long, String>>()

    fun getSetting(key: String): Pair<Long, String>? = settings[key]

    fun setSetting(key: String, timestamp: Long, value: String) {
        settings[key] = timestamp to value
        synchronized(lock) { save() }
    }

    fun markRead(conversationId: String) {
        val latest = _items.value.values.filter { it.conversation == conversationId }
            .maxOfOrNull { it.createdAt } ?: return
        if ((_readUpTo.value[conversationId] ?: 0) >= latest) return
        synchronized(lock) {
            _readUpTo.value = _readUpTo.value + (conversationId to latest)
            save()
        }
    }

    // ------------------------------------------------------------------------------------
    // Signalling over relays (end-to-end encrypted)
    // ------------------------------------------------------------------------------------

    private fun signalKey(other: String): ByteArray {
        val current = session ?: error("Not logged in")
        val shared = Secp256k1.sharedSecret(current.privateKey, Secp256k1.unhex(other))
        return AirCrypto.hkdf(shared, "AIR Chat".toByteArray(), "AIR Chat signalling v1")
    }

    /** Sends a call message to a peer, directly if connected, otherwise through signalling. */
    internal fun sendCallMessage(to: String, payload: JSONObject) {
        val peer = peers[to]
        if (peer?.isOpen == true) send(peer, JSONObject().put("t", "call").put("call", payload))
        else sendSignal(to, payload)
    }

    fun isConnected(publicKey: String) = peers[publicKey]?.isOpen == true

    internal fun sendSignal(to: String, payload: JSONObject) {
        val current = session ?: return
        scope.launch {
            try {
                payload.put("from_name", current.username)
                val sealed = AirCrypto.seal(signalKey(to), payload.toString().toByteArray(), "S|$to")
                val event = NostrEvent.create(
                    current.privateKey,
                    SIGNAL_KIND,
                    listOf(listOf("p", to)),
                    AirCrypto.b64(sealed)
                )
                NostrRelayPool.send(event)
            } catch (e: Exception) {
                Log.w(TAG, "Could not send signal", e)
            }
        }
    }

    private val lastHelloSent = ConcurrentHashMap<String, Long>()

    private fun sendHello(to: String) {
        val now = System.currentTimeMillis()
        if (now - (lastHelloSent[to] ?: 0L) < 20_000) return
        lastHelloSent[to] = now
        sendSignal(to, JSONObject().put("t", "hello"))
    }

    private fun helloEveryone() {
        val targets = _contacts.value.keys + _conversations.value.values.flatMap { it.members }
        targets.filter { it != selfKey && peers[it]?.isOpen != true }.toSet().forEach { sendHello(it) }
    }

    private val handledSignals = ConcurrentHashMap.newKeySet<String>()

    private fun onSignalEvent(event: NostrEvent) {
        val current = session ?: return
        if (!handledSignals.add(event.id)) return
        if (event.firstTag("p") != current.publicKey) return
        if (System.currentTimeMillis() / 1000 - event.createdAt > 120) return
        val from = event.pubkey
        if (from == current.publicKey) return

        val json = try {
            JSONObject(
                String(AirCrypto.open(signalKey(from), AirCrypto.unb64(event.content), "S|${current.publicKey}"))
            )
        } catch (e: Exception) {
            return
        }

        scope.launch {
            try {
                rememberSender(from, json.optString("from_name"))
                when (json.optString("t")) {
                    "hello" -> onHello(from)
                    "offer" -> onOffer(from, json.getString("sid"), json.getString("sdp"))
                    "answer" -> onAnswer(from, json.getString("sid"), json.getString("sdp"))
                    "invite" -> json.optJSONObject("conv")?.let { mergeConversation(from, it) }
                    "call-ring", "call-join", "call-roster", "call-offer", "call-answer",
                    "call-leave", "call-busy" -> AirCall.onSignal(from, json)
                    "join" -> onJoinRequest(from, json.optString("server"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Signal handling failed", e)
            }
        }
    }

    /** Someone we do not know yet wrote to us: check their username before showing it. */
    private suspend fun rememberSender(from: String, claimedName: String) {
        if (_contacts.value.containsKey(from) || claimedName.isEmpty()) return
        val owner = runCatching { AirAccount.ownerOf(claimedName) }.getOrNull()
        if (owner != from) return
        synchronized(lock) {
            _contacts.value = _contacts.value + (from to AirContact(from, AirAccount.normalize(claimedName)))
            save()
        }
    }

    private suspend fun onHello(from: String) {
        val existing = peers[from]
        if (existing != null && (existing.isOpen || System.currentTimeMillis() - existing.createdAt < 20_000)) return
        if ((selfKey ?: return) < from) connect(from) else sendHello(from)
    }

    // ------------------------------------------------------------------------------------
    // Direct connections (WebRTC data channels)
    // ------------------------------------------------------------------------------------

    private class Peer(val publicKey: String, val sessionId: String, val connection: PeerConnection) {
        val createdAt = System.currentTimeMillis()
        @Volatile
        var channel: DataChannel? = null
        val gathered = CompletableDeferred<Unit>()
        val syncedConversations: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val isOpen: Boolean get() = channel?.state() == DataChannel.State.OPEN
    }

    private val peers = ConcurrentHashMap<String, Peer>()
    private var factory: PeerConnectionFactory? = null

    private val iceServers by lazy {
        listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478",
        ).map { PeerConnection.IceServer.builder(it).createIceServer() }
    }

    @Synchronized
    internal fun peerConnectionFactory(): PeerConnectionFactory {
        factory?.let { return it }
        LiveKit.init(appContext)
        return PeerConnectionFactory.builder().createPeerConnectionFactory().also { factory = it }
    }

    internal fun rtcConfiguration() = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }

    private suspend fun connect(to: String) {
        closePeer(to)
        val sessionId = AirCrypto.hex(AirCrypto.randomBytes(8))
        val peer = newPeer(to, sessionId) ?: return
        attach(peer, peer.connection.createDataChannel("air-chat", DataChannel.Init().apply { ordered = true }))
        val offer = peer.connection.createDescription(true)
        peer.connection.applyDescription(offer, local = true)
        withTimeoutOrNull(6_000) { peer.gathered.await() }
        sendSignal(
            to,
            JSONObject().put("t", "offer").put("sid", sessionId)
                .put("sdp", peer.connection.localDescription?.description ?: offer.description)
        )
    }

    private suspend fun onOffer(from: String, sessionId: String, sdp: String) {
        closePeer(from)
        val peer = newPeer(from, sessionId) ?: return
        peer.connection.applyDescription(SessionDescription(SessionDescription.Type.OFFER, sdp), local = false)
        val answer = peer.connection.createDescription(false)
        peer.connection.applyDescription(answer, local = true)
        withTimeoutOrNull(6_000) { peer.gathered.await() }
        sendSignal(
            from,
            JSONObject().put("t", "answer").put("sid", sessionId)
                .put("sdp", peer.connection.localDescription?.description ?: answer.description)
        )
    }

    private suspend fun onAnswer(from: String, sessionId: String, sdp: String) {
        val peer = peers[from] ?: return
        if (peer.sessionId != sessionId) return
        peer.connection.applyDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp), local = false)
    }

    private fun newPeer(publicKey: String, sessionId: String): Peer? {
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
                    created?.let { peer -> scope.launch { closePeer(peer.publicKey, peer) } }
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
        val connection = peerConnectionFactory().createPeerConnection(rtcConfiguration(), observer) ?: return null
        val peer = Peer(publicKey, sessionId, connection)
        created = peer
        peers[publicKey] = peer
        return peer
    }

    private fun attach(peer: Peer, channel: DataChannel) {
        peer.channel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                when (channel.state()) {
                    DataChannel.State.OPEN -> onPeerOpen(peer)
                    DataChannel.State.CLOSED -> scope.launch { closePeer(peer.publicKey, peer) }
                    else -> Unit
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                runCatching { onData(peer, JSONObject(String(bytes, Charsets.UTF_8))) }
                    .onFailure { Log.w(TAG, "Bad data from peer", it) }
            }
        })
        if (channel.state() == DataChannel.State.OPEN) onPeerOpen(peer)
    }

    private fun onPeerOpen(peer: Peer) {
        _online.value = _online.value + peer.publicKey
        // the official AIR Chat server is open to everyone
        AirOfficial.openConversationIds().forEach { sendSync(peer, it) }
        sendProfile(peer)
        // files we are still waiting for
        fileMetas.keys.filter { !hasFile(it) && wantedFiles.containsKey(it) }
            .forEach { send(peer, JSONObject().put("t", "fget").put("id", it)) }
        _conversations.value.values
            .filter { peer.publicKey in it.members }
            .forEach { conversation ->
                if (conversation.isGroup) sendConversation(peer, conversation)
                sendSync(peer, conversation.id)
            }
    }

    private fun sendSync(peer: Peer, conversationId: String) {
        if (!peer.syncedConversations.add(conversationId)) return
        send(peer, JSONObject().put("t", "sync").put("conv", conversationId)
            .put("ids", JSONArray(itemIdsOf(conversationId))))
    }

    private fun onData(peer: Peer, json: JSONObject) {
        when (json.optString("t")) {
            "profile" -> json.optJSONObject("profile")?.let { onProfile(peer, it) }
            "fget" -> onFileRequest(peer, json.optString("id"))
            "fpart" -> onFilePart(json)
            "call" -> AirCall.onSignal(peer.publicKey, json.getJSONObject("call"))
            "conv" -> json.optJSONObject("conv")?.let { raw ->
                mergeConversation(peer.publicKey, raw)
                conversationFromJson(raw)?.let { c ->
                    if (conversationFor(peer.publicKey, c.id) != null) sendSync(peer, c.id)
                }
            }

            "sync" -> {
                val conversationId = json.getString("conv")
                val conversation = conversationFor(peer.publicKey, conversationId)
                    ?: (if (conversationId == directConversationId(peer.publicKey)) openDirect(peer.publicKey) else null)
                    ?: return
                sendSync(peer, conversation.id)
                val theirs = json.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    ?: emptySet()
                val missing = _items.value.values.filter { it.conversation == conversation.id && it.id !in theirs }
                sendItems(peer, missing)
            }

            "items" -> {
                val array = json.optJSONArray("items") ?: return
                val incoming = (0 until array.length()).mapNotNull {
                    runCatching { AirItem.fromJson(array.getJSONObject(it)) }.getOrNull()
                }
                val accepted = incoming.filter { item ->
                    val conversation = conversationFor(peer.publicKey, item.conversation)
                        ?: (if (item.conversation == directConversationId(peer.publicKey)) openDirect(peer.publicKey) else null)
                    conversation != null && item.isValid() &&
                            (item.author in conversation.members || AirOfficial.isOpen(conversation.id)) &&
                            !AirServers.isBlocked(conversation.id, item.author)
                }
                val added = addItems(accepted)
                AirOfficial.onItems(added)
                added.groupBy { it.conversation }.forEach { (conversationId, list) ->
                    broadcastItems(conversationId, list, except = peer.publicKey)
                }
            }
        }
    }

    /** The conversation, if both this device and [peer] are members of it. */
    private fun conversationFor(peer: String, conversationId: String): AirConversation? {
        val conversation = _conversations.value[conversationId] ?: return null
        if (AirOfficial.isOpen(conversationId)) return conversation
        return conversation.takeIf { peer in it.members && selfKey in it.members }
    }

    private fun mergeConversation(from: String, json: JSONObject) {
        val incoming = conversationFromJson(json) ?: return
        if (from !in incoming.members || selfKey !in incoming.members) return
        synchronized(lock) {
            val existing = _conversations.value[incoming.id]
            if (existing != null && from !in existing.members) return
            val merged = existing?.copy(
                members = existing.members + incoming.members,
                name = if (existing.owner != null && from == existing.owner) incoming.name else existing.name,
                secret = existing.secret ?: incoming.secret,
                link = existing.link ?: incoming.link
            ) ?: incoming
            _conversations.value = _conversations.value + (merged.id to merged)
            save()
        }
        incoming.members.filter { it != selfKey && peers[it]?.isOpen != true }.forEach { sendHello(it) }
        if (incoming.kind == AirConversation.KIND_SERVER) {
            refreshInviteSubscriptions()
            AirServers.ensureJoinItem(incoming.id)
        }
    }

    private fun sendConversation(peer: Peer, conversation: AirConversation) {
        send(peer, JSONObject().put("t", "conv").put("conv", conversationJson(conversation)))
    }

    private fun broadcastItems(conversationId: String, list: List<AirItem>, except: String?) {
        val conversation = _conversations.value[conversationId] ?: return
        val targets = if (AirOfficial.isOpen(conversationId)) peers.keys else conversation.members
        targets
            .filter { it != selfKey && it != except && !AirServers.isBlocked(conversationId, it) }
            .mapNotNull { peers[it] }
            .filter { it.isOpen }
            .forEach { sendItems(it, list) }
    }

    private fun sendItems(peer: Peer, list: List<AirItem>) {
        list.chunked(20).forEach { batch ->
            send(peer, JSONObject().put("t", "items").put("items", JSONArray().apply { batch.forEach { put(it.toJson()) } }))
        }
    }

    private fun send(peer: Peer, json: JSONObject) {
        val channel = peer.channel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(json.toString().toByteArray(Charsets.UTF_8)), false))
    }

    private fun closePeer(publicKey: String, only: Peer? = null) {
        val peer = peers[publicKey] ?: return
        if (only != null && peer !== only) return
        peers.remove(publicKey)
        runCatching { peer.channel?.unregisterObserver() }
        runCatching { peer.channel?.close() }
        runCatching { peer.connection.close() }
        _online.value = _online.value - publicKey
    }

    // ------------------------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------------------------

    private fun itemIdsOf(conversationId: String): List<String> =
        _items.value.values.filter { it.conversation == conversationId }.map { it.id }

    private fun addItems(list: List<AirItem>): List<AirItem> {
        list.forEach { item ->
            attachmentsOf(item).forEach { meta ->
                rememberFileMeta(meta)
                if (item.author != selfKey && !hasFile(meta.id) && meta.size <= 10L * 1024 * 1024) {
                    scope.launch { delay(500); requestFile(meta.id) }
                }
            }
        }
        synchronized(lock) {
            val current = _items.value
            val added = list.filter { it.id !in current }
            if (added.isEmpty()) return emptyList()
            _items.value = current + added.associateBy { it.id }
            // a message in a direct conversation also makes its author a contact
            save()
            return added
        }
    }

    private fun conversationJson(c: AirConversation) = JSONObject().apply {
        put("id", c.id); put("group", c.isGroup); put("name", c.name)
        put("members", JSONArray(c.members.toList())); put("createdAt", c.createdAt)
        put("kind", c.kind); put("parent", c.parent ?: ""); put("owner", c.owner ?: "")
        put("secret", c.secret ?: ""); put("link", c.link ?: "")
    }

    private fun conversationFromJson(json: JSONObject): AirConversation? = runCatching {
        AirConversation(
            id = json.getString("id"),
            isGroup = json.getBoolean("group"),
            name = json.optString("name").take(64),
            members = json.getJSONArray("members").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
            createdAt = json.optLong("createdAt"),
            kind = json.optString("kind").ifEmpty { if (json.getBoolean("group")) AirConversation.KIND_GROUP else AirConversation.KIND_DM },
            parent = json.optString("parent").ifEmpty { null },
            owner = json.optString("owner").ifEmpty { null },
            secret = json.optString("secret").ifEmpty { null },
            link = json.optString("link").ifEmpty { null },
        )
    }.getOrNull()

    private fun load() {
        val raw = vault?.read() ?: return
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        json.optJSONArray("contacts")?.let { a ->
            _contacts.value = (0 until a.length()).map { a.getJSONObject(it) }
                .associate { it.getString("key") to AirContact(it.getString("key"), it.getString("username")) }
        }
        json.optJSONArray("conversations")?.let { a ->
            _conversations.value = (0 until a.length()).mapNotNull { conversationFromJson(a.getJSONObject(it)) }
                .associateBy { it.id }
        }
        json.optJSONArray("items")?.let { a ->
            _items.value = (0 until a.length()).mapNotNull { runCatching { AirItem.fromJson(a.getJSONObject(it)) }.getOrNull() }
                .associateBy { it.id }
        }
        json.optJSONObject("read")?.let { o ->
            _readUpTo.value = o.keys().asSequence().associateWith { o.getLong(it) }
        }
        json.optJSONObject("profiles")?.let { o ->
            _profiles.value = o.keys().asSequence().associateWith { profileFromJson(o.getJSONObject(it)) }
        }
        json.optJSONArray("files")?.let { a ->
            for (i in 0 until a.length()) runCatching { FileMeta.fromJson(a.getJSONObject(i)) }.getOrNull()?.let { fileMetas[it.id] = it }
        }
        json.optJSONObject("names")?.let { o ->
            _names.value = o.keys().asSequence().associateWith { o.getString(it) }
        }
        json.optJSONObject("acks")?.let { o ->
            _acks.value = o.keys().asSequence().associateWith { o.getString(it) }
        }
        json.optJSONObject("settings")?.let { o ->
            o.keys().forEach { k ->
                val a = o.getJSONArray(k)
                settings[k] = a.getLong(0) to a.getString(1)
            }
        }
    }

    private fun save() {
        val target = vault ?: return
        val json = JSONObject()
        json.put("contacts", JSONArray().apply {
            _contacts.value.values.forEach { put(JSONObject().put("key", it.publicKey).put("username", it.username)) }
        })
        json.put("conversations", JSONArray().apply { _conversations.value.values.forEach { put(conversationJson(it)) } })
        json.put("items", JSONArray().apply { _items.value.values.sortedBy { it.createdAt }.forEach { put(it.toJson()) } })
        json.put("read", JSONObject(_readUpTo.value.mapValues { it.value }))
        json.put("acks", JSONObject(_acks.value.mapValues { it.value }))
        json.put("names", JSONObject(_names.value.mapValues { it.value }))
        json.put("profiles", JSONObject().apply { _profiles.value.forEach { (k, v) -> put(k, profileJson(v).apply { remove("avatarMeta") }) } })
        json.put("files", JSONArray().apply { fileMetas.values.forEach { put(it.toJson()) } })
        json.put("settings", JSONObject().apply {
            settings.forEach { (k, v) -> put(k, JSONArray().put(v.first).put(v.second)) }
        })
        runCatching { target.write(json.toString()) }.onFailure { Log.e(TAG, "Could not save", it) }
    }

    // ---- coroutine helpers for the callback-based WebRTC API ----

    internal suspend fun PeerConnection.createDescription(offer: Boolean): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (continuation.isActive) continuation.resume(description)
                }

                override fun onCreateFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error ?: "createDescription failed"))
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }
            if (offer) createOffer(observer, MediaConstraints()) else createAnswer(observer, MediaConstraints())
        }

    internal suspend fun PeerConnection.applyDescription(description: SessionDescription, local: Boolean) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {}
                override fun onCreateFailure(error: String?) {}
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error ?: "setDescription failed"))
                }
            }
            if (local) setLocalDescription(observer, description) else setRemoteDescription(observer, description)
        }
    }
}

/** Bridge used by [AirCall] to reach the encrypted signalling of [AirNetwork]. */
internal object AirNetworkSignals {
    fun newSessionId(): String = AirCrypto.hex(AirCrypto.randomBytes(8))
    fun send(to: String, payload: JSONObject) = AirNetwork.sendSignal(to, payload)
}
