package chat.stoat.air

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * AIR Chat server administration without a server.
 *
 * Every change to a server (name, icon, roles, permissions, bans, emojis, channels…) is an
 * "op" item signed by its author and shared with the members like messages. Each device
 * replays the ops in order and only applies those whose author had the right permission at
 * that moment, so every member ends up with the same server state.
 */
object AirServers {
    // Permission bits of the interface (see PermissionBit)
    const val MANAGE_CHANNEL = 1L shl 0
    const val MANAGE_SERVER = 1L shl 1
    const val MANAGE_PERMISSIONS = 1L shl 2
    const val MANAGE_ROLE = 1L shl 3
    const val MANAGE_CUSTOMISATION = 1L shl 4
    const val KICK_MEMBERS = 1L shl 6
    const val BAN_MEMBERS = 1L shl 7
    const val TIMEOUT_MEMBERS = 1L shl 8
    const val ASSIGN_ROLES = 1L shl 9
    const val CHANGE_NICKNAME = 1L shl 10
    const val MANAGE_NICKNAMES = 1L shl 11
    const val CHANGE_AVATAR = 1L shl 12
    const val VIEW_CHANNEL = 1L shl 20
    const val READ_HISTORY = 1L shl 21
    const val SEND_MESSAGE = 1L shl 22
    const val MANAGE_MESSAGES = 1L shl 23
    const val INVITE_OTHERS = 1L shl 25
    const val SEND_EMBEDS = 1L shl 26
    const val UPLOAD_FILES = 1L shl 27
    const val REACT = 1L shl 29
    const val CONNECT = 1L shl 30
    const val SPEAK = 1L shl 31
    const val VIDEO = 1L shl 32
    const val LISTEN = 1L shl 36
    const val MENTION_EVERYONE = 1L shl 37
    const val ALL = (1L shl 40) - 1

    const val DEFAULT_PERMISSIONS = VIEW_CHANNEL or READ_HISTORY or SEND_MESSAGE or INVITE_OTHERS or
            SEND_EMBEDS or UPLOAD_FILES or REACT or CONNECT or SPEAK or VIDEO or LISTEN or
            CHANGE_NICKNAME or CHANGE_AVATAR

    data class Role(
        val id: String,
        val name: String,
        val colour: String?,
        val hoist: Boolean,
        val rank: Double,
        val allow: Long,
        val deny: Long,
    )

    data class Emoji(val id: String, val name: String, val creator: String)

    data class ChannelMeta(
        val name: String? = null,
        val description: String? = null,
        val deleted: Boolean = false,
        val roleOverrides: Map<String, Pair<Long, Long>> = emptyMap(),
        val defaultOverride: Pair<Long, Long>? = null,
    )

    data class Listing(val description: String, val icon: String?, val link: String?, val members: Int, val at: Long)

    data class State(
        val name: String,
        val description: String?,
        val icon: AirNetwork.FileMeta?,
        val banner: AirNetwork.FileMeta?,
        val defaultPermissions: Long,
        val roles: Map<String, Role>,
        val memberRoles: Map<String, List<String>>,
        val nicknames: Map<String, String>,
        val timeouts: Map<String, Long>,
        val removed: Set<String>,
        val bans: Map<String, String?>,
        val emojis: Map<String, Emoji>,
        val channels: Map<String, ChannelMeta>,
        val createdChannels: List<Pair<String, String>>,
        val voiceChannels: Set<String>,
        val joinedAt: Map<String, Long>,
        val listing: Listing?,
    ) {
        fun isMember(key: String, conversation: AirConversation) =
            key !in removed && (key in conversation.members || AirOfficial.isOpen(conversation.id))
    }

    private val cache = ConcurrentHashMap<String, Pair<Int, State>>()

    /** The server a conversation belongs to (itself for a server, its parent for a channel). */
    fun serverOf(conversationId: String): AirConversation? {
        val c = AirNetwork.conversations.value[conversationId] ?: return null
        return if (c.kind == AirConversation.KIND_SERVER) c
        else c.parent?.let { AirNetwork.conversations.value[it] }
    }

