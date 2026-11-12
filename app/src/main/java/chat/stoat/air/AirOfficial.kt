package chat.stoat.air

import chat.stoat.e2ee.AirCrypto

/**
 * The official AIR Chat server ("AIR.CHAT.2026!").
 *
 * Every account is in it automatically and cannot leave it (it can only be hidden, and shown
 * again from Discover AIR Chat). It is open: any account can read and write in it, and its
 * owner is the AIR Chat owner account.
 */
object AirOfficial {
    const val LINK = "AIR.CHAT.2026!"
    const val NAME = "AIR Chat"
    private const val HIDDEN_SETTING = "air:official_hidden"

    val serverId: String = "server:" + AirCrypto.hex(AirCrypto.sha256("$LINK#v3".toByteArray())).take(24)
    val generalChannelId: String = "channel:" + AirCrypto.hex(AirCrypto.sha256("$LINK#v3#general".toByteArray())).take(24)

    /** Conversations open to everyone: the official server and all its channels. */
    fun isOpen(conversationId: String): Boolean {
        if (conversationId == serverId) return true
        val c = AirNetwork.conversations.value[conversationId] ?: return conversationId == generalChannelId
        return c.parent == serverId
    }

    fun openConversationIds(): List<String> =
        AirNetwork.conversations.value.values.filter { isOpen(it.id) }.map { it.id }

    fun isHidden(): Boolean = AirNetwork.getSetting(HIDDEN_SETTING)?.second == "true"

    fun setHidden(hidden: Boolean) {
        AirNetwork.setSetting(HIDDEN_SETTING, System.currentTimeMillis(), hidden.toString())
        // re-emit the conversation so the interface shows or hides the server
        AirNetwork.conversations.value[serverId]?.let { AirNetwork.upsertConversation(it.copy(createdAt = it.createdAt)) }
        hiddenVersion.value = hiddenVersion.value + 1
    }

    val hiddenVersion = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Creates the official server on this device and joins it. */
    fun ensure() {
        val me = AirNetwork.selfKey ?: return
        val owner = AirAccount.ownerKey.ifEmpty { null }
        val existing = AirNetwork.conversations.value[serverId]
        val server = AirConversation(
            id = serverId, isGroup = true, name = NAME,
            members = (existing?.members.orEmpty()) + me + listOfNotNull(owner),
            createdAt = existing?.createdAt ?: 1767225600000L,
            kind = AirConversation.KIND_SERVER, owner = owner, link = LINK,
        )
        if (existing != server) AirNetwork.upsertConversation(server)
        val general = AirNetwork.conversations.value[generalChannelId]
        if (general == null) {
            AirNetwork.upsertConversation(
                AirConversation(
                    id = generalChannelId, isGroup = true, name = "général", members = server.members,
                    createdAt = server.createdAt + 1, kind = AirConversation.KIND_CHANNEL,
                    parent = serverId, owner = owner,
                )
            )
        }
        syncCreatedChannels()
        AirServers.ensureJoinItem(serverId)
    }

    /** Channels added by the owner (or members allowed to) through "channel.create" ops. */
    fun syncCreatedChannels() {
        val server = AirNetwork.conversations.value[serverId] ?: return
        AirServers.state(server).createdChannels.forEach { (id, name) ->
            if (!id.startsWith("channel:") || AirNetwork.conversations.value.containsKey(id)) return@forEach
            AirNetwork.upsertConversation(
                AirConversation(
                    id = id, isGroup = true, name = name, members = server.members,
                    createdAt = System.currentTimeMillis(), kind = AirConversation.KIND_CHANNEL,
                    parent = serverId, owner = server.owner,
                )
            )
        }
    }

    /** New authors in the official server are shown as members. */
    fun onItems(items: List<AirItem>) {
        val open = items.filter { isOpen(it.conversation) }
        if (open.isEmpty()) return
        val server = AirNetwork.conversations.value[serverId] ?: return
        val newMembers = open.map { it.author }.toSet() - server.members
        if (newMembers.isNotEmpty()) AirNetwork.upsertConversation(server.copy(members = server.members + newMembers))
        if (open.any { it.type == AirItem.OP }) syncCreatedChannels()
    }
}
