package com.kaiser.aiagent.scripts

import android.graphics.Rect

/**
 * v0.7: A single YouTube search result parsed from the accessibility tree.
 *
 * The AI sees this as JSON and uses it to decide which video to play.
 * The [bounds] field is captured during parsing so youtube_play can
 * tap the exact screen coordinates — no text matching needed.
 */
data class YouTubeResult(
    val index: Int,
    val title: String,
    val channel: String,
    val views: String,
    val uploaded: String,
    val rawMeta: String,
    /** Screen bounds of the clickable result container. Used by youtube_play to tap by coordinates. */
    val bounds: Rect,
    /** True if the title or meta contains "LIVE", "playlist", etc. */
    val isLive: Boolean = false,
    val isPlaylist: Boolean = false
) {
    /** Renders as a compact JSON-ish string for the AI. */
    fun toAiString(): String {
        val live = if (isLive) " [LIVE]" else ""
        val pl = if (isPlaylist) " [PLAYLIST]" else ""
        return "index=$index title=\"$title\" channel=\"$channel\" views=\"$views\" uploaded=\"$uploaded\"$live$pl"
    }
}