    /** Whether someone is kicked or banned from the server of this conversation. */
    fun isBlocked(conversationId: String, key: String): Boolean {
        val server = serverOf(conversationId) ?: return false
        return key in state(server).removed
    }

    fun ownerOf(server: AirConversation): String? =
        if (AirOfficial.isOpen(server.id)) AirAccount.ownerKey.ifEmpty { null } else server.owner

    fun state(server: AirConversation): State {
        val items = AirNetwork.items.value.values.filter {
            it.conversation == server.id && (it.type == AirItem.OP || it.type == AirItem.JOIN)
        }
        val version = items.size * 31 + server.hashCode()
        cache[server.id]?.let { (v, state) -> if (v == version) return state }
        val state = fold(server, items)
        cache[server.id] = version to state
        return state
    }

    /** Effective permissions of a member in the server (without channel overrides). */
    fun permissions(server: AirConversation, key: String, state: State = state(server)): Long =
        permissionsWith(server, key, state.defaultPermissions, state.roles, state.memberRoles, state.timeouts)

    private fun permissionsWith(
        server: AirConversation,
        key: String,
        default: Long,
        roles: Map<String, Role>,
        memberRoles: Map<String, List<String>>,
        timeouts: Map<String, Long>,
    ): Long {
        if (key == ownerOf(server)) return ALL
        var perms = default
        memberRoles[key].orEmpty().mapNotNull { roles[it] }.sortedByDescending { it.rank }.forEach { role ->
            perms = (perms or role.allow) and role.deny.inv()
        }
        if ((timeouts[key] ?: 0L) > System.currentTimeMillis()) {
            perms = perms and (SEND_MESSAGE or REACT or SPEAK or VIDEO).inv()
        }
        return perms
    }

