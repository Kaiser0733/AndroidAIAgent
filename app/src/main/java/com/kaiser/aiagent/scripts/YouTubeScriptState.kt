package com.kaiser.aiagent.scripts

/**
 * v0.7: In-memory cache of the last YouTube search results.
 *
 * When youtube_search runs, it stores its parsed results here. When
 * youtube_play is called with an index, it looks up the bounds from
 * here to tap the exact screen coordinates.
 *
 * This is a singleton (object) because the state must persist across
 * separate tool calls within the same agent turn. The state is cleared
 * if the app process dies (which is fine — the user just needs to
 * search again).
 */
object YouTubeScriptState {
    @Volatile
    var lastResults: List<YouTubeResult> = emptyList()
        private set

    @Volatile
    var lastQuery: String = ""
        private set

    fun update(results: List<YouTubeResult>, query: String) {
        lastResults = results
        lastQuery = query
    }

    fun clear() {
        lastResults = emptyList()
        lastQuery = ""
    }

    /** Returns the result at [index], or null if out of bounds. */
    fun get(index: Int): YouTubeResult? {
        return lastResults.getOrNull(index)
    }
}
