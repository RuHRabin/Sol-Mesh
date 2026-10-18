package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.p2p.P2PNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Android Foreground Service ensuring persistent background P2P operation.
 * Manages socket listener, Kademlia DHT maintenance, and network change callbacks.
 */
class P2PNodeService : Service() {

    companion object {
        const val CHANNEL_ID = "sol_mesh_node_channel"
        const val MESSAGES_CHANNEL_ID = "sol_mesh_messages_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.service.action.START"
        const val ACTION_STOP = "com.example.service.action.STOP"

        fun startService(context: Context) {
            val intent = Intent(context, P2PNodeService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, P2PNodeService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var p2pNode: P2PNode
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Sol Mesh is running"))
        p2pNode = P2PNode.getInstance(this)
        p2pNode.chatManager.onIncomingMessage = { peerId, _ -> notifyIncomingMessage(peerId) }
        p2pNode.onContactRequest = { peerId, name -> notifyContactRequest(peerId, name) }
        p2pNode.start()
        observeNodeStatus()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun observeNodeStatus() {
        serviceScope.launch {
            p2pNode.nodeStatus.collectLatest { status ->
                val text = when (status.activeConnectionsCount) {
                    0 -> "Running. Nobody connected yet"
                    1 -> "Running. 1 person connected"
                    else -> "Running. ${status.activeConnectionsCount} people connected"
                }
                val notification = buildNotification(text)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Refresh local addresses and republish DHT presence
                val multiaddrs = p2pNode.transportManager.getLocalMultiaddresses()
                p2pNode.peerDiscovery.updateLocalAddresses(multiaddrs)
            }

            override fun onLost(network: Network) {
                // Handle interface change
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sol Mesh running in background",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Sol Mesh reachable so friends can connect to you"
            }
            val messagesChannel = NotificationChannel(
                MESSAGES_CHANNEL_ID,
                "New messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts you when a new message arrives"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(messagesChannel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sol Mesh")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let {
            try { connectivityManager?.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        serviceScope.cancel()
        p2pNode.chatManager.onIncomingMessage = null
        p2pNode.onContactRequest = null
        p2pNode.stop()
    }

    /** Shows "wants to connect" when someone unknown connects while the app is not on screen. */
    private fun notifyContactRequest(peerId: String, name: String) {
        if (AppState.isForeground) return
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            peerId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, MESSAGES_CHANNEL_ID)
            .setContentTitle("Connection request")
            .setContentText("${name.ifBlank { "Someone" }} wants to connect with you")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(3000 + (peerId.hashCode() and 0xFFFF), notification)
    }

    /** Shows "New message" for a chat unless the app is on screen. The text is not shown (privacy). */
    private fun notifyIncomingMessage(peerId: String) {
        if (AppState.isForeground) return
        // Messages from someone who has not been accepted yet stay hidden, so no alert for them either
        if (!p2pNode.contactPolicy.isAccepted(peerId)) return
        serviceScope.launch(Dispatchers.IO) {
            val name = p2pNode.database.peerDao().getPeer(peerId)?.displayName ?: "Someone"
            val launchIntent = Intent(this@P2PNodeService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this@P2PNodeService,
                peerId.hashCode(),
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this@P2PNodeService, MESSAGES_CHANNEL_ID)
                .setContentTitle(name)
                .setContentText("New message")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(2000 + (peerId.hashCode() and 0xFFFF), notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
