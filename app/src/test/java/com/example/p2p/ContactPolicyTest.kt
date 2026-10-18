package com.example.p2p

import com.example.p2p.ContactPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactPolicyTest {

    @Test
    fun unknownInboundBecomesPendingOnceAndStaysPending() {
        val policy = ContactPolicy(InMemoryContactStore())
        assertEquals(Decision.PENDING_NEW, policy.onInbound("alice", autoAccept = false))
        assertEquals(true, policy.isPending("alice"))
        assertEquals(false, policy.isAccepted("alice"))
        assertEquals(Decision.PENDING_EXISTING, policy.onInbound("alice", autoAccept = false))
        assertEquals(setOf("alice"), policy.pending.value)
    }

    @Test
    fun acceptingMovesFromPendingToAccepted() {
        val policy = ContactPolicy(InMemoryContactStore())
        policy.onInbound("alice", false)
        policy.accept("alice")
        assertEquals(true, policy.isAccepted("alice"))
        assertEquals(false, policy.isPending("alice"))
        assertEquals(Decision.ACCEPTED, policy.onInbound("alice", false))
    }

    @Test
    fun decliningForgetsTheRequestAndAsksAgainNextTime() {
        val policy = ContactPolicy(InMemoryContactStore())
        policy.onInbound("alice", false)
        policy.decline("alice")
        assertEquals(false, policy.isPending("alice"))
        assertEquals(false, policy.isAccepted("alice"))
        assertEquals(Decision.PENDING_NEW, policy.onInbound("alice", false))
    }

    @Test
    fun blockedPeopleAreRejectedUntilUnblocked() {
        val policy = ContactPolicy(InMemoryContactStore())
        policy.onInbound("mallory", false)
        policy.block("mallory")
        assertEquals(true, policy.isBlocked("mallory"))
        assertEquals(false, policy.isPending("mallory"))
        assertEquals(Decision.REJECT, policy.onInbound("mallory", false))
        policy.unblock("mallory")
        assertEquals(Decision.PENDING_NEW, policy.onInbound("mallory", false))
    }

    @Test
    fun connectingToSomeoneYourselfAcceptsThemAndLiftsABlock() {
        val policy = ContactPolicy(InMemoryContactStore())
        policy.block("bob")
        policy.onOutbound("bob")
        assertEquals(true, policy.isAccepted("bob"))
        assertEquals(false, policy.isBlocked("bob"))
        assertEquals(Decision.ACCEPTED, policy.onInbound("bob", false))
    }

    @Test
    fun mutualCodeConnectionsNeedNoQuestion() {
        val policy = ContactPolicy(InMemoryContactStore())
        assertEquals(Decision.ACCEPTED, policy.onInbound("carol", autoAccept = true))
        assertEquals(true, policy.isAccepted("carol"))
        assertEquals(false, policy.isPending("carol"))
    }

    @Test
    fun stateSurvivesRestart() {
        val store = InMemoryContactStore()
        val first = ContactPolicy(store)
        first.onInbound("alice", false)
        first.onOutbound("bob")
        first.block("mallory")
        val second = ContactPolicy(store)
        assertEquals(setOf("alice"), second.pending.value)
        assertEquals(setOf("mallory"), second.blocked.value)
        assertEquals(true, second.isAccepted("bob"))
    }

    @Test
    fun migrationAcceptsExistingContactsOnlyOnce() {
        val store = InMemoryContactStore()
        val policy = ContactPolicy(store)
        policy.block("mallory")
        assertEquals(true, policy.migrateExisting(listOf("old1", "old2", "mallory")))
        assertEquals(true, policy.isAccepted("old1"))
        assertEquals(false, policy.isAccepted("mallory"))
        assertEquals(false, ContactPolicy(store).migrateExisting(listOf("old3")))
        assertEquals(false, ContactPolicy(store).isAccepted("old3"))
    }
}
