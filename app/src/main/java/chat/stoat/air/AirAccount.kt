package chat.stoat.air

import android.content.Context
import chat.stoat.e2ee.AirVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * AIR Chat accounts.
 *
 * An account is a username and a password. The account key is derived from both on the device
 * (PBKDF2), so the same username and password open the same account on any phone, and nothing
 * secret ever leaves the device.
 *
 * Usernames are reserved on public relays the first time they are registered, signed with the
 * account key: the oldest reservation wins, so a username can never be taken again by a new
 * account.
 */
object AirAccount {
    /** Event kind used for username reservations (parameterized replaceable range). */
    const val USERNAME_KIND = 30420

    /** Tag shared by every username reservation (new directory: all older accounts are gone). */
    @Volatile
    var USERNAME_TOPIC = "air-chat-username-v3"
        private set

    /** Automated tests use their own directory, so they never create real accounts. */
    fun useTestDirectory() {
        USERNAME_TOPIC = "air-chat-username-test"
        ownerKey = ""
    }

    /**
     * The chief of AIR Chat (owner of the official server): the very first account ever
     * created. Found on the relays (oldest username reservation) and remembered on the device.
     */
    @Volatile
    var ownerKey: String = ""
        private set

    private const val OWNER_FILE = "owner3.txt"

    /** Looks up (once) who created the very first account. */
    suspend fun resolveOwner(): String? {
        if (ownerKey.isNotEmpty()) return ownerKey
        val events = NostrRelayPool.query(
            listOf(
                JSONObject()
                    .put("kinds", org.json.JSONArray().put(USERNAME_KIND))
                    .put("#t", org.json.JSONArray().put(USERNAME_TOPIC))
                    .put("limit", 5000)
            ),
            timeoutMs = 8_000
        )
        val first = events
            .filter { it.kind == USERNAME_KIND && it.firstTag("t") == USERNAME_TOPIC }
            .minWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
            ?: return null
        setOwner(first.pubkey)
        return first.pubkey
    }

    private fun setOwner(key: String) {
        if (ownerKey.isNotEmpty()) return
        ownerKey = key
        runCatching { java.io.File(appDir, OWNER_FILE).writeText(key) }
    }

    /** Accounts shown with the verified badge (by username, checked against the directory). */
    private val VERIFIED_USERNAMES = listOf("rami")

    private val _verifiedKeys = MutableStateFlow<Set<String>>(emptySet())
    val verifiedKeys: StateFlow<Set<String>> = _verifiedKeys.asStateFlow()

    fun isVerified(publicKey: String): Boolean = publicKey in _verifiedKeys.value

    /** Finds which key really owns each verified username (only the true owner gets the badge). */
    suspend fun resolveVerified() {
        val keys = VERIFIED_USERNAMES.mapNotNull { name -> runCatching { ownerOf(name) }.getOrNull() }.toSet()
        if (keys.isNotEmpty()) _verifiedKeys.value = _verifiedKeys.value + keys
    }

    private var appDir: java.io.File? = null
    private const val PBKDF2_ITERATIONS = 210_000

    data class Session(val username: String, val privateKey: ByteArray) {
        val publicKey: String = Secp256k1.hex(Secp256k1.publicKey(privateKey))
    }

    sealed class Result {
        data class Success(val session: Session) : Result()
        data object UsernameInvalid : Result()
        data object PasswordInvalid : Result()
        data object UsernameTaken : Result()
        data object WrongPassword : Result()
        data object AccountNotFound : Result()
        data object NetworkError : Result()
    }

