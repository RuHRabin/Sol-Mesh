package com.example.ui.navigation

sealed class Screen(val route: String, val title: String) {
    object Home : Screen("home", "sol-mesh")
    object Shell : Screen("shell", "terminal")
    object Peers : Screen("peers", "peers")
    object Chat : Screen("chat/{peerId}", "tty") {
        fun createRoute(peerId: String) = "chat/$peerId"
    }
    object Transfers : Screen("transfers", "files")
    object Identity : Screen("identity", "keys")
    object NetworkStatus : Screen("network_status", "circuit")
    object Settings : Screen("settings", "conf")
}
