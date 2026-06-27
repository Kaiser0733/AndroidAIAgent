package com.kaiser.aiagent.ui.navigation

/**
 * All navigation destinations. v0.3 adds:
 *  - CHAT — the new chat surface.
 *  - DEBUG — hidden debug page (reachable from Settings, not from
 *    the bottom nav).
 */
enum class Destinations(val route: String) {
    LAUNCH("launch"),
    HOME("home"),
    SETTINGS("settings"),
    ABOUT("about"),
    CHAT("chat"),
    DEBUG("debug");

    companion object {
        val startDestination: Destinations = LAUNCH
    }
}