    private lateinit var vault: AirVault
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    fun install(context: Context) {
        if (::vault.isInitialized) return
        appDir = context.applicationContext.filesDir
        runCatching { java.io.File(appDir, OWNER_FILE).takeIf { it.exists() }?.readText()?.trim() }
            .getOrNull()?.takeIf { it.length == 64 }?.let { ownerKey = it }
        vault = AirVault(context.applicationContext, "account3.vault")
        vault.read()?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                _session.value = Session(
                    username = json.getString("username"),
                    privateKey = Secp256k1.unhex(json.getString("key"))
                )
            }
        }
    }

    fun normalize(username: String): String = username.trim().lowercase()

    /** 3 to 20 characters: letters, digits, "." and "_". */
    fun isUsernameValid(username: String): Boolean =
        Regex("^[a-z0-9._]{3,20}$").matches(normalize(username))

    /** At least 4 letters, 3 digits and 2 special characters. */
    fun passwordProblems(password: String): PasswordCheck = PasswordCheck(
        letters = password.count { it.isLetter() } >= 4,
        digits = password.count { it.isDigit() } >= 3,
        specials = password.count { !it.isLetterOrDigit() && !it.isWhitespace() } >= 2,
    )

    data class PasswordCheck(val letters: Boolean, val digits: Boolean, val specials: Boolean) {
        val isValid: Boolean get() = letters && digits && specials
    }

    private fun deriveKey(username: String, password: String): ByteArray {
        val spec = PBEKeySpec(
            password.toCharArray(),
            "AIR Chat account|${normalize(username)}".toByteArray(Charsets.UTF_8),
            PBKDF2_ITERATIONS,
            256
        )
        val seed = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Secp256k1.privateKeyFromSeed(seed)
    }

    private fun usernameTag(username: String) = "$USERNAME_TOPIC:${normalize(username)}"

    // ---- Changing the password ----
    // The account key never changes. After a password change, the key is kept on the relays
    // encrypted with the new password (signed by the account), and only that password opens it.

    const val KEYSTORE_KIND = 30421

    private fun keystoreTag(username: String) = "$USERNAME_TOPIC:keystore:${normalize(username)}"

    private fun keystoreKey(username: String, password: String, salt: String): ByteArray {
        val spec = PBEKeySpec(
            password.toCharArray(),
            "AIR Chat keystore|${normalize(username)}|$salt".toByteArray(Charsets.UTF_8),
            PBKDF2_ITERATIONS,
            256
        )
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private suspend fun latestKeystore(username: String, owner: String): NostrEvent? {
        val tag = keystoreTag(username)
        return NostrRelayPool.query(
            listOf(
                JSONObject()
                    .put("kinds", org.json.JSONArray().put(KEYSTORE_KIND))
                    .put("authors", org.json.JSONArray().put(owner))
                    .put("#d", org.json.JSONArray().put(tag))
            )
        ).filter { it.kind == KEYSTORE_KIND && it.pubkey == owner && it.firstTag("d") == tag }
            .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
    }

    /** The account key for this password, or null when the password is wrong. */
    private suspend fun keyFor(username: String, password: String, owner: String): ByteArray? {
        val keystore = latestKeystore(username, owner)
        if (keystore != null) {
            return runCatching {
                val json = JSONObject(keystore.content)
                val salt = json.getString("salt")
                val key = chat.stoat.e2ee.AirCrypto.open(
                    keystoreKey(username, password, salt),
                    chat.stoat.e2ee.AirCrypto.unb64(json.getString("data")),
                    "AIR Chat keystore|${normalize(username)}"
                )
                key.takeIf { Secp256k1.hex(Secp256k1.publicKey(it)) == owner }
            }.getOrNull()
        }
        return deriveKey(username, password).takeIf { Secp256k1.hex(Secp256k1.publicKey(it)) == owner }
    }

    sealed class ChangeResult {
        data object Success : ChangeResult()
        data object WrongPassword : ChangeResult()
        data object PasswordInvalid : ChangeResult()
        data object NetworkError : ChangeResult()
    }

    suspend fun changePassword(current: String, new: String): ChangeResult = withContext(Dispatchers.Default) {
        val s = _session.value ?: return@withContext ChangeResult.NetworkError
        if (!passwordProblems(new).isValid) return@withContext ChangeResult.PasswordInvalid
        val key = try {
            keyFor(s.username, current, s.publicKey)
        } catch (e: Exception) {
            return@withContext ChangeResult.NetworkError
        } ?: return@withContext ChangeResult.WrongPassword
        val salt = chat.stoat.e2ee.AirCrypto.b64(chat.stoat.e2ee.AirCrypto.randomBytes(16))
        val sealed = chat.stoat.e2ee.AirCrypto.seal(keystoreKey(s.username, new, salt), key, "AIR Chat keystore|${s.username}")
        val event = NostrEvent.create(
            key,
            KEYSTORE_KIND,
            listOf(listOf("d", keystoreTag(s.username)), listOf("t", "air-chat-keystore")),
            JSONObject().put("v", 1).put("salt", salt).put("data", chat.stoat.e2ee.AirCrypto.b64(sealed)).toString()
        )
        if (!NostrRelayPool.publish(event)) return@withContext ChangeResult.NetworkError
        ChangeResult.Success
    }

    /** Owner (public key) of a username, or null when it is free. */
    suspend fun ownerOf(username: String): String? {
        val events = NostrRelayPool.query(
            listOf(
                JSONObject()
                    .put("kinds", org.json.JSONArray().put(USERNAME_KIND))
                    .put("#d", org.json.JSONArray().put(usernameTag(username)))
            )
        )
        return events
            .filter { it.kind == USERNAME_KIND && it.firstTag("d") == usernameTag(username) }
            .minWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
            ?.pubkey
    }

    suspend fun register(username: String, password: String): Result = withContext(Dispatchers.Default) {
        val name = normalize(username)
        if (!isUsernameValid(name)) return@withContext Result.UsernameInvalid
        if (!passwordProblems(password).isValid) return@withContext Result.PasswordInvalid

        val owner = try {
            ownerOf(name)
        } catch (e: Exception) {
            return@withContext Result.NetworkError
        }
        if (NostrRelayPool.connectedCount.value == 0) return@withContext Result.NetworkError
        if (owner != null) return@withContext Result.UsernameTaken

        val key = deriveKey(name, password)
        val claim = NostrEvent.create(
            key,
            USERNAME_KIND,
            listOf(listOf("d", usernameTag(name)), listOf("t", USERNAME_TOPIC)),
            JSONObject().put("username", name).put("app", "AIR Chat").toString()
        )
        if (!NostrRelayPool.publish(claim)) return@withContext Result.NetworkError

        // Someone may have registered the same name at the same moment: the oldest wins.
        val confirmedOwner = ownerOf(name)
        val session = Session(name, key)
        if (confirmedOwner != null && confirmedOwner != session.publicKey) {
            return@withContext Result.UsernameTaken
        }
        saveSession(session)
        runCatching { resolveOwner() }
        Result.Success(session)
    }

    /** Reserves a username for a bot account hosted by [owner]. */
    suspend fun registerBot(username: String, key: ByteArray, owner: String): Result = withContext(Dispatchers.Default) {
        val name = normalize(username)
        if (!isUsernameValid(name)) return@withContext Result.UsernameInvalid
        val existing = try {
            ownerOf(name)
        } catch (e: Exception) {
            return@withContext Result.NetworkError
        }
        if (NostrRelayPool.connectedCount.value == 0) return@withContext Result.NetworkError
        if (existing != null) return@withContext Result.UsernameTaken
        val claim = NostrEvent.create(
            key,
            USERNAME_KIND,
            listOf(listOf("d", usernameTag(name)), listOf("t", USERNAME_TOPIC)),
            JSONObject().put("username", name).put("app", "AIR Chat").put("bot", true).put("owner", owner).toString()
        )
        if (!NostrRelayPool.publish(claim)) return@withContext Result.NetworkError
        val session = Session(name, key)
        val confirmed = ownerOf(name)
        if (confirmed != null && confirmed != session.publicKey) return@withContext Result.UsernameTaken
        Result.Success(session)
    }

    suspend fun login(username: String, password: String): Result = withContext(Dispatchers.Default) {
        val name = normalize(username)
        if (!isUsernameValid(name)) return@withContext Result.UsernameInvalid
        val owner = try {
            ownerOf(name)
        } catch (e: Exception) {
            return@withContext Result.NetworkError
        }
        if (owner == null) {
            return@withContext if (NostrRelayPool.connectedCount.value == 0) Result.NetworkError else Result.AccountNotFound
        }
        val key = try {
            keyFor(name, password, owner)
        } catch (e: Exception) {
            return@withContext Result.NetworkError
        } ?: return@withContext Result.WrongPassword
        val session = Session(name, key)
        saveSession(session)
        Result.Success(session)
    }

    fun logout() {
        _session.value = null
        vault.write("{}")
    }

    private fun saveSession(session: Session) {
        vault.write(
            JSONObject()
                .put("username", session.username)
                .put("key", Secp256k1.hex(session.privateKey))
                .toString()
        )
        _session.value = session
    }
}
