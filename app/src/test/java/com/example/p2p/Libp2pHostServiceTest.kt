package com.example.p2p

import com.example.p2p.libp2p.HostLifecycleState
import com.example.p2p.libp2p.Libp2pEvent
import com.example.p2p.libp2p.Libp2pHostService
import io.libp2p.crypto.keys.generateEd25519KeyPair
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Libp2pHostServiceTest {

    @Test
    fun testNodeIdentificationGenerationAndRestoration() {
        val context = RuntimeEnvironment.getApplication()
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)

        val service = Libp2pHostService(
            context = context,
            serviceScope = testScope
        )

        val (privKey1, pubKey1) = service.getOrCreateIdentity()
        assertNotNull(privKey1)
        assertNotNull(pubKey1)
        assertTrue(privKey1.raw().isNotEmpty())
        assertTrue(pubKey1.raw().isNotEmpty())

        // Ensure consecutive calls restore the identical node identity
        val (privKey2, pubKey2) = service.getOrCreateIdentity()
        assertEquals(privKey1.raw().toList(), privKey2.raw().toList())
        assertEquals(pubKey1.raw().toList(), pubKey2.raw().toList())
    }

    @Test
    fun testNodeIdentityWithCustomKeyPairProvider() {
        val customKeyPair = generateEd25519KeyPair()
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)

        val service = Libp2pHostService(
            keyPairProvider = { customKeyPair },
            serviceScope = testScope
        )

        val (privKey, pubKey) = service.getOrCreateIdentity()
        assertEquals(customKeyPair.first.raw().toList(), privKey.raw().toList())
        assertEquals(customKeyPair.second.raw().toList(), pubKey.raw().toList())
    }

    @Test
    fun testLifecycleTransitionsAndStateFlows() = runTest {
        val customKeyPair = generateEd25519KeyPair()
        val service = Libp2pHostService(
            keyPairProvider = { customKeyPair },
            serviceScope = this
        )

        // 1. Initial State
        assertTrue(service.lifecycleState.value is HostLifecycleState.Stopped)
        assertEquals(0, service.activeConnections.value.size)

        // 2. Start Host
        val startResult = service.start(listenPort = 0) // Port 0 binds to an available test port
        assertTrue(startResult.isSuccess)
        val nodeInfo = startResult.getOrNull()
        assertNotNull(nodeInfo)
        assertTrue(nodeInfo!!.peerIdBase58.isNotEmpty())
        assertTrue(nodeInfo.isRunning)
        assertTrue(service.lifecycleState.value is HostLifecycleState.Running)

        // 3. Stop Host
        val stopResult = service.stop()
        assertTrue(stopResult.isSuccess)
        assertTrue(service.lifecycleState.value is HostLifecycleState.Stopped)
        assertEquals(0, service.activeConnections.value.size)
    }
}
