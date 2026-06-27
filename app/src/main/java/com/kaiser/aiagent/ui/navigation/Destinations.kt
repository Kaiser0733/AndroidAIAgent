package com.kaiser.aiagent.ui.navigation

/**
 * All navigation destinations for v0.1. The enum keeps route constants in
 * one place so screens and the nav graph stay in sync. New destinations
 * added in later versions should be appended here.
 *
 * Note: the route strings are also used by Compose Navigation as the
 * actual navigation keys, so they must be unique and stable.
 */
enum class Destinations(val route: String) {
    LAUNCH("launch"),
    HOME("home"),
    SETTINGS("settings"),
    ABOUT("about");

    companion object {
        val startDestination: Destinations = LAUNCH
    }
}
