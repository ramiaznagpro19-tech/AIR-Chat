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
    const val USERNAME_TOPIC = "air-chat-username-v2"

    /**
     * The chief of AIR Chat (owner of the official server): the very first account ever
     * created. Found on the relays (oldest username reservation) and remembered on the device.
     */
    @Volatile
    var ownerKey: String = ""
        private set

    private const val OWNER_FILE = "owner2.txt"

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
        vault = AirVault(context.applicationContext, "account2.vault")
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

    private fun usernameTag(username: String) = "airchat-v2:username:${normalize(username)}"

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

    suspend fun login(username: String, password: String): Result = withContext(Dispatchers.Default) {
        val name = normalize(username)
        if (!isUsernameValid(name)) return@withContext Result.UsernameInvalid
        val key = deriveKey(name, password)
        val session = Session(name, key)
        val owner = try {
            ownerOf(name)
        } catch (e: Exception) {
            return@withContext Result.NetworkError
        }
        when {
            owner == null && NostrRelayPool.connectedCount.value == 0 -> Result.NetworkError
            owner == null -> Result.AccountNotFound
            owner != session.publicKey -> Result.WrongPassword
            else -> {
                saveSession(session)
                Result.Success(session)
            }
        }
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
