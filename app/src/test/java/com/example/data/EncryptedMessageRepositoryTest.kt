package com.example.data

import androidx.room.Room
import com.example.data.repository.EncryptedMessageRepository
import com.example.data.repository.LocalEncryptedMessageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EncryptedMessageRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: EncryptedMessageRepository

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LocalEncryptedMessageRepository(database.encryptedMessageDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testSaveAndRetrieveEncryptedMessage() = runTest {
        val message = EncryptedMessageEntity(
            id = "msg_001",
            conversationPeerId = "peer_alice",
            senderPeerId = "peer_bob",
            recipientPeerId = "peer_alice",
            ciphertextHex = "48656c6c6f20576f726c64",
            nonceHex = "0102030405060708090a0b0c",
            authTagHex = "aabbccddeeff",
            ratchetKeyHex = "1122334455667788",
            sequenceNumber = 1,
            timestamp = 1000L,
            isOutgoing = false,
            deliveryStatus = "RECEIVED"
        )

        repository.saveMessage(message)

        val retrieved = repository.getMessageById("msg_001")
        assertNotNull(retrieved)
        assertEquals("msg_001", retrieved?.id)
        assertEquals("peer_alice", retrieved?.conversationPeerId)
        assertEquals("48656c6c6f20576f726c64", retrieved?.ciphertextHex)
        assertEquals("0102030405060708090a0b0c", retrieved?.nonceHex)
        assertEquals(1L, retrieved?.sequenceNumber)

        val list = repository.getMessagesForConversation("peer_alice").first()
        assertEquals(1, list.size)
        assertEquals("msg_001", list[0].id)
    }

    @Test
    fun testUpdateDeliveryStatus() = runTest {
        val message = EncryptedMessageEntity(
            id = "msg_002",
            conversationPeerId = "peer_charlie",
            senderPeerId = "self",
            recipientPeerId = "peer_charlie",
            ciphertextHex = "deadbeef",
            nonceHex = "1234567890abcdef",
            isOutgoing = true,
            deliveryStatus = "PENDING"
        )

        repository.saveMessage(message)
        assertEquals("PENDING", repository.getMessageById("msg_002")?.deliveryStatus)

        repository.updateDeliveryStatus("msg_002", "SENT")
        assertEquals("SENT", repository.getMessageById("msg_002")?.deliveryStatus)
    }

    @Test
    fun testDeleteMessageAndConversation() = runTest {
        val msg1 = EncryptedMessageEntity(
            id = "msg_003",
            conversationPeerId = "peer_dave",
            senderPeerId = "self",
            recipientPeerId = "peer_dave",
            ciphertextHex = "c0ffee",
            nonceHex = "abcdef1234567890",
            timestamp = 100L,
            isOutgoing = true
        )
        val msg2 = EncryptedMessageEntity(
            id = "msg_004",
            conversationPeerId = "peer_dave",
            senderPeerId = "peer_dave",
            recipientPeerId = "self",
            ciphertextHex = "bada55",
            nonceHex = "9876543210fedcba",
            timestamp = 200L,
            isOutgoing = false
        )

        repository.saveMessages(listOf(msg1, msg2))
        assertEquals(2, repository.getMessageCount("peer_dave"))

        // Delete single message
        val deletedSingle = repository.deleteMessage("msg_003")
        assertTrue(deletedSingle)
        assertNull(repository.getMessageById("msg_003"))
        assertEquals(1, repository.getMessageCount("peer_dave"))

        // Delete conversation
        val deletedConv = repository.deleteConversation("peer_dave")
        assertTrue(deletedConv)
        assertEquals(0, repository.getMessageCount("peer_dave"))
    }
}
