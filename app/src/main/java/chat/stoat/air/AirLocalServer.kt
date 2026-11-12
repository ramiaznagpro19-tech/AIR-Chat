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
                put("channel_type", "TextChannel")
                put("server", serverId(c.parent ?: ""))
                // every server channel can also hold a voice/video call
                put("voice", JSONObject().put("max_users", 8))
                put("name", c.name)
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

    private fun serverJson(c: AirConversation, withChannelObjects: Boolean = false): JSONObject = JSONObject().apply {
        val channels = AirNetwork.channelsOf(c.id)
        put("_id", serverId(c.id))
        put("owner", userId(c.owner ?: ""))
        put("name", c.name)
        put("channels", JSONArray().apply {
            channels.forEach { put(if (withChannelObjects) channelJson(it) else channelId(it.id)) }
        })
        put("default_permissions", ALL_PERMISSIONS)
        put("roles", JSONObject())
    }

    private fun memberJson(server: AirConversation, publicKey: String) = JSONObject().apply {
        put("_id", JSONObject().put("server", serverId(server.id)).put("user", userId(publicKey)))
        put("joined_at", Instant.ofEpochMilli(server.createdAt).toString())
        put("roles", JSONArray())
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
    )

    private fun messageViews(conversation: AirConversation): List<MessageView> {
        val items = AirNetwork.items.value.values.filter { it.conversation == conversation.id }
        val owner = conversation.owner
            ?: conversation.parent?.let { AirNetwork.conversations.value[it]?.owner }
        val base = items.filter { it.type == AirItem.MESSAGE && it.id.length == 26 }.associateBy { it.id }
        val edits = items.filter { it.type == AirItem.EDIT }.sortedBy { it.createdAt }
        val deleted = items.filter { it.type == AirItem.DELETE }
            .filter { del -> base[del.parent]?.let { it.author == del.author || del.author == owner } == true }
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
        return base.values.filter { it.id !in deleted }.map { m ->
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
            val boundary = request.body?.contentType()?.parameter("boundary") ?: return error(400, "BadRequest")
            val part = parseMultipart(raw, boundary) ?: return error(400, "BadRequest")
            if (part.bytes.size > AirNetwork.MAX_FILE_SIZE) return error(413, "FileTooLarge")
            val meta = AirNetwork.storeFile(segments[0], part.filename, part.contentType, part.bytes)
            return ok(JSONObject().put("id", meta.id))
        }
        if (request.method == "GET" && segments.size >= 2) {
            val id = segments[1]
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
            s.size == 2 && s[0] == "invites" -> inviteRoute(method, s[1])

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
        val target = if (s[1] == "@me") AirNetwork.selfKey else publicKeyOf(s[1])
        target ?: return error(404, "NotFound")
        return when {
            method == "GET" && s.size == 2 -> ok(userJson(target))
            method == "GET" && s.size == 3 && s[2] == "profile" -> ok(JSONObject().put("content", ""))
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
                val name = jsonBody(body).optString("name")
                val updated = if (name.isNotBlank()) AirNetwork.renameConversation(conversation.id, name) else null
                ok(channelJson(updated ?: conversation))
            }

            sub.isEmpty() && method == "DELETE" -> {
                AirNetwork.leaveConversation(conversation.id)
                ok()
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
                val q = jsonBody(body).optString("query").lowercase()
                val found = messageViews(conversation).filter { q.isNotEmpty() && it.content.lowercase().contains(q) }
                    .sortedByDescending { it.id }.take(100)
                ok(messagesResponse(conversation, found, includeUsers = true))
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

    private fun serverRoute(method: String, s: List<String>, body: String): Reply {
        val server = conversationOfServer(s[1]) ?: return error(404, "NotFound")
        val sub = s.drop(2)
        return when {
            sub.isEmpty() && method == "GET" -> ok(serverJson(server))
            sub.isEmpty() && method == "PATCH" -> {
                val name = jsonBody(body).optString("name")
                val updated = if (name.isNotBlank()) AirNetwork.renameConversation(server.id, name) else null
                ok(serverJson(updated ?: server))
            }

            sub.isEmpty() && method == "DELETE" -> {
                AirNetwork.leaveConversation(server.id)
                ok()
            }

            sub == listOf("ack") -> ok()
            sub == listOf("channels") && method == "POST" -> {
                val channel = AirNetwork.createServerChannel(server.id, jsonBody(body).optString("name").ifBlank { "salon" })
                    ?: return error(403, "MissingPermission")
                ok(channelJson(channel))
            }

            sub == listOf("members") -> ok(JSONObject().apply {
                put("members", JSONArray().apply { server.members.forEach { put(memberJson(server, it)) } })
                put("users", JSONArray().apply { server.members.forEach { put(userJson(it)) } })
            })

            sub.size == 2 && sub[0] == "members" && method == "GET" -> {
                val key = publicKeyOf(sub[1]) ?: return error(404, "NotFound")
                if (key !in server.members) error(404, "NotFound") else ok(memberJson(server, key))
            }

            sub == listOf("invites") -> ok(JSONArray().apply {
                if (server.owner == AirNetwork.selfKey) {
                    val channel = AirNetwork.channelsOf(server.id).firstOrNull()
                    AirNetwork.inviteCode(server.id)?.let { code ->
                        put(JSONObject().apply {
                            put("type", "Server"); put("_id", code); put("server", serverId(server.id))
                            put("creator", userId(server.owner ?: "")); put("channel", channel?.let { channelId(it.id) } ?: "")
                        })
                    }
                }
            })

            sub == listOf("bans") -> ok(JSONObject().put("users", JSONArray()).put("bans", JSONArray()))
            else -> ok()
        }
    }

    private val joinMutex = Mutex()

    private suspend fun inviteRoute(method: String, code: String): Reply {
        val invite = AirNetwork.parseInvite(code) ?: return error(404, "NotFound")
        return when (method) {
            "GET" -> ok(JSONObject().apply {
                put("type", "Server"); put("code", code)
                put("server_id", serverId(invite.serverId)); put("server_name", invite.name)
                put("channel_id", ""); put("channel_name", "general")
                put("user_name", AirNetwork.username(invite.owner))
                put("member_count", AirNetwork.conversations.value[invite.serverId]?.members?.size ?: 1)
            })

            "POST" -> joinMutex.withLock {
                if (AirNetwork.conversations.value[invite.serverId] == null) {
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
                    put("channels", JSONArray().apply { AirNetwork.channelsOf(server.id).forEach { put(channelJson(it)) } })
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
            put("servers", JSONArray().apply {
                conversations.filter { it.kind == AirConversation.KIND_SERVER }.forEach { put(serverJson(it)) }
            })
            put("channels", JSONArray().apply {
                conversations.filter { it.kind != AirConversation.KIND_SERVER }.forEach { put(channelJson(it)) }
            })
            put("emojis", JSONArray())
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
