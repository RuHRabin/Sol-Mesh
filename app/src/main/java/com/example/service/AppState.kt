package com.example.service

/**
 * Tiny process-wide state used to decide whether a new message needs a notification:
 * no notification while the app is on screen.
 */
object AppState {
    @Volatile
    var isForeground: Boolean = false

    /** Peer id of the chat that is currently open, or null. */
    @Volatile
    var openChatPeerId: String? = null
}
