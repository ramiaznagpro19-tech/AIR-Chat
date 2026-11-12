package chat.stoat.air

import android.util.Log
import chat.stoat.api.internals.LocalBackend
import chat.stoat.e2ee.AirCrypto
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.airnet_err_network
import chat.stoat.shared.resources.airnet_err_not_found
import chat.stoat.shared.resources.airnet_err_password_invalid
import chat.stoat.shared.resources.airnet_err_taken
import chat.stoat.shared.resources.airnet_err_username_invalid
import chat.stoat.shared.resources.airnet_err_wrong_password
import chat.stoat.shared.resources.airnet_join_owner_offline
import chat.stoat.shared.resources.airnet_uploads_unavailable
import chat.stoat.shared.resources.airnet_user_not_found
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * AIR Chat: the "server" built into the app.
 *
 * The original interface talks to its server through a REST API and a realtime connection. This
 * object answers both directly on the phone, using AIR Chat accounts and the peer-to-peer
 * network ([AirNetwork]) as storage, so the whole interface keeps working without any server.
 */
object AirLocalServer {
    private const val TAG = "AirLocalServer"
    const val TOKEN_PREFIX = "air-local."
    private const val SESSION_ID = "air-session"

    /** Every permission: on AIR Chat, members of a conversation can do everything in it. */
    private const val ALL_PERMISSIONS = (1L shl 40) - 1

    /** All derived ids share this timestamp, so every device computes the same ids. */
    private const val ID_EPOCH = 1735689600000L

    private val JSON = "application/json".toMediaType()

    fun install() {
        LocalBackend.realtime = { token, emit -> runRealtime(token, emit) }
    }

    /** Called when the person logs out from the settings. */
    fun onLogout() {
        AirNetwork.stop()
        AirAccount.logout()
    }

    private fun ensureStarted(): AirAccount.Session? {
        val session = AirAccount.session.value ?: return null
        AirNetwork.start(session)
        return session
    }

    // ------------------------------------------------------------------------------------
    // Ids
    // ------------------------------------------------------------------------------------

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val idToKey = ConcurrentHashMap<String, String>()

    private fun deriveId(prefix: String, value: String): String {
        val sb = StringBuilder()
        var time = ID_EPOCH
        val timePart = CharArray(10)
        for (i in 9 downTo 0) {
            timePart[i] = ALPHABET[(time and 31).toInt()]
            time = time shr 5
        }
        sb.append(timePart)
        val hash = AirCrypto.sha256("$prefix:$value".toByteArray())
        var bits = 0
        var acc = 0
        var index = 0
        while (sb.length < 26) {
            if (bits < 5) {
                acc = (acc shl 8) or (hash[index++].toInt() and 0xff)
                bits += 8
            }
            bits -= 5
            sb.append(ALPHABET[(acc shr bits) and 31])
        }
        val id = sb.toString()
        idToKey[id] = "$prefix:$value"
        return id
    }

    fun userId(publicKey: String) = deriveId("u", publicKey)
    private fun channelId(conversationId: String) = deriveId("c", conversationId)
    private fun serverId(conversationId: String) = deriveId("s", conversationId)

    /** Public key of a user id, if known. */
    fun publicKeyOf(userId: String): String? =
        idToKey[userId]?.takeIf { it.startsWith("u:") }?.removePrefix("u:") ?: run {
            knownKeys().forEach { userId(it) }
            idToKey[userId]?.takeIf { it.startsWith("u:") }?.removePrefix("u:")
        }

    private fun conversationOfChannel(id: String): AirConversation? {
        val key = idToKey[id] ?: run {
            AirNetwork.conversations.value.keys.forEach { channelId(it) }
            idToKey[id]
        }
        return key?.takeIf { it.startsWith("c:") }?.removePrefix("c:")
            ?.let { AirNetwork.conversations.value[it] }
            ?.takeIf { it.kind != AirConversation.KIND_SERVER }
    }

    private fun conversationOfServer(id: String): AirConversation? {
        val key = idToKey[id] ?: run {
            AirNetwork.conversations.value.keys.forEach { serverId(it) }
            idToKey[id]
        }
        return key?.takeIf { it.startsWith("s:") }?.removePrefix("s:")
            ?.let { AirNetwork.conversations.value[it] }
            ?.takeIf { it.kind == AirConversation.KIND_SERVER }
    }

    /** AIR Chat conversation behind a channel id of the interface. */
    fun conversationIdOfChannel(channelId: String): String? = conversationOfChannel(channelId)?.id

    /** DM channel id for a conversation partner (used by calls). */
    fun peerOfChannel(channelId: String): String? {
        val conversation = conversationOfChannel(channelId) ?: return null
        if (conversation.kind != AirConversation.KIND_DM) return null
        return conversation.members.firstOrNull { it != AirNetwork.selfKey }
    }

    private fun knownKeys(): Set<String> {
        val keys = mutableSetOf<String>()
        AirNetwork.selfKey?.let { keys.add(it) }
        keys.addAll(AirNetwork.contacts.value.keys)
        AirNetwork.conversations.value.values.forEach { keys.addAll(it.members) }
        return keys
    }

    // ------------------------------------------------------------------------------------
    // JSON views in the format of the original API
    // ------------------------------------------------------------------------------------

    private fun displayName(publicKey: String): String? = AirNetwork.profiles.value[publicKey]?.displayName

    private fun userJson(publicKey: String): JSONObject {
        val self = publicKey == AirNetwork.selfKey
        val online = self || publicKey in AirNetwork.online.value
        return JSONObject().apply {
            put("_id", userId(publicKey))
            put("username", AirNetwork.username(publicKey))
            put("discriminator", "0001")
            displayName(publicKey)?.let { put("display_name", it) }
            AirNetwork.profiles.value[publicKey]?.avatar
                ?.let { AirNetwork.fileMeta(it) }
                ?.let { put("avatar", autumnJson(it)) }
            put("relationship", when {
                self -> "User"
                AirNetwork.contacts.value.containsKey(publicKey) -> "Friend"
                else -> "None"
            })
            put("online", online)
            put("status", JSONObject().put("presence", if (online) "Online" else "Invisible"))
            put("badges", 0)
            put("flags", 0)
        }
    }

    private fun lastMessageId(conversationId: String): String? =
        AirNetwork.items.value.values
            .filter { it.conversation == conversationId && it.type == AirItem.MESSAGE && it.id.length == 26 }
            .maxOfOrNull { it.id }

    private fun channelJson(c: AirConversation): JSONObject = JSONObject().apply {
        put("_id", channelId(c.id))
        lastMessageId(c.id)?.let { put("last_message_id", it) }
        when (c.kind) {
            AirConversation.KIND_DM -> {
                put("channel_type", "DirectMessage")
                put("active", true)
                put("recipients", JSONArray(c.members.map { userId(it) }))
            }

            AirConversation.KIND_CHANNEL -> {
                val server = c.parent?.let { AirNetwork.conversations.value[it] }
                val meta = server?.let { AirServers.state(it).channels[c.id] }
                put("channel_type", "TextChannel")
                put("server", serverId(c.parent ?: ""))
                // every server channel can also hold a voice/video call
                put("voice", JSONObject().put("max_users", 8))
                put("name", meta?.name ?: c.name)
                meta?.description?.let { put("description", it) }
                meta?.defaultOverride?.let { (a, d) -> put("default_permissions", JSONObject().put("a", a).put("d", d)) }
                if (!meta?.roleOverrides.isNullOrEmpty()) {
                    put("role_permissions", JSONObject().apply {
                        meta!!.roleOverrides.forEach { (role, ad) -> put(role, JSONObject().put("a", ad.first).put("d", ad.second)) }
                    })
                }
            }

            else -> {
                put("channel_type", "Group")
                put("name", c.name)
                put("owner", userId(c.owner ?: c.members.minOrNull() ?: ""))
                put("recipients", JSONArray(c.members.map { userId(it) }))
                put("permissions", ALL_PERMISSIONS)
            }
        }
    }