    private fun fold(server: AirConversation, items: List<AirItem>): State {
        var name = server.name
        var description: String? = null
        var icon: AirNetwork.FileMeta? = null
        var banner: AirNetwork.FileMeta? = null
        var defaultPermissions = DEFAULT_PERMISSIONS
        val roles = linkedMapOf<String, Role>()
        val memberRoles = mutableMapOf<String, List<String>>()
        val nicknames = mutableMapOf<String, String>()
        val timeouts = mutableMapOf<String, Long>()
        val removed = mutableSetOf<String>()
        val bans = mutableMapOf<String, String?>()
        val emojis = linkedMapOf<String, Emoji>()
        val channels = mutableMapOf<String, ChannelMeta>()
        val createdChannels = mutableListOf<Pair<String, String>>()
        val voiceChannels = mutableSetOf<String>()
        val joinedAt = mutableMapOf<String, Long>()
        var listing: Listing? = null

        items.sortedWith(compareBy<AirItem> { it.createdAt }.thenBy { it.id }).forEach { item ->
            if (item.type == AirItem.JOIN) {
                joinedAt.putIfAbsent(item.author, item.createdAt)
                return@forEach
            }
            val payload = runCatching { JSONObject(item.text) }.getOrNull() ?: return@forEach
            val author = item.author
            if (author in removed && author != ownerOf(server)) return@forEach
            val perms = permissionsWith(server, author, defaultPermissions, roles, memberRoles, timeouts)
            fun can(bit: Long) = perms and bit == bit
            val target = payload.optString("user")
            // nobody can act on the owner
            val targetIsOwner = target.isNotEmpty() && target == ownerOf(server)

            when (item.title) {
                "server" -> if (can(MANAGE_SERVER)) {
                    if (payload.has("name")) name = payload.optString("name").take(64).ifBlank { name }
                    if (payload.has("description")) description = payload.optString("description").take(1024).ifBlank { null }
                    if (payload.has("icon")) icon = payload.optJSONObject("icon")?.let { runCatching { AirNetwork.FileMeta.fromJson(it) }.getOrNull() }
                    if (payload.has("banner")) banner = payload.optJSONObject("banner")?.let { runCatching { AirNetwork.FileMeta.fromJson(it) }.getOrNull() }
                }

                "role.set" -> if (can(MANAGE_ROLE)) {
                    val id = payload.optString("id").ifBlank { return@forEach }
                    val old = roles[id]
                    roles[id] = Role(
                        id = id,
                        name = payload.optString("name", old?.name ?: "role").take(32),
                        colour = if (payload.has("colour")) payload.optString("colour").ifBlank { null } else old?.colour,
                        hoist = if (payload.has("hoist")) payload.optBoolean("hoist") else old?.hoist ?: false,
                        rank = if (payload.has("rank")) payload.optDouble("rank") else old?.rank ?: roles.size.toDouble(),
                        allow = old?.allow ?: 0L,
                        deny = old?.deny ?: 0L,
                    )
                }

                "role.delete" -> if (can(MANAGE_ROLE)) roles.remove(payload.optString("id"))
                "role.ranks" -> if (can(MANAGE_ROLE)) {
                    val ranks = payload.optJSONArray("ranks") ?: JSONArray()
                    for (i in 0 until ranks.length()) {
                        roles[ranks.getString(i)]?.let { roles[it.id] = it.copy(rank = i.toDouble()) }
                    }
                }

                "perm.role" -> if (can(MANAGE_PERMISSIONS)) {
                    roles[payload.optString("role")]?.let {
                        roles[it.id] = it.copy(allow = payload.optLong("a") and ALL, deny = payload.optLong("d") and ALL)
                    }
                }

                "perm.default" -> if (can(MANAGE_PERMISSIONS)) defaultPermissions = payload.optLong("value") and ALL

                "member" -> if (target.isNotEmpty() && !targetIsOwner) {
                    if (payload.has("roles") && can(ASSIGN_ROLES)) {
                        val list = payload.optJSONArray("roles") ?: JSONArray()
                        memberRoles[target] = (0 until list.length()).map { list.getString(it) }.filter { it in roles }
                    }
                    if (payload.has("nickname") &&
                        ((target == author && can(CHANGE_NICKNAME)) || can(MANAGE_NICKNAMES))
                    ) {
                        val nick = payload.optString("nickname").take(32)
                        if (nick.isBlank()) nicknames.remove(target) else nicknames[target] = nick
                    }
                    if (payload.has("timeout") && can(TIMEOUT_MEMBERS)) {
                        val until = payload.optLong("timeout")
                        if (until <= 0) timeouts.remove(target) else timeouts[target] = until
                    }
                }

                "kick" -> if (!targetIsOwner && can(KICK_MEMBERS)) removed.add(target)
                "ban" -> if (!targetIsOwner && can(BAN_MEMBERS)) {
                    removed.add(target)
                    bans[target] = payload.optString("reason").ifBlank { null }
                }

                "unban" -> if (can(BAN_MEMBERS)) bans.remove(target)
                // admitted again after a kick (by any member answering the join request)
                "readd" -> if (target !in bans) removed.remove(target)
                // a member leaving
                "leave" -> if (target == author && !targetIsOwner) removed.add(target)

                "emoji.add" -> if (can(MANAGE_CUSTOMISATION)) {
                    val id = payload.optString("id")
                    if (id.isNotBlank()) emojis[id] = Emoji(id, payload.optString("name").take(32), author)
                }

                "emoji.delete" -> if (can(MANAGE_CUSTOMISATION)) emojis.remove(payload.optString("id"))

                "channel.create" -> if (can(MANAGE_CHANNEL)) {
                    val id = payload.optString("id")
                    if (id.isNotBlank()) createdChannels.add(id to payload.optString("name").take(64).ifBlank { "salon" })
                    if (id.isNotBlank() && payload.optBoolean("voice", false)) voiceChannels.add(id)
                }

                "channel" -> {
                    val id = payload.optString("id").ifBlank { return@forEach }
                    val old = channels[id] ?: ChannelMeta()
                    var meta = old
                    if (can(MANAGE_CHANNEL)) {
                        if (payload.has("name")) meta = meta.copy(name = payload.optString("name").take(64).ifBlank { null })
                        if (payload.has("description")) meta = meta.copy(description = payload.optString("description").take(1024).ifBlank { null })
                        if (payload.optBoolean("deleted")) meta = meta.copy(deleted = true)
                    }
                    if (can(MANAGE_PERMISSIONS)) {
                        payload.optJSONObject("roleOverride")?.let { o ->
                            meta = meta.copy(roleOverrides = meta.roleOverrides + (o.optString("role") to (o.optLong("a") to o.optLong("d"))))
                        }
                        payload.optJSONObject("defaultOverride")?.let { o ->
                            meta = meta.copy(defaultOverride = o.optLong("a") to o.optLong("d"))
                        }
                    }
                    channels[id] = meta
                }

                "listing" -> if (can(MANAGE_SERVER)) {
                    listing = Listing(
                        description = payload.optString("description").take(512),
                        icon = payload.optString("icon").ifBlank { null },
                        link = payload.optString("link").ifBlank { null },
                        members = payload.optInt("members"),
                        at = item.createdAt,
                    )
                }
            }
        }

        return State(
            name, description, icon, banner, defaultPermissions, roles, memberRoles, nicknames, timeouts,
            removed, bans, emojis, channels, createdChannels, voiceChannels, joinedAt, listing
        )
    }

