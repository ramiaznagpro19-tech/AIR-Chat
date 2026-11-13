package chat.stoat.air

import android.graphics.Bitmap
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import chat.stoat.activities.MainActivity
import chat.stoat.api.StoatAPI
import chat.stoat.api.StoatAPIHost
import chat.stoat.api.routes.account.RegistrationBody
import chat.stoat.api.routes.account.negotiateAuthentication
import chat.stoat.api.routes.account.register
import chat.stoat.api.routes.channel.react
import chat.stoat.api.routes.channel.sendMessage
import chat.stoat.api.routes.custom.createEmoji
import chat.stoat.api.routes.microservices.autumn.uploadToAutumn
import chat.stoat.api.routes.server.createServer
import chat.stoat.api.routes.server.createServerChannel
import chat.stoat.api.routes.server.createServerRole
import chat.stoat.api.routes.server.patchServer
import chat.stoat.api.routes.user.patchSelf
import chat.stoat.callbacks.Action
import chat.stoat.callbacks.ActionChannel
import chat.stoat.core.model.schemas.ChannelType
import io.ktor.http.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Screenshots of every part of AIR Chat on a phone, with real data (servers, messages, bot,
 * friend on a PC...). The pictures are published by the CI on the "screenshots" branch.
 */
class AirScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.filesDir, "shots").apply { deleteRecursively(); mkdirs() }
    private var count = 0

    private fun shot(name: String) {
        Thread.sleep(1500)
        val bitmap: Bitmap? = instrumentation.uiAutomation.takeScreenshot()
        if (bitmap == null) {
            println("AIRSHOT FAIL $name (no screenshot)")
            return
        }
        count++
        val file = File(dir, "%02d-%s.png".format(count, name))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("AIRSHOT OK ${file.absolutePath}")
    }

    private fun navigate(route: String) {
        instrumentation.runOnMainSync {
            runCatching { AirTestHooks.navController?.navigate(route) }
                .onFailure { println("AIRSHOT FAIL navigate $route: $it") }
        }
    }

    private fun back() {
        instrumentation.runOnMainSync { runCatching { AirTestHooks.navController?.popBackStack() } }
    }

    private suspend fun <T> safe(name: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        Log.e("AirScreenshotTest", name, e)
        println("AIRSHOT FAIL $name -> $e")
        null
    }

    @Test
    fun everyScreen() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val username = (args.getString("user")?.takeIf { it.isNotBlank() } ?: "shot") + "s"
        val password = "abcd123!?"
        AirAccount.useTestDirectory()

        // account with a profile photo
        safe("register") { register(RegistrationBody(email = username, password = password, captcha = "")) }
        val hints = negotiateAuthentication(username, password).firstUserHints!!
        StoatAPIHost.storage.kvStorage.set("sessionToken", hints.token)
        StoatAPIHost.storage.kvStorage.set("sessionId", hints.id)
        StoatAPI.loginAs(hints.token)
        val png = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAACMUlEQVR4Xu2avU7DMBRGnQp1Y6sQU3kEXoWJhYmHY2EqPAk8AANMVVfEwkBRZMmE2L4/n29iV8oZqyr5ju+9jgPtNputO2VWpxy+ZxGozVKB2pyZBzh/uM5d8+vp2Tn383hleFMzASL3iNXtu//ExKRUQJ47xsQEFyiJnqwJVhBwFzJMHwgFUYEIYOn9BNMADroWwqKr8A7ydlJUYIb0QDtJBeZM7xG2k0hg/vRyB5FAy/ACJssv2YKSsI3ECJikL4R2oARaSM8OA99CjZMVaGf56SLonsRyPu9e/3+5fwdgJxLAXiCK/oc/INhqpFsI7h8ifUB+zhmRNLccYkl6D+wQYyYgT2/rkBAA+kebHnaIu8isArUwEMCWHy7CeLJrrZwVi0BtlgrUZqmAc4f1ES5D+cEOP42+3b/AuQ3pkv+lJE4TudwX3502FrD88SlQUQF6yQ/ro8oBSJ+EF5C3ityhH5vd9vLmQ1u0mHQLlYwmW4fh0Ksckm8RfAW0+HxJjXi/2u/63wmoNEbYC3hUe+u+oJ2yDzK2E2zxpSDIvYU29CRmHZJQAjMXwbmsA/FHgOwuFFB1sxXDsSbSi36tMn8dhqWg0zsnqIB2S7GFXT7RELNXmQjJfUUCuQfTpEjSi2YgILyiCfJ76Z7E/rqT7kvy6B5pCw3R3kMOcGVEYKKRANI7p2yhuA4m7YRF9+hmICbcGzApyR0oFYj3KNbEJLe9QJXdFtyFmgLchdphEajNL/fGsjE6T19tAAAAAElFTkSuQmCC",
            android.util.Base64.DEFAULT
        )
        safe("avatar") {
            val avatar = uploadToAutumn(png, "avatar.png", "avatars", ContentType.Image.PNG)
            patchSelf(displayName = "Testeur AIR", avatar = avatar)
        }

        // a server with an icon, channels, role, emoji, messages, a bot
        val server = safe("server") { createServer("Serveur AIR") }
        val serverId = server?.id
        val channelId = server?.channels?.firstOrNull()?.id
        if (serverId != null && channelId != null) {
            safe("icon") { patchServer(serverId, icon = uploadToAutumn(png, "icon.png", "icons", ContentType.Image.PNG)) }
            safe("channels") {
                createServerChannel(serverId, "annonces", ChannelType.TextChannel)
                createServerChannel(serverId, "vocal", ChannelType.VoiceChannel)
            }
            safe("role") { createServerRole(serverId, "Modérateur") }
            safe("emoji") { createEmoji(uploadToAutumn(png, "e.png", "emojis", ContentType.Image.PNG), serverId, "air") }
            safe("messages") {
                val first = JSONObject(sendMessage(channelId, "Bienvenue sur AIR Chat 👋 :air:")).getString("_id")
                react(channelId, first, "🔥")
                sendMessage(channelId, "**Gras**, *italique*, `code` et un lien https://example.com")
                sendMessage(channelId, "Une image :", attachments = listOf(uploadToAutumn(png, "photo.png", "attachments", ContentType.Image.PNG)))
            }
            val bot = safe("bot") { (AirBots.create("botshot" + System.currentTimeMillis().toString().takeLast(6)) as AirBots.CreateResult.Success).bot }
            val serverKey = AirNetwork.conversations.value.values.firstOrNull { it.name == "Serveur AIR" && it.kind == AirConversation.KIND_SERVER }?.id
            if (bot != null && serverKey != null) {
                AirBots.addToServer(bot.publicKey, serverKey)
                delay(1500)
                safe("bot ping") { sendMessage(channelId, "!ping") }
                delay(1500)
                safe("bot help") { sendMessage(channelId, "!aide") }
                delay(2000)
            }
        }

        // a friend on a PC (the PC test account of the CI), with a direct conversation
        val pcbot = args.getString("pcbot")
        var dmId: String? = null
        if (!pcbot.isNullOrBlank()) {
            safe("friend") { chat.stoat.api.routes.user.friendUser(pcbot) }
            delay(3000)
            AirNetwork.contacts.value.values.firstOrNull { it.username == pcbot }?.let { contact ->
                dmId = safe("dm") { chat.stoat.api.routes.user.openDM(AirLocalServer.userId(contact.publicKey)).id }
                dmId?.let { safe("dm message") { sendMessage(it, "Salut le PC 👋") } }
            }
        }

        // ---- the interface ----
        ActivityScenario.launch(MainActivity::class.java)
        delay(10_000)
        shot("accueil")

        if (serverId != null) {
            ActionChannel.send(Action.SwitchServer(serverId))
            delay(2500)
            shot("serveur")
        }
        if (channelId != null) {
            ActionChannel.send(Action.SwitchChannel(channelId))
            delay(4000)
            shot("salon-messages-bot")
        }
        dmId?.let {
            delay(8000) // the PC answers
            ActionChannel.send(Action.SwitchChannel(it))
            delay(4000)
            shot("message-prive-avec-le-pc")
        }
        ActionChannel.send(Action.SwitchServer(AirLocalServer.serverIdOf(AirOfficial.serverId)))
        delay(3000)
        shot("serveur-officiel-air-chat")

        val screens = listOf(
            "settings" to "parametres",
            "settings/profile" to "profil",
            "settings/bots" to "mes-bots",
            "settings/appearance" to "apparence",
            "settings/notifications" to "notifications",
            "settings/language" to "langue",
            "discover" to "decouvrez-air-chat",
            "create/group" to "creer-un-groupe",
            "about" to "a-propos",
        )
        for ((route, name) in screens) {
            navigate(route)
            delay(2000)
            shot(name)
            back()
            delay(800)
        }
        if (serverId != null) {
            for ((route, name) in listOf(
                "settings/server/$serverId" to "serveur-parametres",
                "settings/server/$serverId/overview" to "serveur-vue-generale",
                "settings/server/$serverId/channels" to "serveur-salons",
                "settings/server/$serverId/roles" to "serveur-roles",
                "settings/server/$serverId/emojis" to "serveur-emojis",
                "settings/server/$serverId/invites" to "serveur-lien-invitation",
                "settings/server/$serverId/bans" to "serveur-bannissements",
            )) {
                navigate(route)
                delay(2000)
                shot(name)
                back()
                delay(800)
            }
        }

        // a call to the PC friend (outgoing call screen)
        dmId?.let { id ->
            AirLocalServer.conversationKeyOf(id)?.let { conversation ->
                withContext(Dispatchers.Main) { runCatching { AirCall.start(conversation, false) } }
                delay(3000)
                shot("appel-en-cours")
                withContext(Dispatchers.Main) { runCatching { AirCall.hangUp() } }
            }
        }
        println("AIRSHOT SUMMARY count=$count dir=${dir.absolutePath}")
    }
}
