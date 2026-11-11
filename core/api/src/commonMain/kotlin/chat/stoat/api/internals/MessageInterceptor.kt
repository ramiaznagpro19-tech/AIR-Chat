package chat.stoat.api.internals

import chat.stoat.core.model.schemas.Message

/**
 * AIR Chat: hooks that let the app transform message content as it enters or leaves the API
 * layer. Used by end-to-end encryption: outgoing content is encrypted on the device before it
 * is sent, and incoming content is decrypted on the device after it is received.
 *
 * Both hooks default to the identity function, so nothing changes unless the app installs them.
 */
object MessageInterceptor {
    /** Applied to every message received from the API (fetches and realtime events). */
    var incoming: (Message) -> Message = { it }

    /** Applied to message content before it is sent or edited. */
    var outgoing: (channelId: String, content: String) -> String = { _, content -> content }

    fun incomingAll(messages: List<Message>): List<Message> = messages.map { safeIncoming(it) }

    fun safeIncoming(message: Message): Message = try {
        incoming(message)
    } catch (e: Throwable) {
        message
    }
}
