package com.example.p2p

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the policy keeps its lists (SharedPreferences on Android, a map in tests). */
interface ContactStore {
    fun load(key: String): Set<String>
    fun save(key: String, values: Set<String>)
}

class InMemoryContactStore : ContactStore {
    private val data = HashMap<String, Set<String>>()
    override fun load(key: String): Set<String> = data[key] ?: emptySet()
    override fun save(key: String, values: Set<String>) {
        data[key] = values.toSet()
    }
}

/**
 * Who may talk to this phone.
 *
 *  - accepted: people the user connected to, or approved. Fully allowed.
 *  - pending : someone unknown connected to us. The connection stays open so the user can see
 *              who it is, but their files are refused and their messages stay hidden until accepted.
 *  - blocked : refused at the door, silently.
 *
 * Pure Kotlin on purpose (no Android imports) so it can be unit tested on a laptop.
 */
class ContactPolicy(private val store: ContactStore) {

    enum class Decision {
        /** Blocked: close the connection. */
        REJECT,

        /** Known or explicitly wanted: nothing to ask. */
        ACCEPTED,

        /** Unknown person connected for the first time: ask the user. */
        PENDING_NEW,

        /** Still waiting for the user's answer. */
        PENDING_EXISTING
    }

    private val lock = Any()
    private var accepted: Set<String> = store.load(KEY_ACCEPTED)
    private val _pending = MutableStateFlow(store.load(KEY_PENDING))
    private val _blocked = MutableStateFlow(store.load(KEY_BLOCKED))

    /** Peer ids waiting for the user to accept or decline. */
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    /** Peer ids the user blocked. */
    val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()

    fun isAccepted(peerId: String): Boolean = synchronized(lock) { peerId in accepted }
    fun isPending(peerId: String): Boolean = peerId in _pending.value
    fun isBlocked(peerId: String): Boolean = peerId in _blocked.value

    /**
     * Someone connected to us and proved who they are. [autoAccept] is true when both people
     * deliberately started the connection (internet code or relay code), so no question is needed.
     */
    fun onInbound(peerId: String, autoAccept: Boolean): Decision = synchronized(lock) {
        when {
            autoAccept -> {
                acceptLocked(peerId)
                Decision.ACCEPTED
            }
            peerId in _blocked.value -> Decision.REJECT
            peerId in accepted -> Decision.ACCEPTED
            peerId in _pending.value -> Decision.PENDING_EXISTING
            else -> {
                _pending.value = _pending.value + peerId
                persistLocked()
                Decision.PENDING_NEW
            }
        }
    }

    /** We connected to them, so we want them. This also lifts an earlier block. */
    fun onOutbound(peerId: String) = synchronized(lock) { acceptLocked(peerId) }

    fun accept(peerId: String) = synchronized(lock) { acceptLocked(peerId) }

    /** "Not now": forgets the request. If they connect again, they are asked about again. */
    fun decline(peerId: String) = synchronized(lock) {
        _pending.value = _pending.value - peerId
        persistLocked()
    }

    fun block(peerId: String) = synchronized(lock) {
        accepted = accepted - peerId
        _pending.value = _pending.value - peerId
        _blocked.value = _blocked.value + peerId
        persistLocked()
    }

    fun unblock(peerId: String) = synchronized(lock) {
        _blocked.value = _blocked.value - peerId
        persistLocked()
    }

    /**
     * One-time upgrade step: people who were already in the contact list before this feature existed
     * are treated as accepted. Returns true if it ran.
     */
    fun migrateExisting(existingPeerIds: Collection<String>): Boolean = synchronized(lock) {
        if (store.load(KEY_MIGRATED).isNotEmpty()) return false
        accepted = accepted + existingPeerIds.filter { it !in _blocked.value }
        store.save(KEY_MIGRATED, setOf("v1"))
        persistLocked()
        true
    }

    private fun acceptLocked(peerId: String) {
        accepted = accepted + peerId
        _pending.value = _pending.value - peerId
        _blocked.value = _blocked.value - peerId
        persistLocked()
    }

    private fun persistLocked() {
        store.save(KEY_ACCEPTED, accepted)
        store.save(KEY_PENDING, _pending.value)
        store.save(KEY_BLOCKED, _blocked.value)
    }

    private companion object {
        const val KEY_ACCEPTED = "accepted"
        const val KEY_PENDING = "pending"
        const val KEY_BLOCKED = "blocked"
        const val KEY_MIGRATED = "migrated"
    }
}