    /** Posts an administration op in a server. */
    fun op(serverId: String, kind: String, payload: JSONObject): AirItem? =
        AirNetwork.post(serverId, AirItem.OP, payload.toString(), title = kind)

    /** Records, once, that this account joined the server ("X joined the server"). */
    fun ensureJoinItem(serverId: String) {
        val me = AirNetwork.selfKey ?: return
        val server = AirNetwork.conversations.value[serverId] ?: return
        if (server.kind != AirConversation.KIND_SERVER) return
        if (me !in server.members && !AirOfficial.isOpen(serverId)) return
        val already = AirNetwork.items.value.values.any {
            it.conversation == serverId && it.type == AirItem.JOIN && it.author == me
        }
        if (!already) AirNetwork.post(serverId, AirItem.JOIN, "")
    }

    // ------------------------------------------------------------------------------------
    // Server links: air.chat.xxxx000X, unique while the server exists
    // ------------------------------------------------------------------------------------

    const val LINK_KIND = 30422
    const val LINK_PREFIX = "air.chat."

    fun isLink(code: String) = code.startsWith(LINK_PREFIX, ignoreCase = true) || code == AirOfficial.LINK

    /** 4 lowercase letters, 3 digits and 1 capital letter, in random order. */
    fun randomLink(): String {
        val random = java.security.SecureRandom()
        val chars = mutableListOf<Char>()
        repeat(4) { chars.add('a' + random.nextInt(26)) }
        repeat(3) { chars.add('0' + random.nextInt(10)) }
        chars.add('A' + random.nextInt(26))
        chars.shuffle(random)
        return LINK_PREFIX + chars.joinToString("")
    }

    private fun linkTag(link: String) = "air-chat:server-link:$link"

    data class LinkClaim(val owner: String, val serverId: String, val inviteKey: String, val name: String)

    /** Who holds a link right now (oldest claim that was not released). */
    suspend fun resolveLink(link: String): LinkClaim? {
        val events = NostrRelayPool.query(
            listOf(
                JSONObject().put("kinds", JSONArray().put(LINK_KIND)).put("#d", JSONArray().put(linkTag(link))),
                JSONObject().put("kinds", JSONArray().put(5)).put("#d", JSONArray().put(linkTag(link))),
            )
        )
        val released = events.filter { it.kind == 5 }.map { it.pubkey to it.createdAt }
        return events
            .filter { it.kind == LINK_KIND && it.firstTag("d") == linkTag(link) }
            .filter { claim -> released.none { (key, at) -> key == claim.pubkey && at >= claim.createdAt } }
            .minWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
            ?.let { event ->
                runCatching {
                    val json = JSONObject(event.content)
                    LinkClaim(event.pubkey, json.getString("serverId"), json.getString("inviteKey"), json.optString("name"))
                }.getOrNull()
            }
    }