    /** Channels of a server that were not deleted. */
    private fun visibleChannels(server: AirConversation): List<AirConversation> {
        val state = AirServers.state(server)
        return AirNetwork.channelsOf(server.id).filter { state.channels[it.id]?.deleted != true }
    }

    private fun serverJson(c: AirConversation, withChannelObjects: Boolean = false): JSONObject = JSONObject().apply {
        val state = AirServers.state(c)
        put("_id", serverId(c.id))
        put("owner", userId(AirServers.ownerOf(c) ?: ""))
        put("name", state.name)
        state.description?.let { put("description", it) }
        state.icon?.let { put("icon", autumnJson(it)) }
        state.banner?.let { put("banner", autumnJson(it)) }
        put("channels", JSONArray().apply {
            visibleChannels(c).forEach { put(if (withChannelObjects) channelJson(it) else channelId(it.id)) }
        })
        put("default_permissions", state.defaultPermissions)
        put("roles", JSONObject().apply {
            state.roles.values.forEach { role ->
                put(role.id, JSONObject().apply {
                    put("name", role.name)
                    put("permissions", JSONObject().put("a", role.allow).put("d", role.deny))
                    role.colour?.let { put("colour", it) }
                    put("hoist", role.hoist)
                    put("rank", role.rank)
                })
            }
        })
        if (AirOfficial.isOpen(c.id)) put("flags", 1)
    }

    private fun roleJson(role: AirServers.Role) = JSONObject().apply {
        put("name", role.name)
        put("permissions", JSONObject().put("a", role.allow).put("d", role.deny))
        role.colour?.let { put("colour", it) }
        put("hoist", role.hoist)
        put("rank", role.rank)
    }

    private fun memberJson(server: AirConversation, publicKey: String) = JSONObject().apply {
        val state = AirServers.state(server)
        put("_id", JSONObject().put("server", serverId(server.id)).put("user", userId(publicKey)))
        put("joined_at", Instant.ofEpochMilli(state.joinedAt[publicKey] ?: server.createdAt).toString())
        put("roles", JSONArray(state.memberRoles[publicKey].orEmpty()))
        state.nicknames[publicKey]?.let { put("nickname", it) }
        state.timeouts[publicKey]?.takeIf { it > System.currentTimeMillis() }
            ?.let { put("timeout", Instant.ofEpochMilli(it).toString()) }
    }

    private fun serverMembers(server: AirConversation): List<String> {
        val state = AirServers.state(server)
        val keys = if (AirOfficial.isOpen(server.id)) server.members + state.joinedAt.keys else server.members
        return keys.filter { it !in state.removed }
    }

    private data class MessageView(
        val id: String,
        val conversation: AirConversation,
        val author: String,
        val content: String,
        val edited: Long?,
        val reactions: Map<String, List<String>>,
        val replies: List<String>,
        val attachments: List<AirNetwork.FileMeta>,
        val system: JSONObject? = null,
    )

    private fun messageViews(conversation: AirConversation): List<MessageView> {
        val items = AirNetwork.items.value.values.filter { it.conversation == conversation.id }
        val server = AirServers.serverOf(conversation.id)
        val state = server?.let { AirServers.state(it) }
        val owner = conversation.owner ?: server?.let { AirServers.ownerOf(it) }
        // moderators (Manage Messages) can delete anyone's message
        val moderators: (String) -> Boolean = { key ->
            key == owner || (server != null && AirServers.permissions(server, key) and AirServers.MANAGE_MESSAGES != 0L)
        }
        val base = items.filter {
            it.type == AirItem.MESSAGE && it.id.length == 26 && (state == null || it.author !in state.bans)
        }.associateBy { it.id }
        val edits = items.filter { it.type == AirItem.EDIT }.sortedBy { it.createdAt }
        val deleted = items.filter { it.type == AirItem.DELETE }
            .filter { del -> base[del.parent]?.let { it.author == del.author || moderators(del.author) } == true }
            .mapNotNull { it.parent }.toSet()
        val reactions = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
        items.filter { it.type == AirItem.REACT || it.type == AirItem.UNREACT }
            .sortedBy { it.createdAt }
            .forEach { r ->
                val parent = r.parent ?: return@forEach
                val emoji = r.text.take(64)
                val users = reactions.getOrPut(parent) { mutableMapOf() }.getOrPut(emoji) { mutableSetOf() }
                if (r.type == AirItem.REACT) users.add(r.author) else users.remove(r.author)
            }
        // "X joined the server", shown in the first channel of the server
        val joins = if (server != null && visibleChannels(server).firstOrNull()?.id == conversation.id) {
            AirNetwork.items.value.values.filter { it.conversation == server.id && it.type == AirItem.JOIN && it.id.length == 26 }
                .map { j ->
                    MessageView(
                        id = j.id, conversation = conversation, author = j.author, content = "",
                        edited = null, reactions = emptyMap(), replies = emptyList(), attachments = emptyList(),
                        system = JSONObject().put("type", "user_joined").put("id", userId(j.author)),
                    )
                }
        } else emptyList()
        return joins + base.values.filter { it.id !in deleted }.map { m ->
            val lastEdit = edits.lastOrNull { it.parent == m.id && it.author == m.author }
            MessageView(
                id = m.id,
                conversation = conversation,
                author = m.author,
                content = lastEdit?.text ?: m.text,
                edited = lastEdit?.createdAt,
                reactions = reactions[m.id].orEmpty()
                    .filterValues { it.isNotEmpty() }
                    .mapValues { (_, users) -> users.map { userId(it) } },
                replies = m.tags.filter { it.length == 26 },
                attachments = AirNetwork.attachmentsOf(m),
            )
        }
    }

    private fun messageJson(m: MessageView) = JSONObject().apply {
        put("_id", m.id)
        put("channel", channelId(m.conversation.id))
        put("author", userId(m.author))
        put("content", m.content)
        m.system?.let { put("system", it); put("author", "00000000000000000000000000") }
        m.edited?.let { put("edited", Instant.ofEpochMilli(it).toString()) }
        if (m.reactions.isNotEmpty()) {
            put("reactions", JSONObject().apply { m.reactions.forEach { (e, u) -> put(e, JSONArray(u)) } })
        }
        if (m.replies.isNotEmpty()) put("replies", JSONArray(m.replies))
        if (m.attachments.isNotEmpty()) put("attachments", JSONArray().apply { m.attachments.forEach { put(autumnJson(it)) } })
    }

    // ------------------------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------------------------

    private class Reply(val code: Int, val body: String, val bytes: ByteArray? = null, val contentType: String? = null)

