package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.p2p.P2PNode
import com.example.p2p.RoutingMode
import com.example.service.P2PNodeService
import com.example.ui.chat.ChatScreen
import com.example.ui.home.HomeScreen
import com.example.ui.identity.IdentityScreen
import com.example.ui.navigation.Screen
import com.example.ui.network.NetworkStatusScreen
import com.example.ui.peers.PeersScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.shell.ShellScreen
import com.example.ui.transfers.TransfersScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.Accent
import com.example.ui.theme.AccentSoft
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.TerminalTextSecondary
import com.example.ui.theme.RobotoMonoFontFamily
import com.example.ui.theme.TerminalAmber
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TerminalBlack
import com.example.ui.theme.TerminalBorder
import com.example.ui.theme.TerminalCyan
import com.example.ui.theme.TerminalGreenBright
import com.example.ui.theme.TerminalSurface
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary

class MainActivity : ComponentActivity() {

    private lateinit var p2pNode: P2PNode

    override fun onStart() {
        super.onStart()
        com.example.service.AppState.isForeground = true
    }

    override fun onStop() {
        super.onStop()
        com.example.service.AppState.isForeground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark only, so always use light system bar icons (even when the phone is in light mode)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        p2pNode = P2PNode.getInstance(this)
        p2pNode.start()
        P2PNodeService.startService(this)

        setContent {
            MyApplicationTheme {
                RequestPermissions()
                SolMeshApp(p2pNode = p2pNode)
            }
        }
    }

    @Composable
    private fun RequestPermissions() {
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { _ -> }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}

@Composable
fun SolMeshApp(p2pNode: P2PNode) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute?.startsWith("chat/") != true

    val navItems = listOf(
        NavigationItem(Screen.Home.route, "Home", Icons.Default.Home),
        NavigationItem(Screen.Peers.route, "Chats", Icons.Default.Chat),
        NavigationItem(Screen.Transfers.route, "Files", Icons.Default.FolderShared),
        NavigationItem(Screen.Settings.route, "Settings", Icons.Default.Settings)
    )

    // The advanced screens live under Settings, so keep the Settings tab highlighted on them
    val advancedRoutes = setOf(Screen.Shell.route, Screen.Identity.route, Screen.NetworkStatus.route)
    val highlightedRoute = if (currentRoute in advancedRoutes) Screen.Settings.route else currentRoute

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = TerminalBg,
                    tonalElevation = 0.dp,
                    modifier = Modifier.testTag("main_bottom_nav")
                ) {
                    navItems.forEach { item ->
                        val selected = highlightedRoute == item.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = item.label,
                                    fontFamily = InterFontFamily,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent,
                                selectedTextColor = Accent,
                                indicatorColor = AccentSoft,
                                unselectedIconColor = TerminalTextSecondary,
                                unselectedTextColor = TerminalTextSecondary
                            ),
                            modifier = Modifier.testTag("nav_item_${item.label.lowercase()}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(TerminalBlack)
        ) {
            // High-Density Linux Polybar / Status Strip
            if (showBottomBar) StatusStrip(p2pNode = p2pNode)

            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        p2pNode = p2pNode,
                        onNavigateToPeers = { navController.navigate(Screen.Peers.route) },
                        onNavigateToChat = { peerId -> navController.navigate(Screen.Chat.createRoute(peerId)) },
                        onNavigateToTransfers = { navController.navigate(Screen.Transfers.route) },
                        onNavigateToIdentity = { navController.navigate(Screen.Identity.route) },
                        onNavigateToNetwork = { navController.navigate(Screen.NetworkStatus.route) }
                    )
                }

                composable(Screen.Shell.route) {
                    ShellScreen(p2pNode = p2pNode)
                }

                composable(Screen.Peers.route) {
                    PeersScreen(
                        p2pNode = p2pNode,
                        onNavigateToChat = { peerId -> navController.navigate(Screen.Chat.createRoute(peerId)) },
                        onNavigateToTransfers = { navController.navigate(Screen.Transfers.route) }
                    )
                }

                composable(
                    route = Screen.Chat.route,
                    arguments = listOf(navArgument("peerId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val peerId = backStackEntry.arguments?.getString("peerId") ?: ""
                    ChatScreen(
                        peerId = peerId,
                        p2pNode = p2pNode,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.Transfers.route) {
                    TransfersScreen(p2pNode = p2pNode)
                }

                composable(Screen.Identity.route) {
                    IdentityScreen(p2pNode = p2pNode)
                }

                composable(Screen.NetworkStatus.route) {
                    NetworkStatusScreen(p2pNode = p2pNode)
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        p2pNode = p2pNode,
                        onOpenIdentity = { navController.navigate(Screen.Identity.route) },
                        onOpenNetwork = { navController.navigate(Screen.NetworkStatus.route) },
                        onOpenTerminal = { navController.navigate(Screen.Shell.route) }
                    )
                }
            }
        }
    }
}

/**
 * Slim status line: tells the user in plain words whether they are online and how many chats are live.
 */
@Composable
fun StatusStrip(p2pNode: P2PNode) {
    val status by p2pNode.nodeStatus.collectAsState()
    val connected = status.activeConnectionsCount

    Surface(
        color = TerminalBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (status.isRunning) TerminalGreenBright else TerminalAmber,
                    modifier = Modifier.size(8.dp)
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (status.isRunning) "Online" else "Starting...",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TerminalTextPrimary
                )
            }
            Text(
                text = when (connected) {
                    0 -> "No one connected"
                    1 -> "1 person connected"
                    else -> "$connected people connected"
                },
                fontSize = 13.sp,
                color = TerminalTextSecondary
            )
        }
    }
}

data class NavigationItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