    /** Reserves a new unique link for a server this account owns. */
    suspend fun claimLink(server: AirConversation, inviteKey: String): String? {
        val session = AirAccount.session.value ?: return null
        repeat(5) {
            val link = randomLink()
            if (resolveLink(link) != null) return@repeat
            val claim = NostrEvent.create(
                session.privateKey, LINK_KIND,
                listOf(listOf("d", linkTag(link)), listOf("t", "air-chat-server-link")),
                JSONObject().put("serverId", server.id).put("inviteKey", inviteKey).put("name", server.name).toString()
            )
            if (!NostrRelayPool.publish(claim)) return null
            // two servers could have picked the same link at the same moment: the oldest wins
            val holder = resolveLink(link)
            if (holder?.serverId == server.id) return link
        }
        return null
    }

    /** Frees a link when its server is deleted, so it can be used again. */
    fun releaseLink(link: String) {
        val session = AirAccount.session.value ?: return
        val event = NostrEvent.create(
            session.privateKey, 5,
            listOf(listOf("d", linkTag(link)), listOf("k", LINK_KIND.toString())),
            "server deleted"
        )
        NostrRelayPool.send(event)
    }

    // ------------------------------------------------------------------------------------
    // Discover AIR Chat: public servers with at least one million members
    // ------------------------------------------------------------------------------------

    const val DISCOVER_KIND = 30424
    const val DISCOVER_MIN_MEMBERS = 1_000_000

    data class Listed(
        val serverId: String,
        val name: String,
        val description: String,
        val icon: String?,
        val link: String,
        val members: Int,
    )

    /** Publishes a server on Discover AIR Chat (only accepted from one million members). */
    suspend fun publishListing(server: AirConversation, description: String, iconBase64: String?): Boolean {
        val session = AirAccount.session.value ?: return false
        val link = server.link ?: return false
        val members = server.members.size - state(server).removed.size
        if (members < DISCOVER_MIN_MEMBERS) return false
        val event = NostrEvent.create(
            session.privateKey, DISCOVER_KIND,
            listOf(listOf("d", "air-chat:discover:${server.id}"), listOf("t", "air-chat-discover")),
            JSONObject().apply {
                put("serverId", server.id); put("name", server.name); put("description", description.take(512))
                put("icon", iconBase64 ?: ""); put("link", link); put("members", members)
            }.toString()
        )
        op(server.id, "listing", JSONObject().put("description", description).put("link", link).put("members", members))
        return NostrRelayPool.publish(event)
    }

    suspend fun fetchListings(): List<Listed> {
        val events = NostrRelayPool.query(
            listOf(JSONObject().put("kinds", JSONArray().put(DISCOVER_KIND)).put("#t", JSONArray().put("air-chat-discover")).put("limit", 200))
        )
        return events.mapNotNull { event ->
            runCatching {
                val json = JSONObject(event.content)
                val link = json.getString("link")
                // only the real holder of the link can list the server
                val claim = resolveLink(link)
                if (claim?.owner != event.pubkey && link != AirOfficial.LINK) return@runCatching null
                Listed(
                    serverId = json.getString("serverId"),
                    name = json.optString("name").take(64),
                    description = json.optString("description").take(512),
                    icon = json.optString("icon").ifBlank { null },
                    link = link,
                    members = json.optInt("members"),
                ).takeIf { it.members >= DISCOVER_MIN_MEMBERS }
            }.getOrNull()
        }.distinctBy { it.serverId }.sortedByDescending { it.members }
    }
}
