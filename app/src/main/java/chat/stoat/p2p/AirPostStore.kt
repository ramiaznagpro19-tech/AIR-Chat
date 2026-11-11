package chat.stoat.p2p

import android.content.Context
import chat.stoat.e2ee.AirE2EE
import chat.stoat.e2ee.AirVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * AIR Chat: a thread, a forum post, or a reply to one of them.
 *
 * Posts are immutable and signed by the author's device, so every device can verify them no
 * matter which peer relayed them. The set of posts of a conversation is a grow-only set: two
 * devices that exchange what they are missing always end up with the same content.
 */
data class AirPost(
    val id: String,
    val kind: String,
    val parent: String?,
    val title: String,
    val body: String,
    val tags: List<String>,
    val author: String,
    val createdAt: Long,
    val sourceMessage: String?,
    val devicePublicKey: String,
    val signature: String,
) {
    fun signedBytes(channelId: String): ByteArray = JSONArray().apply {
        put(channelId); put(id); put(kind); put(parent ?: ""); put(title); put(body)
        put(JSONArray(tags)); put(author); put(createdAt); put(sourceMessage ?: "")
        put(devicePublicKey)
    }.toString().toByteArray(Charsets.UTF_8)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind); put("parent", parent ?: JSONObject.NULL)
        put("title", title); put("body", body); put("tags", JSONArray(tags))
        put("author", author); put("createdAt", createdAt)
        put("sourceMessage", sourceMessage ?: JSONObject.NULL)
        put("devicePublicKey", devicePublicKey); put("signature", signature)
    }

    companion object {
        const val KIND_THREAD = "thread"
        const val KIND_FORUM = "forum"
        const val KIND_REPLY = "reply"

        fun fromJson(json: JSONObject): AirPost = AirPost(
            id = json.getString("id"),
            kind = json.getString("kind"),
            parent = json.optString("parent").takeIf { !json.isNull("parent") && it.isNotEmpty() },
            title = json.optString("title"),
            body = json.optString("body"),
            tags = json.optJSONArray("tags")?.let { array ->
                (0 until array.length()).map { array.getString(it) }
            } ?: emptyList(),
            author = json.getString("author"),
            createdAt = json.getLong("createdAt"),
            sourceMessage = json.optString("sourceMessage")
                .takeIf { !json.isNull("sourceMessage") && it.isNotEmpty() },
            devicePublicKey = json.getString("devicePublicKey"),
            signature = json.getString("signature"),
        )

        fun create(
            channelId: String,
            id: String,
            kind: String,
            parent: String?,
            title: String,
            body: String,
            tags: List<String>,
            author: String,
            sourceMessage: String?,
        ): AirPost {
            val unsigned = AirPost(
                id = id,
                kind = kind,
                parent = parent,
                title = title.take(MAX_TITLE),
                body = body.take(MAX_BODY),
                tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.take(8),
                author = author,
                createdAt = System.currentTimeMillis(),
                sourceMessage = sourceMessage,
                devicePublicKey = AirE2EE.ownPublicKey,
                signature = "",
            )
            return unsigned.copy(signature = AirE2EE.sign(unsigned.signedBytes(channelId)))
        }

        const val MAX_TITLE = 200
        const val MAX_BODY = 8000
    }
}

/** Local, encrypted-at-rest store of the posts of one conversation. */
class AirPostStore(context: Context, private val channelId: String) {
    private val vault = AirVault(context, "p2p_${channelId}.vault")
    private val _posts = MutableStateFlow<Map<String, AirPost>>(emptyMap())
    val posts: StateFlow<Map<String, AirPost>> = _posts.asStateFlow()

    init {
        val loaded = vault.read()?.let { raw ->
            runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull {
                    runCatching { AirPost.fromJson(array.getJSONObject(it)) }.getOrNull()
                }
            }.getOrNull()
        } ?: emptyList()
        _posts.value = loaded.associateBy { it.id }
    }

    fun ids(): Set<String> = _posts.value.keys

    fun missingFrom(theirIds: Set<String>): List<AirPost> =
        _posts.value.values.filter { it.id !in theirIds }

    /** Verifies and adds posts. Returns the posts that were new. */
    @Synchronized
    fun merge(incoming: List<AirPost>): List<AirPost> {
        val current = _posts.value
        val accepted = incoming.filter { post ->
            post.id !in current && isValid(post)
        }
        if (accepted.isEmpty()) return emptyList()
        accepted.forEach { AirE2EE.rememberDeviceOf(channelId, it.devicePublicKey, it.author) }
        _posts.value = current + accepted.associateBy { it.id }
        persist()
        return accepted
    }

    private fun isValid(post: AirPost): Boolean {
        if (post.kind !in setOf(AirPost.KIND_THREAD, AirPost.KIND_FORUM, AirPost.KIND_REPLY)) return false
        if (post.kind == AirPost.KIND_REPLY && post.parent == null) return false
        if (post.title.length > AirPost.MAX_TITLE || post.body.length > AirPost.MAX_BODY) return false
        if (!AirE2EE.verify(post.devicePublicKey, post.signedBytes(channelId), post.signature)) return false
        // If this device is already known to belong to someone, the author must match.
        val fingerprint = runCatching {
            chat.stoat.e2ee.AirCrypto.fingerprint(chat.stoat.e2ee.AirCrypto.unb64(post.devicePublicKey))
        }.getOrNull() ?: return false
        val knownUser = AirE2EE.userOfDevice(channelId, fingerprint)
        return knownUser == null || knownUser == post.author
    }

    private fun persist() {
        val array = JSONArray()
        _posts.value.values.sortedBy { it.createdAt }.forEach { array.put(it.toJson()) }
        vault.write(array.toString())
    }
}