    private fun ok(json: Any = JSONObject()) = Reply(200, json.toString())
    private fun error(code: Int, type: String) = Reply(code, JSONObject().put("type", type).toString())
    private suspend fun error(code: Int, message: StringResource) = error(code, getString(message))

    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        if (!(host.endsWith("stoat.chat") || host.endsWith("revolt.chat") || host.endsWith("stoatusercontent.com"))) {
            return@Interceptor chain.proceed(request)
        }
        val raw = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readByteArray() } ?: ByteArray(0)
        val body = String(raw, Charsets.UTF_8)
        val reply = try {
            runBlocking(Dispatchers.IO) {
                if (host.endsWith("stoatusercontent.com")) fileRoute(request, raw) else route(request, body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Local request failed: ${request.method} ${request.url}", e)
            error(400, e.message ?: "InternalError")
        }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(reply.code)
            .message(if (reply.code < 400) "OK" else "Error")
            // the API layer reads the type from the headers to decode the response
            .header("Content-Type", if (reply.bytes != null) reply.contentType ?: "application/octet-stream" else "application/json")
            .body(
                reply.bytes?.toResponseBody((reply.contentType ?: "application/octet-stream").toMediaType())
                    ?: reply.body.toResponseBody(JSON)
            )
            .build()
    }

    // ---- Files (uploads and downloads) ----

    private suspend fun fileRoute(request: Request, raw: ByteArray): Reply {
        ensureStarted() ?: return error(401, "Unauthorized")
        val segments = request.url.pathSegments.filter { it.isNotEmpty() }
        if (request.method == "POST" && segments.size == 1) {
            val contentType = request.body?.contentType()?.toString() ?: request.header("Content-Type") ?: ""
            val boundary = Regex("boundary=\"?([^\";]+)\"?").find(contentType)?.groupValues?.get(1)
                ?: return error(400, "BadRequest")
            val part = parseMultipart(raw, boundary) ?: return error(400, "BadRequest")
            if (part.bytes.size > AirNetwork.MAX_FILE_SIZE) return error(413, "FileTooLarge")
            val meta = AirNetwork.storeFile(segments[0], part.filename, part.contentType, part.bytes)
            return ok(JSONObject().put("id", meta.id))
        }
        if (request.method == "GET" && segments.size >= 2) {
            val id = segments[1]
            if (!AirNetwork.hasFile(id) && AirNetwork.fileMeta(id) == null) return error(404, "NotFound")
            val bytes = AirNetwork.awaitFile(id) ?: return error(404, "NotFound")
            val type = AirNetwork.fileMeta(id)?.contentType ?: "application/octet-stream"
            return Reply(200, "", bytes, type)
        }
        return error(404, "NotFound")
    }

    private class Part(val filename: String, val contentType: String, val bytes: ByteArray)

    private fun parseMultipart(raw: ByteArray, boundary: String): Part? {
        val delimiter = "--$boundary".toByteArray()
        fun indexOf(pattern: ByteArray, from: Int): Int {
            outer@ for (i in from..raw.size - pattern.size) {
                for (j in pattern.indices) if (raw[i + j] != pattern[j]) continue@outer
                return i
            }
            return -1
        }
        val start = indexOf(delimiter, 0)
        if (start < 0) return null
        val headerEnd = indexOf("\r\n\r\n".toByteArray(), start)
        if (headerEnd < 0) return null
        val headers = String(raw, start, headerEnd - start, Charsets.UTF_8)
        val dataStart = headerEnd + 4
        val end = indexOf("\r\n--$boundary".toByteArray(), dataStart)
        if (end < 0) return null
        val filename = Regex("filename=\"([^\"]*)\"").find(headers)?.groupValues?.get(1) ?: "file"
        val contentType = Regex("(?i)content-type:\\s*([^\\r\\n]+)").find(headers)?.groupValues?.get(1)?.trim()
            ?: "application/octet-stream"
        return Part(filename, contentType, raw.copyOfRange(dataStart, end))
    }

    private fun autumnJson(meta: AirNetwork.FileMeta) = JSONObject().apply {
        put("_id", meta.id)
        put("tag", meta.tag)
        put("filename", meta.filename)
        put("content_type", meta.contentType)
        put("size", meta.size)
        put("metadata", JSONObject().apply {
            when {
                meta.contentType.startsWith("image/") -> {
                    put("type", "Image"); meta.width?.let { put("width", it) }; meta.height?.let { put("height", it) }
                }

                meta.contentType.startsWith("video/") -> put("type", "Video")
                meta.contentType.startsWith("audio/") -> put("type", "Audio")
                meta.contentType.startsWith("text/") -> put("type", "Text")
                else -> put("type", "File")
            }
        })
    }

    private fun jsonBody(body: String): JSONObject = runCatching { JSONObject(body) }.getOrDefault(JSONObject())

    private suspend fun route(request: Request, body: String): Reply {
        val host = request.url.host
        if (host.startsWith("health.")) return ok(JSONObject())
        if (host.startsWith("geo.")) return ok(JSONObject().put("countryCode", "FR"))
        if (!host.startsWith("api.")) return error(404, Res.string.airnet_uploads_unavailable)

        val segments = request.url.pathSegments.filter { it.isNotEmpty() }.let {
            if (it.firstOrNull()?.firstOrNull()?.isDigit() == true) it.drop(1) else it
        }
        val method = request.method
        val path = "/" + segments.joinToString("/")

        // ---- Without an account ----
        when {
            method == "GET" && path == "/" -> return ok(rootJson())
            method == "POST" && path == "/auth/session/login" -> return login(jsonBody(body))
            method == "POST" && path == "/auth/account/create" -> return register(jsonBody(body))
        }

        val session = ensureStarted() ?: return error(401, "Unauthorized")
        val self = session.publicKey
        val s = segments
        val query = request.url

        return when {
            // ---- Account & session ----
            method == "GET" && path == "/onboard/hello" -> ok(JSONObject().put("onboarding", false))
            path == "/onboard/complete" -> ok()
            method == "GET" && path == "/auth/account" ->
                ok(JSONObject().put("_id", userId(self)).put("email", session.username))
            method == "GET" && path == "/auth/session/all" ->
                ok(JSONArray().put(JSONObject().put("_id", SESSION_ID).put("name", "AIR Chat")))
            s.firstOrNull() == "auth" -> ok()
            s.firstOrNull() == "push" -> ok()
            s.firstOrNull() == "safety" -> ok()
            path == "/sync/settings/fetch" -> settingsFetch(jsonBody(body))
            path == "/sync/settings/set" -> settingsSet(jsonBody(body), query.queryParameter("timestamp")?.toLongOrNull())
            method == "GET" && path == "/sync/unreads" -> unreads(self)

            // ---- Users ----
            method == "GET" && path == "/users/@me" -> ok(userJson(self))
            method == "PATCH" && path == "/users/@me" -> {
                val json = jsonBody(body)
                val current = AirNetwork.ownProfile()
                val remove = json.optJSONArray("remove")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                var name = current.displayName
                var avatar = current.avatar
                if (json.has("display_name")) name = json.optString("display_name")
                if (json.has("avatar")) avatar = json.optString("avatar").ifBlank { null }
                if ("DisplayName" in remove) name = null
                if ("Avatar" in remove) avatar = null
                AirNetwork.updateOwnProfile(name, avatar)
                ok(userJson(self))
            }

            method == "POST" && path == "/users/friend" -> addFriend(jsonBody(body).optString("username"))
            s.size >= 2 && s[0] == "users" -> userRoute(method, s)

            // ---- Channels ----
            method == "POST" && path == "/channels/create" -> createGroup(jsonBody(body))
            s.size >= 2 && s[0] == "channels" -> channelRoute(method, s, body, query)

            // ---- Servers ----
            method == "POST" && path == "/servers/create" -> {
                val (server, _) = AirNetwork.createServer(jsonBody(body).optString("name").ifBlank { "AIR Chat" })
                ok(serverJson(server, withChannelObjects = true))
            }

            s.size >= 2 && s[0] == "servers" -> serverRoute(method, s, body)

            // ---- Invites ----
            s.size >= 2 && s[0] == "invites" -> inviteRoute(method, s.drop(1).joinToString("/"))
            s.size == 3 && s[0] == "custom" && s[1] == "emoji" -> emojiRoute(method, s[2], body)

            else -> error(404, "NotFound")
        }
    }

    private fun rootJson() = JSONObject().apply {
        put("revolt", "0.8")
        put("features", JSONObject().apply {
            put("captcha", JSONObject().put("enabled", false).put("key", ""))
            put("email", false)
            put("invite_only", false)
            put("autumn", JSONObject().put("enabled", true).put("url", "https://cdn.stoatusercontent.com"))
            put("january", JSONObject().put("enabled", false).put("url", ""))
            put("livekit", JSONObject().put("enabled", false).put("nodes", JSONArray()))
        })
        put("ws", "")
        put("app", "")
        put("vapid", "")
    }

    private suspend fun accountError(result: AirAccount.Result): Reply = when (result) {
        is AirAccount.Result.Success -> ok()
        AirAccount.Result.UsernameInvalid -> error(400, Res.string.airnet_err_username_invalid)
        AirAccount.Result.PasswordInvalid -> error(400, Res.string.airnet_err_password_invalid)
        AirAccount.Result.UsernameTaken -> error(409, Res.string.airnet_err_taken)
        AirAccount.Result.WrongPassword -> error(401, Res.string.airnet_err_wrong_password)
        AirAccount.Result.AccountNotFound -> error(404, Res.string.airnet_err_not_found)
        AirAccount.Result.NetworkError -> error(400, Res.string.airnet_err_network)
    }

    private suspend fun login(json: JSONObject): Reply {
        val result = AirAccount.login(json.optString("email"), json.optString("password"))
        if (result !is AirAccount.Result.Success) return accountError(result)
        AirNetwork.start(result.session)
        return ok(JSONObject().apply {
            put("result", "Success")
            put("_id", SESSION_ID)
            put("user_id", userId(result.session.publicKey))
            put("token", TOKEN_PREFIX + result.session.publicKey)
            put("name", "AIR Chat")
        })
    }

    private suspend fun register(json: JSONObject): Reply {
        val result = AirAccount.register(json.optString("email"), json.optString("password"))
        if (result is AirAccount.Result.Success) AirNetwork.start(result.session)
        return accountError(result)
    }

    private fun settingsFetch(json: JSONObject): Reply {
        val keys = json.optJSONArray("keys") ?: JSONArray()
        val out = JSONObject()
        for (i in 0 until keys.length()) {
            val key = keys.getString(i)
            AirNetwork.getSetting(key)?.let { (ts, value) -> out.put(key, JSONArray().put(ts).put(value)) }
        }
        return ok(out)
    }

    private fun settingsSet(json: JSONObject, timestamp: Long?): Reply {
        val ts = timestamp ?: System.currentTimeMillis()
        json.keys().forEach { key -> AirNetwork.setSetting(key, ts, json.optString(key)) }
        return ok()
    }

    private fun unreads(self: String): Reply {
        val out = JSONArray()
        AirNetwork.acks.value.forEach { (conversationId, messageId) ->
            if (AirNetwork.conversations.value.containsKey(conversationId)) {
                out.put(JSONObject().apply {
                    put("_id", JSONObject().put("channel", channelId(conversationId)).put("user", userId(self)))
                    put("last_id", messageId)
                })
            }
        }
        return ok(out)
    }

    private suspend fun addFriend(raw: String): Reply {
        val username = raw.substringBefore('#').trim()
        val contact = AirNetwork.addContact(username) ?: return error(404, Res.string.airnet_user_not_found)
        return ok(userJson(contact.publicKey))
    }

    private suspend fun userRoute(method: String, s: List<String>): Reply {
        val target = (if (s[1] == "@me") AirNetwork.selfKey else publicKeyOf(s[1]))
            ?: if (s.getOrNull(2) == "default_avatar") s[1] else return error(404, "NotFound")
        return when {
            method == "GET" && s.size == 2 -> ok(userJson(target))
            method == "GET" && s.size == 3 && s[2] == "profile" -> ok(JSONObject().put("content", ""))
            method == "GET" && s.size == 3 && s[2] == "default_avatar" -> Reply(200, "", defaultAvatar(target), "image/png")
            method == "GET" && s.size == 3 && s[2] == "dm" -> ok(channelJson(AirNetwork.openDirect(target)))
            method == "GET" && s.size == 3 && s[2] == "mutual" ->
                ok(JSONObject().put("users", JSONArray()).put("servers", JSONArray()).put("channels", JSONArray()))
            s.size == 3 && s[2] == "friend" && method == "PUT" -> {
                AirNetwork.addContact(AirNetwork.username(target))
                ok(userJson(target))
            }

            s.size == 3 && s[2] == "friend" && method == "DELETE" -> {
                AirNetwork.removeContact(target)
                ok(userJson(target))
            }

            s.size == 3 && s[2] == "block" -> ok(userJson(target))
            else -> error(404, "NotFound")
        }
    }

    private val avatarCache = ConcurrentHashMap<String, ByteArray>()

    /** Coloured circle with the first letter of the username, made on the device. */
    private fun defaultAvatar(publicKey: String): ByteArray = avatarCache.getOrPut(publicKey) {
        val size = 256
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val colours = intArrayOf(0xFFFE1A1D.toInt(), 0xFF21B94D.toInt(), 0xFF166AFF.toInt(), 0xFF8E44AD.toInt(), 0xFFE67E22.toInt())
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = colours[(publicKey.hashCode() and 0x7fffffff) % colours.size]
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 128f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val letter = AirNetwork.username(publicKey).take(1).uppercase()
        canvas.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun createGroup(json: JSONObject): Reply {
        val users = json.optJSONArray("users") ?: JSONArray()
        val members = (0 until users.length()).mapNotNull { publicKeyOf(users.getString(it)) }.toSet()
        val group = AirNetwork.createGroup(json.optString("name").ifBlank { "Groupe" }, members)
        return ok(channelJson(group))
    }

    private suspend fun channelRoute(method: String, s: List<String>, body: String, url: okhttp3.HttpUrl): Reply {
        val conversation = conversationOfChannel(s[1]) ?: return error(404, "NotFound")
        val sub = s.drop(2)
        return when {
            sub.isEmpty() && method == "GET" -> ok(channelJson(conversation))
            sub.isEmpty() && method == "PATCH" -> {
                val json = jsonBody(body)
                val server = AirServers.serverOf(conversation.id)
                if (server != null && conversation.kind == AirConversation.KIND_CHANNEL) {
                    val remove = json.optJSONArray("remove")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                    val op = JSONObject().put("id", conversation.id)
                    if (json.has("name")) op.put("name", json.optString("name"))
                    if (json.has("description")) op.put("description", json.optString("description"))
                    if ("Description" in remove) op.put("description", "")
                    AirServers.op(server.id, "channel", op)
                    return ok(channelJson(conversation))
                }
                val name = json.optString("name")
                val updated = if (name.isNotBlank()) AirNetwork.renameConversation(conversation.id, name) else null
                ok(channelJson(updated ?: conversation))
            }

            sub.isEmpty() && method == "DELETE" -> {
                val server = AirServers.serverOf(conversation.id)
                if (server != null && conversation.kind == AirConversation.KIND_CHANNEL) {
                    AirServers.op(server.id, "channel", JSONObject().put("id", conversation.id).put("deleted", true))
                } else {
                    AirNetwork.leaveConversation(conversation.id)
                }
                ok()
            }

            sub.size == 2 && sub[0] == "permissions" -> {
                val server = AirServers.serverOf(conversation.id) ?: return ok(channelJson(conversation))
                val p = jsonBody(body).optJSONObject("permissions") ?: JSONObject()
                val ad = JSONObject().put("a", p.optLong("allow")).put("d", p.optLong("deny"))
                val op = JSONObject().put("id", conversation.id)
                if (sub[1] == "default") op.put("defaultOverride", ad) else op.put("roleOverride", ad.put("role", sub[1]))
                AirServers.op(server.id, "channel", op)
                ok(channelJson(conversation))
            }

            sub == listOf("members") ->
                ok(JSONArray().apply { conversation.members.forEach { put(userJson(it)) } })

            sub.size == 2 && sub[0] == "recipients" && method == "PUT" -> {
                publicKeyOf(sub[1])?.let { AirNetwork.addMembers(conversation.id, setOf(it)) }
                ok()
            }

            sub.size == 2 && sub[0] == "recipients" -> ok()
            sub.size == 2 && sub[0] == "ack" -> {
                AirNetwork.ack(conversation.id, sub[1])
                ok()
            }

            sub == listOf("typing") -> ok()
            sub == listOf("join_call") -> error(400, "NotAvailable")
            sub == listOf("invites") && method == "POST" -> {
                val server = conversation.parent?.let { AirNetwork.conversations.value[it] }
                    ?: return error(400, "NotAvailable")
                val code = AirNetwork.inviteCode(server.id) ?: return error(400, "NotAvailable")
                ok(JSONObject().apply {
                    put("type", "Server"); put("_id", code); put("server", serverId(server.id))
                    put("creator", userId(AirNetwork.selfKey ?: "")); put("channel", channelId(conversation.id))
                })
            }

            sub == listOf("search") -> {
                val searchBody = jsonBody(body)
                val q = searchBody.optString("query").lowercase()
                val found = messageViews(conversation).filter { q.isNotEmpty() && it.content.lowercase().contains(q) }
                    .sortedByDescending { it.id }.take(100)
                ok(messagesResponse(conversation, found, includeUsers = searchBody.optBoolean("include_users", false)))
            }

            sub == listOf("messages") && method == "GET" -> getMessages(conversation, url)
            sub == listOf("messages") && method == "POST" -> sendMessage(conversation, jsonBody(body))
            sub.size == 2 && sub[0] == "messages" -> messageRoute(method, conversation, sub[1], body)
            sub.size == 4 && sub[0] == "messages" && sub[2] == "reactions" -> {
                val type = if (method == "PUT") AirItem.REACT else AirItem.UNREACT
                AirNetwork.post(conversation.id, type, sub[3], parent = sub[1])
                ok()
            }

            sub.size == 3 && sub[0] == "messages" && sub[2] == "pin" -> ok()
            else -> error(404, "NotFound")
        }
    }

    private fun messagesResponse(conversation: AirConversation, list: List<MessageView>, includeUsers: Boolean): Any {
        val messages = JSONArray().apply { list.forEach { put(messageJson(it)) } }
        if (!includeUsers) return messages
        val authors = list.map { it.author }.toSet() + conversation.members
        return JSONObject().apply {
            put("messages", messages)
            put("users", JSONArray().apply { authors.forEach { put(userJson(it)) } })
            val server = conversation.parent?.let { AirNetwork.conversations.value[it] }
            put("members", JSONArray().apply {
                if (server != null) authors.filter { it in server.members }.forEach { put(memberJson(server, it)) }
            })
        }
    }

    private fun getMessages(conversation: AirConversation, url: okhttp3.HttpUrl): Reply {
        val limit = url.queryParameter("limit")?.toIntOrNull()?.coerceIn(1, 100) ?: 50
        val before = url.queryParameter("before")
        val after = url.queryParameter("after")
        val nearby = url.queryParameter("nearby")
        val oldestFirst = url.queryParameter("sort") == "Oldest"
        val includeUsers = url.queryParameter("include_users") == "true"
        var list = messageViews(conversation).sortedBy { it.id }
        list = when {
            nearby != null -> {
                val older = list.filter { it.id <= nearby }.takeLast(limit / 2 + 1)
                val newer = list.filter { it.id > nearby }.take(limit / 2)
                older + newer
            }

            else -> {
                if (before != null) list = list.filter { it.id < before }
                if (after != null) list = list.filter { it.id > after }
                if (oldestFirst || (after != null && before == null)) list.take(limit) else list.takeLast(limit)
            }
        }
        val ordered = if (oldestFirst) list else list.reversed()
        return ok(messagesResponse(conversation, ordered, includeUsers))
    }

    private suspend fun sendMessage(conversation: AirConversation, json: JSONObject): Reply {
        val attachmentIds = json.optJSONArray("attachments")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            ?: emptyList()
        val files = attachmentIds.take(5).mapNotNull { AirNetwork.fileMeta(it) }
        if (files.size != attachmentIds.take(5).size) return error(400, Res.string.airnet_uploads_unavailable)
        val replies = json.optJSONArray("replies")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id") }
        } ?: emptyList()
        val content = json.optString("content")
        val item = AirNetwork.post(
            conversation.id, AirItem.MESSAGE, content,
            tags = replies + files.map { "f:" + it.toJson().toString() }
        )
            ?: return error(401, "Unauthorized")
        val view = messageViews(conversation).firstOrNull { it.id == item.id } ?: return error(400, "InternalError")
        return ok(messageJson(view).put("nonce", json.optString("nonce")))
    }

    private fun messageRoute(method: String, conversation: AirConversation, messageId: String, body: String): Reply {
        return when (method) {
            "GET" -> messageViews(conversation).firstOrNull { it.id == messageId }
                ?.let { ok(messageJson(it)) } ?: error(404, "NotFound")

            "PATCH" -> {
                val content = jsonBody(body).optString("content")
                AirNetwork.post(conversation.id, AirItem.EDIT, content, parent = messageId)
                messageViews(conversation).firstOrNull { it.id == messageId }
                    ?.let { ok(messageJson(it)) } ?: error(404, "NotFound")
            }

            "DELETE" -> {
                AirNetwork.post(conversation.id, AirItem.DELETE, "", parent = messageId)
                ok()
            }

            else -> error(404, "NotFound")
        }
    }

    private fun fileOf(id: String?): AirNetwork.FileMeta? = id?.takeIf { it.isNotBlank() }?.let { AirNetwork.fileMeta(it) }

    private suspend fun serverRoute(method: String, s: List<String>, body: String): Reply {
        val server = conversationOfServer(s[1]) ?: return error(404, "NotFound")
        val sub = s.drop(2)
        val json = jsonBody(body)
        val me = AirNetwork.selfKey ?: return error(401, "Unauthorized")
        val perms = AirServers.permissions(server, me)
        fun can(bit: Long) = perms and bit == bit
        val denied = error(403, "MissingPermission")
        return when {
            sub.isEmpty() && method == "GET" -> ok(serverJson(server))
            sub.isEmpty() && method == "PATCH" -> {
                if (!can(AirServers.MANAGE_SERVER)) return denied
                val remove = json.optJSONArray("remove")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                val op = JSONObject()
                if (json.has("name")) op.put("name", json.optString("name"))
                if (json.has("description")) op.put("description", json.optString("description"))
                if (json.has("icon")) op.put("icon", fileOf(json.optString("icon"))?.toJson() ?: JSONObject.NULL)
                if (json.has("banner")) op.put("banner", fileOf(json.optString("banner"))?.toJson() ?: JSONObject.NULL)
                if ("Icon" in remove) op.put("icon", JSONObject.NULL)
                if ("Banner" in remove) op.put("banner", JSONObject.NULL)
                if ("Description" in remove) op.put("description", "")
                if (op.length() > 0) AirServers.op(server.id, "server", op)
                if (json.has("name")) AirNetwork.renameConversation(server.id, json.optString("name"))
                ok(serverJson(AirNetwork.conversations.value[server.id] ?: server))
            }

            sub.isEmpty() && method == "DELETE" -> {
                when {
                    AirOfficial.isOpen(server.id) -> AirOfficial.setHidden(true)
                    AirServers.ownerOf(server) == me -> {
                        // deleting the server frees its link
                        server.link?.let { AirServers.releaseLink(it) }
                        AirNetwork.leaveConversation(server.id)
                    }

                    else -> {
                        AirServers.op(server.id, "leave", JSONObject().put("user", me))
                        AirNetwork.leaveConversation(server.id)
                    }
                }
                ok()
            }

            sub == listOf("ack") -> ok()
            sub == listOf("channels") && method == "POST" -> {
                if (!can(AirServers.MANAGE_CHANNEL)) return denied
                val name = json.optString("name").ifBlank { "salon" }
                if (AirOfficial.isOpen(server.id)) {
                    val id = "channel:" + chat.stoat.e2ee.AirCrypto.hex(chat.stoat.e2ee.AirCrypto.randomBytes(12))
                    AirServers.op(server.id, "channel.create", JSONObject().put("id", id).put("name", name))
                    AirOfficial.syncCreatedChannels()
                    val channel = AirNetwork.conversations.value[id] ?: return error(400, "InternalError")
                    return ok(channelJson(channel))
                }
                val channel = AirNetwork.createServerChannel(server.id, name) ?: return denied
                ok(channelJson(channel))
            }

            sub == listOf("members") -> ok(JSONObject().apply {
                val members = serverMembers(server)
                put("members", JSONArray().apply { members.forEach { put(memberJson(server, it)) } })
                put("users", JSONArray().apply { members.forEach { put(userJson(it)) } })
            })

            sub.size == 2 && sub[0] == "members" && method == "GET" -> {
                val key = publicKeyOf(sub[1]) ?: return error(404, "NotFound")
                if (key !in serverMembers(server)) error(404, "NotFound") else ok(memberJson(server, key))
            }

            sub.size == 2 && sub[0] == "members" && method == "PATCH" -> {
                val key = publicKeyOf(sub[1]) ?: return error(404, "NotFound")
                val remove = json.optJSONArray("remove")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                val op = JSONObject().put("user", key)
                if (json.has("nickname")) op.put("nickname", json.optString("nickname"))
                if ("Nickname" in remove) op.put("nickname", "")
                if (json.has("roles")) op.put("roles", json.optJSONArray("roles") ?: JSONArray())
                if ("Roles" in remove) op.put("roles", JSONArray())
                if (json.has("timeout")) {
                    val until = runCatching { Instant.parse(json.optString("timeout")).toEpochMilli() }.getOrDefault(0L)
                    op.put("timeout", until)
                }
                if ("Timeout" in remove) op.put("timeout", 0L)
                AirServers.op(server.id, "member", op)
                ok(memberJson(server, key))
            }

            sub.size == 2 && sub[0] == "members" && method == "DELETE" -> {
                val key = publicKeyOf(sub[1]) ?: return error(404, "NotFound")
                if (!can(AirServers.KICK_MEMBERS)) return denied
                AirServers.op(server.id, "kick", JSONObject().put("user", key))
                ok()
            }

            sub == listOf("bans") -> ok(JSONObject().apply {
                val bans = AirServers.state(server).bans
                put("users", JSONArray().apply { bans.keys.forEach { put(userJson(it)) } })
                put("bans", JSONArray().apply {
                    bans.forEach { (key, reason) ->
                        put(JSONObject().apply {
                            put("_id", JSONObject().put("server", serverId(server.id)).put("user", userId(key)))
                            reason?.let { put("reason", it) }
                        })
                    }
                })
            })

            sub.size == 2 && sub[0] == "bans" -> {
                val key = publicKeyOf(sub[1]) ?: return error(404, "NotFound")
                if (!can(AirServers.BAN_MEMBERS)) return denied
                if (method == "PUT") {
                    AirServers.op(server.id, "ban", JSONObject().put("user", key).put("reason", json.optString("reason")))
                    ok(JSONObject().put("_id", JSONObject().put("server", serverId(server.id)).put("user", userId(key))))
                } else {
                    AirServers.op(server.id, "unban", JSONObject().put("user", key))
                    ok()
                }
            }

            sub == listOf("roles") && method == "POST" -> {
                if (!can(AirServers.MANAGE_ROLE)) return denied
                val id = chat.stoat.api.internals.ULID.makeNext()
                AirServers.op(server.id, "role.set", JSONObject().put("id", id).put("name", json.optString("name").ifBlank { "role" }))
                val role = AirServers.state(server).roles[id] ?: return error(400, "InternalError")
                ok(JSONObject().put("id", id).put("role", roleJson(role)))
            }

            sub == listOf("roles", "ranks") -> {
                if (!can(AirServers.MANAGE_ROLE)) return denied
                AirServers.op(server.id, "role.ranks", JSONObject().put("ranks", json.optJSONArray("ranks") ?: JSONArray()))
                ok(serverJson(server))
            }

            sub.size == 2 && sub[0] == "roles" -> {
                val id = sub[1]
                when (method) {
                    "GET" -> AirServers.state(server).roles[id]?.let { ok(roleJson(it)) } ?: error(404, "NotFound")
                    "PATCH" -> {
                        if (!can(AirServers.MANAGE_ROLE)) return denied
                        val remove = json.optJSONArray("remove")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                        val op = JSONObject().put("id", id)
                        listOf("name", "colour", "hoist", "rank").forEach { k -> if (json.has(k)) op.put(k, json.get(k)) }
                        if ("Colour" in remove) op.put("colour", "")
                        AirServers.op(server.id, "role.set", op)
                        AirServers.state(server).roles[id]?.let { ok(roleJson(it)) } ?: error(404, "NotFound")
                    }

                    "DELETE" -> {
                        if (!can(AirServers.MANAGE_ROLE)) return denied
                        AirServers.op(server.id, "role.delete", JSONObject().put("id", id))
                        ok()
                    }

                    else -> error(404, "NotFound")
                }
            }

            sub.size == 2 && sub[0] == "permissions" -> {
                if (!can(AirServers.MANAGE_PERMISSIONS)) return denied
                if (sub[1] == "default") {
                    AirServers.op(server.id, "perm.default", JSONObject().put("value", json.optLong("permissions")))
                } else {
                    val p = json.optJSONObject("permissions") ?: JSONObject()
                    AirServers.op(server.id, "perm.role", JSONObject().put("role", sub[1]).put("a", p.optLong("allow")).put("d", p.optLong("deny")))
                }
                ok(serverJson(server))
            }

            sub == listOf("invites") -> ok(JSONArray().apply {
                val link = server.link ?: AirNetwork.ensureLink(server.id)
                if (link != null) {
                    val channel = visibleChannels(server).firstOrNull()
                    put(JSONObject().apply {
                        put("type", "Server"); put("_id", link); put("server", serverId(server.id))
                        put("creator", userId(AirServers.ownerOf(server) ?: "")); put("channel", channel?.let { channelId(it.id) } ?: "")
                    })
                }
            })

            else -> ok()
        }
    }

    // ---- Custom emojis ----

    private fun emojiJson(serverConv: AirConversation, emoji: AirServers.Emoji) = JSONObject().apply {
        put("_id", emoji.id)
        put("parent", JSONObject().put("type", "Server").put("id", serverId(serverConv.id)))
        put("creator_id", userId(emoji.creator))
        put("name", emoji.name)
        put("animated", AirNetwork.fileMeta(emoji.id)?.contentType == "image/gif")
    }

    private fun allEmojis(): List<Pair<AirConversation, AirServers.Emoji>> =
        AirNetwork.conversations.value.values.filter { it.kind == AirConversation.KIND_SERVER }
            .flatMap { server -> AirServers.state(server).emojis.values.map { server to it } }

    private fun emojiRoute(method: String, id: String, body: String): Reply {
        return when (method) {
            "GET" -> allEmojis().firstOrNull { it.second.id == id }?.let { ok(emojiJson(it.first, it.second)) }
                ?: error(404, "NotFound")

            "PUT" -> {
                val json = jsonBody(body)
                val parent = json.optJSONObject("parent")
                val server = parent?.optString("id")?.let { conversationOfServer(it) } ?: return error(404, "NotFound")
                AirServers.op(server.id, "emoji.add", JSONObject().put("id", id).put("name", json.optString("name")))
                AirServers.state(server).emojis[id]?.let { ok(emojiJson(server, it)) } ?: error(403, "MissingPermission")
            }

            "DELETE" -> {
                val found = allEmojis().firstOrNull { it.second.id == id } ?: return error(404, "NotFound")
                AirServers.op(found.first.id, "emoji.delete", JSONObject().put("id", id))
                ok()
            }

            else -> error(404, "NotFound")
        }
    }

    private val joinMutex = Mutex()

    /** Resolves an invitation: a server link (air.chat.xxxx000X), AIR.CHAT.2026! or an old code. */
    private suspend fun resolveInvite(code: String): AirNetwork.InviteInfo? {
        if (code == AirOfficial.LINK) return AirNetwork.InviteInfo("", AirOfficial.serverId, AirOfficial.NAME)
        if (AirServers.isLink(code)) {
            AirNetwork.conversations.value.values.firstOrNull { it.link == code }?.let { local ->
                return AirNetwork.InviteInfo("", local.id, local.name)
            }
            val claim = AirServers.resolveLink(code) ?: return null
            return AirNetwork.InviteInfo(claim.inviteKey, claim.serverId, claim.name)
        }
        return AirNetwork.parseInvite(code)
    }

    private suspend fun inviteRoute(method: String, rawCode: String): Reply {
        val code = rawCode.trim().substringAfterLast('/')
        val invite = resolveInvite(code) ?: return error(404, "NotFound")
        return when (method) {
            "GET" -> ok(JSONObject().apply {
                val local = AirNetwork.conversations.value[invite.serverId]
                put("type", "Server"); put("code", code)
                put("server_id", serverId(invite.serverId)); put("server_name", invite.name)
                local?.let { AirServers.state(it).icon }?.let { put("server_icon", autumnJson(it)) }
                put("channel_id", ""); put("channel_name", "général")
                put("user_name", invite.name)
                put("member_count", local?.let { serverMembers(it).size } ?: 1)
            })

            "POST" -> joinMutex.withLock {
                if (invite.serverId == AirOfficial.serverId) {
                    AirOfficial.setHidden(false)
                    AirOfficial.ensure()
                } else if (AirNetwork.conversations.value[invite.serverId] == null && invite.inviteKey.isNotEmpty()) {
                    AirNetwork.requestJoin(invite)
                    withTimeoutOrNull(30_000) {
                        while (AirNetwork.conversations.value[invite.serverId] == null) {
                            delay(500)
                        }
                    }
                }
                val server = AirNetwork.conversations.value[invite.serverId]
                    ?: return error(404, Res.string.airnet_join_owner_offline)
                // give the channels a moment to arrive
                withTimeoutOrNull(5_000) { while (AirNetwork.channelsOf(server.id).isEmpty()) delay(300) }
                ok(JSONObject().apply {
                    put("type", "Server")
                    put("server", serverJson(server))
                    put("channels", JSONArray().apply { visibleChannels(server).forEach { put(channelJson(it)) } })
                })
            }

            else -> ok()
        }
    }

    // ------------------------------------------------------------------------------------
    // Realtime
    // ------------------------------------------------------------------------------------

    private fun frame(type: String, json: JSONObject) = json.put("type", type).toString()

    private fun readyFrame(): String {
        val conversations = AirNetwork.conversations.value.values
        return JSONObject().apply {
            put("type", "Ready")
            put("users", JSONArray().apply { knownKeys().forEach { put(userJson(it)) } })
            val visible = conversations.filter { !(AirOfficial.isOpen(it.id) && AirOfficial.isHidden()) }
            put("servers", JSONArray().apply {
                visible.filter { it.kind == AirConversation.KIND_SERVER }.forEach { put(serverJson(it)) }
            })
            put("channels", JSONArray().apply {
                visible.filter { it.kind != AirConversation.KIND_SERVER }
                    .filter { c -> AirServers.serverOf(c.id)?.let { server -> AirServers.state(server).channels[c.id]?.deleted != true } ?: true }
                    .forEach { put(channelJson(it)) }
            })
            put("emojis", JSONArray().apply { allEmojis().forEach { (server, emoji) -> put(emojiJson(server, emoji)) } })
        }.toString()
    }

    private suspend fun runRealtime(token: String, emit: suspend (String) -> Unit) {
        if (!token.startsWith(TOKEN_PREFIX)) return
        ensureStarted() ?: return
        AirNetwork.resolveNames(knownKeys())
        emit(readyFrame())

        coroutineScope {
            val knownUsers = ConcurrentHashMap.newKeySet<String>().apply { addAll(knownKeys()) }

            suspend fun announceUsers(keys: Collection<String>) {
                AirNetwork.resolveNames(keys)
                keys.filter { knownUsers.add(it) }.forEach { key ->
                    emit(frame("UserRelationship", JSONObject().apply {
                        put("id", userId(AirNetwork.selfKey ?: ""))
                        put("user", userJson(key))
                        put("status", if (AirNetwork.contacts.value.containsKey(key)) "Friend" else "None")
                    }))
                }
            }

            // Conversations, servers and channels
            launch {
                var previous = AirNetwork.conversations.value
                AirNetwork.conversations.collect { current ->
                    val before = previous
                    previous = current
                    announceUsers(current.values.flatMap { it.members })
                    // servers first, so their channels can attach to them
                    current.values.sortedBy { if (it.kind == AirConversation.KIND_SERVER) 0 else 1 }.forEach { c ->
                        val old = before[c.id]
                        when {
                            old == null && c.kind == AirConversation.KIND_SERVER -> emit(
                                frame("ServerCreate", JSONObject().apply {
                                    put("id", serverId(c.id))
                                    put("server", serverJson(c))
                                    put("channels", JSONArray().apply {
                                        AirNetwork.channelsOf(c.id).forEach { put(channelJson(it)) }
                                    })
                                    put("emojis", JSONArray())
                                })
                            )

                            old == null && c.kind == AirConversation.KIND_CHANNEL &&
                                    c.parent != null && before[c.parent] == null && current[c.parent] != null -> Unit // sent with ServerCreate

                            old == null -> emit(frame("ChannelCreate", channelJson(c)))
                            old != c && c.kind == AirConversation.KIND_SERVER -> {
                                emit(frame("ServerUpdate", JSONObject().put("id", serverId(c.id)).put("data", serverJson(c))))
                                (c.members - (old?.members ?: emptySet())).forEach { member ->
                                    emit(frame("ServerMemberJoin", JSONObject().put("id", serverId(c.id)).put("user", userId(member))))
                                }
                            }

                            old != c -> emit(frame("ChannelUpdate", JSONObject().put("id", channelId(c.id)).put("data", channelJson(c))))
                        }
                    }
                    (before.keys - current.keys).forEach { id ->
                        val old = before[id] ?: return@forEach
                        if (old.kind == AirConversation.KIND_SERVER) {
                            emit(frame("ServerDelete", JSONObject().put("id", serverId(id))))
                        } else {
                            emit(frame("ChannelDelete", JSONObject().put("id", channelId(id))))
                        }
                    }
                }
            }

            // Messages, edits, deletions and reactions
            launch {
                val seen = ConcurrentHashMap.newKeySet<String>().apply { addAll(AirNetwork.items.value.keys) }
                AirNetwork.items.collect { items ->
                    val fresh = items.values.filter { seen.add(it.id) }.sortedBy { it.createdAt }
                    if (fresh.isEmpty()) return@collect
                    announceUsers(fresh.map { it.author })
                    fresh.forEach { item ->
                        val conversation = AirNetwork.conversations.value[item.conversation] ?: return@forEach
                        val channel = channelId(conversation.id)
                        when (item.type) {
                            AirItem.MESSAGE -> messageViews(conversation).firstOrNull { it.id == item.id }
                                ?.let { emit(frame("Message", messageJson(it))) }

                            AirItem.EDIT -> messageViews(conversation).firstOrNull { it.id == item.parent }?.let { view ->
                                emit(frame("MessageUpdate", JSONObject().apply {
                                    put("id", view.id); put("channel", channel)
                                    put("data", JSONObject().put("content", view.content).apply {
                                        view.edited?.let { put("edited", Instant.ofEpochMilli(it).toString()) }
                                    })
                                }))
                            }

                            AirItem.DELETE -> if (messageViews(conversation).none { it.id == item.parent }) {
                                emit(frame("MessageDelete", JSONObject().put("id", item.parent).put("channel", channel)))
                            }

                            AirItem.REACT, AirItem.UNREACT -> emit(
                                frame(if (item.type == AirItem.REACT) "MessageReact" else "MessageUnreact", JSONObject().apply {
                                    put("id", item.parent); put("channel_id", channel)
                                    put("user_id", userId(item.author)); put("emoji_id", item.text)
                                })
                            )

                            // server administration: refresh what changed
                            AirItem.OP -> if (conversation.kind == AirConversation.KIND_SERVER) {
                                val sid = serverId(conversation.id)
                                val payload = runCatching { JSONObject(item.text) }.getOrDefault(JSONObject())
                                emit(frame("ServerUpdate", JSONObject().put("id", sid).put("data", serverJson(conversation))))
                                when (item.title) {
                                    "member", "readd" -> payload.optString("user").takeIf { it.isNotEmpty() }?.let { key ->
                                        emit(frame("ServerMemberUpdate", JSONObject().apply {
                                            put("id", JSONObject().put("server", sid).put("user", userId(key)))
                                            put("data", memberJson(conversation, key))
                                        }))
                                    }

                                    "kick", "ban", "leave" -> payload.optString("user").takeIf { it.isNotEmpty() }?.let { key ->
                                        if (key == AirNetwork.selfKey && key in AirServers.state(conversation).removed) {
                                            emit(frame("ServerDelete", JSONObject().put("id", sid)))
                                        } else {
                                            emit(frame("ServerMemberLeave", JSONObject().put("id", sid).put("user", userId(key))))
                                        }
                                    }

                                    "emoji.add" -> AirServers.state(conversation).emojis[payload.optString("id")]
                                        ?.let { emit(frame("EmojiCreate", emojiJson(conversation, it))) }

                                    "emoji.delete" -> emit(frame("EmojiDelete", JSONObject().put("id", payload.optString("id"))))

                                    "channel" -> AirNetwork.conversations.value[payload.optString("id")]?.let { ch ->
                                        if (AirServers.state(conversation).channels[ch.id]?.deleted == true) {
                                            emit(frame("ChannelDelete", JSONObject().put("id", channelId(ch.id))))
                                        } else {
                                            emit(frame("ChannelUpdate", JSONObject().put("id", channelId(ch.id)).put("data", channelJson(ch))))
                                        }
                                    }
                                }
                            }

                            // "X joined the server"
                            AirItem.JOIN -> if (conversation.kind == AirConversation.KIND_SERVER) {
                                emit(frame("ServerMemberJoin", JSONObject().put("id", serverId(conversation.id)).put("user", userId(item.author))))
                                visibleChannels(conversation).firstOrNull()?.let { first ->
                                    messageViews(first).firstOrNull { it.id == item.id }?.let { emit(frame("Message", messageJson(it))) }
                                }
                            }
                        }
                    }
                }
            }

            // Contacts (friends)
            launch {
                var previous = AirNetwork.contacts.value.keys
                AirNetwork.contacts.collect { current ->
                    val before = previous
                    previous = current.keys
                    (current.keys - before).forEach { key ->
                        knownUsers.add(key)
                        emit(frame("UserRelationship", JSONObject().apply {
                            put("id", userId(AirNetwork.selfKey ?: ""))
                            put("user", userJson(key)); put("status", "Friend")
                        }))
                    }
                    (before - current.keys).forEach { key ->
                        emit(frame("UserRelationship", JSONObject().apply {
                            put("id", userId(AirNetwork.selfKey ?: ""))
                            put("user", userJson(key)); put("status", "None")
                        }))
                    }
                }
            }

            // Usernames learnt later (members we are not friends with)
            launch {
                var previous = AirNetwork.names.value
                AirNetwork.names.collect { current ->
                    val before = previous
                    previous = current
                    current.filter { (k, v) -> before[k] != v }.keys.forEach { key ->
                        emit(frame("UserUpdate", JSONObject().apply {
                            put("id", userId(key))
                            put("data", JSONObject().put("username", AirNetwork.username(key)))
                        }))
                    }
                }
            }

            // The official server hidden or shown again
            launch {
                var previous = AirOfficial.isHidden()
                AirOfficial.hiddenVersion.collect {
                    val hidden = AirOfficial.isHidden()
                    if (hidden == previous) return@collect
                    previous = hidden
                    val server = AirNetwork.conversations.value[AirOfficial.serverId] ?: return@collect
                    if (hidden) {
                        emit(frame("ServerDelete", JSONObject().put("id", serverId(server.id))))
                    } else {
                        emit(frame("ServerCreate", JSONObject().apply {
                            put("id", serverId(server.id)); put("server", serverJson(server))
                            put("channels", JSONArray().apply { visibleChannels(server).forEach { put(channelJson(it)) } })
                            put("emojis", JSONArray())
                        }))
                    }
                }
            }

            // Presence
            launch {
                var previous = AirNetwork.online.value
                AirNetwork.online.collect { current ->
                    val before = previous
                    previous = current
                    ((current - before) + (before - current)).forEach { key ->
                        val online = key in current
                        emit(frame("UserUpdate", JSONObject().apply {
                            put("id", userId(key))
                            put("data", JSONObject().put("online", online)
                                .put("status", JSONObject().put("presence", if (online) "Online" else "Invisible")))
                        }))
                    }
                }
            }

            awaitCancellation()
        }
    }
}
