package chat.stoat.air

import android.util.Log
import chat.stoat.api.StoatAPI
import chat.stoat.api.routes.account.RegistrationBody
import chat.stoat.api.routes.account.negotiateAuthentication
import chat.stoat.api.routes.account.register
import chat.stoat.api.routes.channel.createInvite
import chat.stoat.api.routes.channel.deleteMessage
import chat.stoat.api.routes.channel.editMessage
import chat.stoat.api.routes.channel.fetchMessagesFromChannel
import chat.stoat.api.routes.channel.fetchSingleChannel
import chat.stoat.api.routes.channel.patchChannel
import chat.stoat.api.routes.channel.react
import chat.stoat.api.routes.channel.searchChannel
import chat.stoat.api.routes.channel.sendMessage
import chat.stoat.api.routes.custom.createEmoji
import chat.stoat.api.routes.invites.fetchInviteByCode
import chat.stoat.api.routes.invites.fetchServerInvites
import chat.stoat.api.routes.microservices.autumn.uploadToAutumn
import chat.stoat.api.routes.misc.getRootRoute
import chat.stoat.api.routes.onboard.needsOnboarding
import chat.stoat.api.routes.server.createServer
import chat.stoat.api.routes.server.createServerChannel
import chat.stoat.api.routes.server.createServerRole
import chat.stoat.api.routes.server.fetchMembers
import chat.stoat.api.routes.server.fetchServerBans
import chat.stoat.api.routes.server.patchServer
import chat.stoat.api.routes.server.setServerRolePermissions
import chat.stoat.api.routes.sync.getKeys
import chat.stoat.api.routes.sync.setKey
import chat.stoat.api.routes.sync.syncUnreads
import chat.stoat.api.routes.user.fetchSelf
import chat.stoat.api.routes.user.patchSelf
import chat.stoat.core.model.schemas.ChannelType
import io.ktor.http.ContentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test

/**
 * AIR Chat end-to-end check of the built-in backend, run on an emulator in CI: every call goes
 * through the real interface API layer, the built-in server and the real relays.
 */
class AirBackendTest {
    private val failures = mutableListOf<String>()

    private suspend fun <T> step(name: String, block: suspend () -> T): T? = try {
        val result = block()
        Log.i(TAG, "OK   $name -> ${result.toString().take(300)}")
        println("AIRTEST OK   $name -> ${result.toString().take(300)}")
        result
    } catch (e: Throwable) {
        Log.e(TAG, "FAIL $name", e)
        println("AIRTEST FAIL $name -> $e\n${e.stackTraceToString().take(2500)}")
        failures.add("$name: $e")
        null
    }

