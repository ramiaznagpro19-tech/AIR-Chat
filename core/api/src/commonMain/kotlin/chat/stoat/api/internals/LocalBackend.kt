package chat.stoat.api.internals

/**
 * AIR Chat: lets the app answer the realtime connection itself (embedded peer-to-peer backend)
 * instead of connecting to a remote events server.
 *
 * When [realtime] is set, it is called with the session token and a function that delivers
 * each realtime frame (JSON text, same format as the events server) to the API layer. It must
 * keep running for as long as the connection is open.
 */
object LocalBackend {
    @Volatile
    var realtime: (suspend (token: String, emit: suspend (String) -> Unit) -> Unit)? = null
}
