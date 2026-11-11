package chat.stoat.air

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * AIR Chat: connections to public Nostr relays.
 *
 * Relays are only used as a rendezvous point: to find which username belongs to which account,
 * and to exchange short, end-to-end encrypted connection set-up messages so devices can then
 * talk to each other directly. Conversation content never goes through them.
 */
object NostrRelayPool {
    private const val TAG = "AirRelays"

    val RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://nostr.mom",
        "wss://relay.nostr.net",
    )

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val connected = ConcurrentHashMap.newKeySet<String>()
    private val pendingSends = ConcurrentHashMap<String, MutableList<String>>()

    /** Live subscriptions, re-sent whenever a relay (re)connects. */
    private val subscriptions = ConcurrentHashMap<String, Subscription>()

    private val _connectedCount = MutableStateFlow(0)
    val connectedCount: StateFlow<Int> = _connectedCount.asStateFlow()

    private class Subscription(
        val filters: List<JSONObject>,
        val onEvent: (NostrEvent) -> Unit,
        val onEose: ((String) -> Unit)? = null,
    )

    private val okWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        RELAYS.forEach { connect(it) }
    }

    private fun connect(url: String) {
        val request = Request.Builder().url(url).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.add(url)
                _connectedCount.value = connected.size
                subscriptions.forEach { (id, sub) -> sendReq(webSocket, id, sub.filters) }
                pendingSends.remove(url)?.forEach { webSocket.send(it) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(url, text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                reconnectLater(url)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Relay $url failed: ${t.message}")
                reconnectLater(url)
            }
        })
        sockets[url] = socket
    }

    private fun reconnectLater(url: String) {
        connected.remove(url)
        _connectedCount.value = connected.size
        sockets.remove(url)
        scope.launch {
            delay(5_000)
            if (started && !sockets.containsKey(url)) connect(url)
        }
    }

    private fun handle(url: String, text: String) {
        try {
            val array = JSONArray(text)
            when (array.getString(0)) {
                "EVENT" -> {
                    val subId = array.getString(1)
                    val event = NostrEvent.fromJson(array.getJSONObject(2))
                    if (!event.verify()) return
                    subscriptions[subId]?.onEvent?.invoke(event)
                }

                "EOSE" -> {
                    val subId = array.getString(1)
                    subscriptions[subId]?.onEose?.invoke(url)
                }

                "OK" -> {
                    val eventId = array.getString(1)
                    val accepted = array.optBoolean(2, false)
                    if (accepted) okWaiters[eventId]?.complete(true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad relay message from $url", e)
        }
    }

    private fun sendReq(socket: WebSocket, id: String, filters: List<JSONObject>) {
        val req = JSONArray().put("REQ").put(id)
        filters.forEach { req.put(it) }
        socket.send(req.toString())
    }

    private fun sendToAll(text: String) {
        RELAYS.forEach { url ->
            val socket = sockets[url]
            if (socket != null && url in connected) {
                socket.send(text)
            } else {
                pendingSends.getOrPut(url) { mutableListOf() }.add(text)
            }
        }
    }

    /** Publishes an event. Returns true if at least one relay accepted it in time. */
    suspend fun publish(event: NostrEvent, timeoutMs: Long = 8_000): Boolean {
        start()
        val waiter = CompletableDeferred<Boolean>()
        okWaiters[event.id] = waiter
        sendToAll("[\"EVENT\",${event.toJson()}]")
        val result = withTimeoutOrNull(timeoutMs) { waiter.await() } ?: false
        okWaiters.remove(event.id)
        return result
    }

    /** Fire-and-forget publish (for ephemeral signalling). */
    fun send(event: NostrEvent) {
        start()
        sendToAll("[\"EVENT\",${event.toJson()}]")
    }

    /** Opens a long-lived subscription. Returns its id, to close it later. */
    fun subscribe(filters: List<JSONObject>, onEvent: (NostrEvent) -> Unit): String {
        start()
        val id = "air-" + UUID.randomUUID().toString().take(12)
        subscriptions[id] = Subscription(filters, onEvent)
        RELAYS.forEach { url ->
            val socket = sockets[url]
            if (socket != null && url in connected) sendReq(socket, id, filters)
        }
        return id
    }

    fun unsubscribe(id: String) {
        subscriptions.remove(id) ?: return
        val close = JSONArray().put("CLOSE").put(id).toString()
        RELAYS.forEach { url -> if (url in connected) sockets[url]?.send(close) }
    }

    /**
     * Runs a one-shot query and returns every (verified) event received until the relays
     * signal the end of stored events, or until [timeoutMs].
     */
    suspend fun query(filters: List<JSONObject>, timeoutMs: Long = 6_000): List<NostrEvent> {
        start()
        // Give relays a moment to connect on first use
        withTimeoutOrNull(4_000) {
            while (connected.isEmpty()) delay(100)
        }
        val events = ConcurrentHashMap<String, NostrEvent>()
        val eoseFrom = ConcurrentHashMap.newKeySet<String>()
        val done = CompletableDeferred<Unit>()
        val id = "airq-" + UUID.randomUUID().toString().take(12)
        subscriptions[id] = Subscription(
            filters = filters,
            onEvent = { events[it.id] = it },
            onEose = { url ->
                eoseFrom.add(url)
                if (eoseFrom.size >= maxOf(1, connected.size)) done.complete(Unit)
            }
        )
        RELAYS.forEach { url ->
            val socket = sockets[url]
            if (socket != null && url in connected) sendReq(socket, id, filters)
        }
        withTimeoutOrNull(timeoutMs) { done.await() }
        unsubscribe(id)
        return events.values.toList()
    }
}