    @Test
    fun wholeBackendWorks() = runBlocking {
        val username = "t" + System.currentTimeMillis().toString().takeLast(9)
        val password = "abcd123!?"

        AirAccount.useTestDirectory()
        step("root") { getRootRoute() }
        step("register") {
            val r = register(RegistrationBody(email = username, password = password, captcha = ""))
            check(r.ok) { "register error: ${r.unwrapError().type}" }
        }
        val hints = step("login") {
            val a = negotiateAuthentication(username, password)
            check(a.error == null) { "login error: ${a.error?.type}" }
            a.firstUserHints!!
        } ?: return@runBlocking fail("cannot continue: $failures")

        step("onboarding") { needsOnboarding(hints.token) }
        step("loginAs") { StoatAPI.loginAs(hints.token) }
        step("fetchSelf") { fetchSelf() }
        step("patchSelf") { patchSelf(displayName = "Testeur") }
        step("settings set") { setKey("ordering", "{\"servers\":[]}") }
        step("settings get") { getKeys("ordering") }
        step("unreads") { syncUnreads() }

        delay(5_000)
        step("ready: official server") {
            check(StoatAPI.serverCache.values.any { it.name == "AIR Chat" }) {
                "servers in cache: ${StoatAPI.serverCache.values.map { it.name }}"
            }
        }

        val server = step("createServer") { createServer("Serveur test") }
        val serverId = server?.id
        val channelId = server?.channels?.firstOrNull()?.id
        if (serverId != null && channelId != null) {
            step("fetchChannel") { fetchSingleChannel(channelId) }
            val messageId = step("sendMessage") { org.json.JSONObject(sendMessage(channelId, "Bonjour AIR Chat")).getString("_id") }
            step("fetchMessages") {
                val m = fetchMessagesFromChannel(channelId, limit = 50, includeUsers = true)
                check(m.messages?.any { it.content == "Bonjour AIR Chat" } == true) { "message missing: $m" }
                m.messages?.size
            }
            if (messageId != null) {
                step("editMessage") { editMessage(channelId, messageId, "Bonjour modifié") }
                step("react") { react(channelId, messageId, "👍") }
                step("search") { searchChannel(channelId, query = "Bonjour") }
                step("deleteMessage") { deleteMessage(channelId, messageId) }
            }
            step("patchServer") { patchServer(serverId, description = "Description") }
            step("createChannel text") {
                val c = createServerChannel(serverId, "deuxième", ChannelType.TextChannel)
                check(c.voice == null) { "text channel became voice: $c" }
            }
            step("createChannel voice") {
                val c = createServerChannel(serverId, "vocal", ChannelType.VoiceChannel)
                check(c.voice != null) { "voice channel is not voice: $c" }
            }
            step("main channel is text") { check(fetchSingleChannel(channelId).voice == null) }
            step("patchChannel") { patchChannel(channelId, description = "salon principal") }
            val role = step("createRole") { createServerRole(serverId, "Modo") }
            if (role != null) step("rolePermissions") {
                setServerRolePermissions(serverId, role.id, chat.stoat.core.model.schemas.PermissionDescription(1L shl 23, 0))
            }
            step("members") { fetchMembers(serverId) }
            step("bans") { fetchServerBans(serverId) }
            delay(4_000)
            val invite = step("createInvite") { createInvite(channelId) }
            step("serverInvites") { fetchServerInvites(serverId) }
            invite?.let { step("fetchInvite") { fetchInviteByCode(it.id).also { r -> check(r.ok) } } }

            val png = android.util.Base64.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
                android.util.Base64.DEFAULT
            )
            val fileId = step("upload") { uploadToAutumn(png, "a.png", "attachments", ContentType.Image.PNG) }
            if (fileId != null) step("sendAttachment") { sendMessage(channelId, "image", attachments = listOf(fileId)) }
            val avatarFile = step("uploadAvatar") { uploadToAutumn(png, "avatar.png", "avatars", ContentType.Image.PNG) }
            if (avatarFile != null) {
                step("patchSelf avatar") { patchSelf(avatar = avatarFile) }
                step("avatar in profile") {
                    val self = fetchSelf()
                    check(self.avatar?.id == avatarFile) { "avatar not saved: ${self.avatar}" }
                }
                step("avatar loads in images") {
                    val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                    val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.bumptech.glide.Glide.with(ctx).asBitmap()
                            .load("${chat.stoat.core.model.data.STOAT_FILES}/avatars/$avatarFile")
                            .submit().get(30, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    check(bmp.width == 1) { "bad bitmap ${bmp.width}" }
                    "bitmap ${bmp.width}x${bmp.height}"
                }
            }
            val emojiFile = step("uploadEmoji") { uploadToAutumn(png, "e.png", "emojis", ContentType.Image.PNG) }
            if (emojiFile != null) step("createEmoji") { createEmoji(emojiFile, serverId, "air") }
            step("joinOfficial") { chat.stoat.api.routes.invites.joinInviteByCode("AIR.CHAT.2026!").also { check(it.ok) } }
        }

        // Phone <-> PC: an AIR Chat PC account (desktop app backend) answers on the same network
        val pcbot = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("pcbot")
        if (!pcbot.isNullOrBlank()) {
            step("PC: add the PC account as friend") { chat.stoat.api.routes.user.friendUser(pcbot) }
            step("PC: message answered by the PC") {
                val botKey = AirNetwork.contacts.value.values.firstOrNull { it.username == pcbot }?.publicKey
                checkNotNull(botKey) { "PC account not found" }
                val dm = chat.stoat.api.routes.user.openDM(AirLocalServer.userId(botKey))
                val dmId = checkNotNull(dm.id)
                sendMessage(dmId, "ping du téléphone")
                var reply: String? = null
                for (i in 0 until 75) {
                    delay(2_000)
                    reply = fetchMessagesFromChannel(dmId, limit = 20, includeUsers = false).messages
                        ?.firstOrNull { it.content?.startsWith("pong du PC") == true }?.content
                    if (reply != null) break
                }
                checkNotNull(reply) { "no answer from the PC (online: ${AirNetwork.online.value.size})" }
            }
        }

        println("AIRTEST SUMMARY failures=${failures.size}")
        failures.forEach { println("AIRTEST FAILURE $it") }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    companion object {
        private const val TAG = "AirBackendTest"
    }
}
