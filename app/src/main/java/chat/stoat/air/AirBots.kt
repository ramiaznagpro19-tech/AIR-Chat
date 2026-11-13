package chat.stoat.air

import android.content.Context
import android.util.Log
import chat.stoat.e2ee.AirVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * AIR Chat bots, without any server: a bot is an AIR Chat account (its own username, which
 * shows a BOT badge) hosted by the phone or PC of the person who created it. While that device
 * has AIR Chat open, the bot answers in the servers and groups it was added to.
 *
 * Bots are set up without code, by their owner, with chat commands (see [HELP_OWNER]).
 */
object AirBots {
    private const val TAG = "AirBots"
    private const val RECENT_MS = 3 * 60_000L

    data class Bot(val username: String, val privateKey: ByteArray, val config: JSONObject) {
        val publicKey: String = Secp256k1.hex(Secp256k1.publicKey(privateKey))
        val session get() = AirAccount.Session(username, privateKey)
        val prefix: String get() = config.optString("prefix", "!").ifBlank { "!" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _bots = MutableStateFlow<List<Bot>>(emptyList())
    val bots: StateFlow<List<Bot>> = _bots.asStateFlow()
    private var vault: AirVault? = null
    private var job: Job? = null
    private var owner: String? = null
    private val lock = Any()

    fun start(context: Context, ownerKey: String) {
        if (owner == ownerKey && job != null) return
        stop()
        owner = ownerKey
        vault = AirVault(context.applicationContext, "bots3_${ownerKey.take(16)}.vault")
        _bots.value = load()
        _bots.value.forEach { AirNetwork.rememberBot(it.publicKey, ownerKey, it.username) }
        job = scope.launch {
            AirNetwork.newItems.collect { items ->
                // answer just after the message is shown, like a person would
                kotlinx.coroutines.delay(600)
                runCatching { handle(items) }.onFailure { Log.w(TAG, "Bot failed", it) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        owner = null
        vault = null
        _bots.value = emptyList()
    }

    fun isMine(key: String) = _bots.value.any { it.publicKey == key }

    sealed class CreateResult {
        data class Success(val bot: Bot) : CreateResult()
        data class Failure(val message: String) : CreateResult()
    }

    suspend fun create(username: String): CreateResult {
        val ownerKey = owner ?: return CreateResult.Failure("Connectez-vous d'abord.")
        val key = Secp256k1.privateKeyFromSeed(chat.stoat.e2ee.AirCrypto.randomBytes(32))
        return when (val r = AirAccount.registerBot(username, key, ownerKey)) {
            is AirAccount.Result.Success -> {
                val bot = Bot(r.session.username, key, defaultConfig())
                synchronized(lock) {
                    _bots.value = _bots.value + bot
                    save()
                }
                AirNetwork.rememberBot(bot.publicKey, ownerKey, bot.username)
                CreateResult.Success(bot)
            }

            AirAccount.Result.UsernameInvalid ->
                CreateResult.Failure("Nom invalide : 3 à 20 caractères (lettres minuscules, chiffres, . et _).")

            AirAccount.Result.UsernameTaken -> CreateResult.Failure("Ce nom est déjà pris.")
            else -> CreateResult.Failure("Impossible de joindre le réseau. Réessayez.")
        }
    }

    fun delete(key: String) {
        synchronized(lock) {
            _bots.value = _bots.value.filter { it.publicKey != key }
            save()
        }
    }

    /** Servers the owner can add bots to. */
    fun servers(): List<AirConversation> = AirNetwork.conversations.value.values
        .filter { it.kind == AirConversation.KIND_SERVER && AirNetwork.selfKey in it.members && !AirOfficial.isOpen(it.id) }
        .sortedBy { it.name.lowercase() }

    fun serversOf(key: String): List<AirConversation> = servers().filter { key in it.members }

    /** Adds the bot to a server: it becomes a member and says it joined. */
    fun addToServer(key: String, serverId: String): Boolean {
        val bot = _bots.value.firstOrNull { it.publicKey == key } ?: return false
        val server = AirNetwork.conversations.value[serverId] ?: return false
        if (server.kind != AirConversation.KIND_SERVER || AirOfficial.isOpen(serverId)) return false
        val state = AirServers.state(server)
        if (key in state.bans) return false
        if (key in state.removed) AirNetwork.post(serverId, AirItem.OP, JSONObject().put("user", key).toString(), title = "readd")
        AirNetwork.addMembers(serverId, setOf(key)) ?: return false
        AirNetwork.post(serverId, AirItem.JOIN, "", author = bot.session)
        return true
    }

    /** Adds the bot to a group conversation. */
    fun addToGroup(key: String, groupId: String): Boolean {
        val group = AirNetwork.conversations.value[groupId] ?: return false
        if (group.kind != AirConversation.KIND_GROUP) return false
        return AirNetwork.addMembers(groupId, setOf(key)) != null
    }

    // ---- what bots do ----

    private fun handle(items: List<AirItem>) {
        val now = System.currentTimeMillis()
        val bots = _bots.value
        if (bots.isEmpty()) return
        for (item in items.sortedBy { it.createdAt }) {
            if (bots.any { it.publicKey == item.author }) continue
            if (now - item.createdAt > RECENT_MS) continue
            val conversation = AirNetwork.conversations.value[item.conversation] ?: continue
            if (AirOfficial.isOpen(conversation.id)) continue
            for (bot in bots) {
                if (bot.publicKey !in conversation.members) continue
                when (item.type) {
                    AirItem.JOIN -> welcome(bot, conversation, item)
                    AirItem.MESSAGE -> onMessage(bot, conversation, item)
                }
            }
        }
    }

    private fun mention(key: String) = "<@${AirLocalServer.userId(key)}>"

    private fun reply(bot: Bot, conversationId: String, to: AirItem?, text: String) {
        AirNetwork.post(conversationId, AirItem.MESSAGE, text.take(4000), tags = listOfNotNull(to?.id), author = bot.session)
    }

    private fun welcome(bot: Bot, server: AirConversation, join: AirItem) {
        if (server.kind != AirConversation.KIND_SERVER) return
        val text = bot.config.optString("welcome").ifBlank { return }
        val channel = AirNetwork.channelsOf(server.id).firstOrNull() ?: return
        reply(bot, channel.id, null, text.replace("{user}", mention(join.author)).replace("{serveur}", server.name))
    }

    private fun onMessage(bot: Bot, conversation: AirConversation, item: AirItem) {
        val text = item.text.trim()
        val lower = text.lowercase()
        val config = bot.config

        // forbidden words: the message is removed (the bot needs the "manage messages" permission)
        val banned = config.optJSONArray("banned").toStrings()
        if (banned.any { it.isNotBlank() && lower.contains(it.lowercase()) }) {
            AirNetwork.post(conversation.id, AirItem.DELETE, "", parent = item.id, author = bot.session)
            reply(bot, conversation.id, null, "${mention(item.author)} ce message contenait un mot interdit et a été supprimé.")
            return
        }

        if (text.startsWith(bot.prefix)) {
            val body = text.removePrefix(bot.prefix).trim()
            val name = body.substringBefore(' ').lowercase()
            val args = body.substringAfter(' ', "").trim()
            command(bot, conversation, item, name, args)
            return
        }

        val auto = config.optJSONObject("auto")
        auto?.keys()?.asSequence()?.firstOrNull { it.isNotBlank() && lower.contains(it.lowercase()) }?.let { keyword ->
            reply(bot, conversation.id, item, fill(auto.optString(keyword), item))
        }
    }

    private fun fill(text: String, item: AirItem) = text.replace("{user}", mention(item.author))

    private fun command(bot: Bot, conversation: AirConversation, item: AirItem, name: String, args: String) {
        val p = bot.prefix
        val isOwner = item.author == owner
        val config = bot.config
        val commands = config.optJSONObject("commands") ?: JSONObject().also { config.put("commands", it) }
        when (name) {
            "aide", "help" -> {
                val custom = commands.keys().asSequence().sorted().joinToString(", ") { "$p$it" }
                val text = buildString {
                    append("**${bot.username}** — bot AIR Chat\n")
                    append("Commandes : ${p}aide, ${p}ping, ${p}info, ${p}des, ${p}choisir a | b")
                    if (custom.isNotEmpty()) append(", $custom")
                    if (isOwner) append("\n\n").append(HELP_OWNER.replace("!", p))
                }
                reply(bot, conversation.id, item, text)
            }

            "ping" -> reply(bot, conversation.id, item, "pong 🏓")
            "info" -> reply(
                bot, conversation.id, item,
                "Je suis **${bot.username}**, un bot AIR Chat hébergé par ${mention(owner ?: "")}. " +
                        "Je réponds quand son appareil a AIR Chat ouvert."
            )

            "des", "dé", "dice" -> reply(bot, conversation.id, item, "🎲 ${(1..6).random()}")
            "choisir", "choose" -> {
                val options = args.split('|').map { it.trim() }.filter { it.isNotEmpty() }
                reply(bot, conversation.id, item, if (options.isEmpty()) "Exemple : ${p}choisir pizza | burger" else "Je choisis : **${options.random()}**")
            }

            "commande", "ajouter" -> ownerOnly(bot, conversation, item, isOwner) {
                val cmd = args.substringBefore(' ').lowercase().removePrefix(p)
                val response = args.substringAfter(' ', "").trim()
                if (cmd.isEmpty() || response.isEmpty()) return@ownerOnly "Exemple : ${p}commande regles Soyez gentils !"
                commands.put(cmd, response)
                "Commande $p$cmd enregistrée."
            }

            "supprimer" -> ownerOnly(bot, conversation, item, isOwner) {
                val cmd = args.lowercase().removePrefix(p)
                if (commands.remove(cmd) == null) "La commande $p$cmd n'existe pas." else "Commande $p$cmd supprimée."
            }

            "bienvenue" -> ownerOnly(bot, conversation, item, isOwner) {
                if (args.isEmpty() || args.equals("off", true)) {
                    config.remove("welcome"); "Message de bienvenue désactivé."
                } else {
                    config.put("welcome", args); "Message de bienvenue enregistré ({user} = le nouveau membre)."
                }
            }

            "auto" -> ownerOnly(bot, conversation, item, isOwner) {
                val keyword = args.substringBefore('=').trim()
                val response = args.substringAfter('=', "").trim()
                if (keyword.isEmpty() || response.isEmpty()) return@ownerOnly "Exemple : ${p}auto bonjour = Salut {user} !"
                val auto = config.optJSONObject("auto") ?: JSONObject().also { config.put("auto", it) }
                auto.put(keyword.lowercase(), response)
                "Réponse automatique enregistrée pour « $keyword »."
            }

            "auto-supprimer" -> ownerOnly(bot, conversation, item, isOwner) {
                config.optJSONObject("auto")?.remove(args.lowercase())
                "Réponse automatique supprimée pour « $args »."
            }

            "interdire" -> ownerOnly(bot, conversation, item, isOwner) {
                if (args.isEmpty()) return@ownerOnly "Exemple : ${p}interdire gros-mot"
                val banned = config.optJSONArray("banned") ?: JSONArray().also { config.put("banned", it) }
                if (args.lowercase() !in banned.toStrings()) banned.put(args.lowercase())
                "Le mot « $args » est maintenant interdit (donnez au bot la permission de gérer les messages)."
            }

            "autoriser" -> ownerOnly(bot, conversation, item, isOwner) {
                val banned = config.optJSONArray("banned").toStrings().filter { it != args.lowercase() }
                config.put("banned", JSONArray(banned))
                "Le mot « $args » est de nouveau autorisé."
            }

            "prefixe", "préfixe" -> ownerOnly(bot, conversation, item, isOwner) {
                if (args.isEmpty() || args.length > 3 || args.contains(' ')) return@ownerOnly "Exemple : ${p}prefixe ?"
                config.put("prefix", args)
                "Nouveau préfixe : $args"
            }

            else -> commands.optString(name).takeIf { it.isNotEmpty() }?.let { reply(bot, conversation.id, item, fill(it, item)) }
        }
    }

    private fun ownerOnly(bot: Bot, conversation: AirConversation, item: AirItem, isOwner: Boolean, change: () -> String) {
        if (!isOwner) {
            reply(bot, conversation.id, item, "Seul le créateur du bot peut faire ça.")
            return
        }
        val answer = change()
        synchronized(lock) { save() }
        reply(bot, conversation.id, item, answer)
    }

    const val HELP_OWNER = """Réglages (créateur du bot uniquement) :
• !commande nom réponse — crée une commande (!nom)
• !supprimer nom — supprime une commande
• !bienvenue texte — message quand quelqu'un rejoint ({user}) · !bienvenue off
• !auto mot = réponse — répond quand un message contient le mot
• !auto-supprimer mot
• !interdire mot / !autoriser mot — supprime les messages qui contiennent ce mot
• !prefixe ? — change le préfixe des commandes"""

    private fun defaultConfig() = JSONObject()
        .put("prefix", "!")
        .put("commands", JSONObject())
        .put("auto", JSONObject())
        .put("banned", JSONArray())

    private fun JSONArray?.toStrings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }

    private fun load(): List<Bot> {
        val raw = vault?.read() ?: return emptyList()
        return runCatching {
            val array = JSONObject(raw).optJSONArray("bots") ?: JSONArray()
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                runCatching {
                    Bot(o.getString("username"), Secp256k1.unhex(o.getString("key")), o.optJSONObject("config") ?: defaultConfig())
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun save() {
        val array = JSONArray()
        _bots.value.forEach { b ->
            array.put(JSONObject().put("username", b.username).put("key", Secp256k1.hex(b.privateKey)).put("config", b.config))
        }
        vault?.write(JSONObject().put("bots", array).toString())
    }
}
