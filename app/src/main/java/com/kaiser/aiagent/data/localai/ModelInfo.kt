package com.kaiser.aiagent.data.localai

import kotlinx.serialization.Serializable

/**
 * Metadata about a downloadable on-device model.
 *
 * v0.5 ships with a curated list of models from the HuggingFace
 * `litert-community` org — the same source Google's Edge Gallery uses.
 * The user picks one, the ModelManager downloads the .task file, and
 * the LocalAiEngine loads it via LiteRT-LM.
 *
 * Models are ordered from smallest to largest. The mid-range-device
 * recommendation is Gemma-3n-E2B-it (1.6 GB, runs on 4 GB RAM phones).
 */
@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    /** HuggingFace download URL for the .task file. */
    val downloadUrl: String,
    /** Approximate file size in bytes. */
    val sizeBytes: Long,
    /** Minimum RAM (in MB) recommended for smooth inference. */
    val minRamMb: Int,
    /** Whether this model supports native function calling. */
    val supportsToolCalling: Boolean
) {
    val sizeHuman: String
        get() = when {
            sizeBytes >= 1_000_000_000 -> "%.1f GB".format(sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000 -> "%.0f MB".format(sizeBytes / 1_000_000.0)
            else -> "$sizeBytes B"
        }
}

/**
 * Curated catalog of on-device models. Same source as Google's Edge
 * Gallery: HuggingFace `litert-community` org.
 *
 * The .task files are LiteRT (TensorFlow Lite) format — pre-quantized
 * and optimized for mobile inference.
 */
object ModelCatalog {

    /**
     * Gemma-3n-E2B — the recommended model for mid-range devices.
     * 2B effective parameters (1.4B active via Per-Layer Embeddings).
     * Runs on phones with 4+ GB RAM. ~1.6 GB download.
     * Supports function calling.
     */
    val GEMMA_3N_E2B = ModelInfo(
        id = "gemma-3n-e2b-it",
        displayName = "Gemma 3n E2B (Recommended)",
        description = "Google's edge-optimized model. 2B params, runs on " +
            "4+ GB RAM phones. Best balance of quality and speed for " +
            "mid-range devices. Supports function calling.",
        downloadUrl = "https://huggingface.co/litert-community/Gemma-3n-E2B-it/resolve/main/Gemma-3n-E2B-it.task",
        sizeBytes = 1_600_000_000L,
        minRamMb = 4096,
        supportsToolCalling = true
    )

    /**
     * Gemma-3n-E4B — larger variant for high-end devices.
     * 4B effective parameters. Needs 8+ GB RAM. ~3.2 GB download.
     */
    val GEMMA_3N_E4B = ModelInfo(
        id = "gemma-3n-e4b-it",
        displayName = "Gemma 3n E4B (High-end)",
        description = "Larger model with better quality. 4B params, " +
            "needs 8+ GB RAM. ~3.2 GB download. Use only on flagship " +
            "devices (Pixel 8+, Galaxy S24+, etc.).",
        downloadUrl = "https://huggingface.co/litert-community/Gemma-3n-E4B-it/resolve/main/Gemma-3n-E4B-it.task",
        sizeBytes = 3_200_000_000L,
        minRamMb = 8192,
        supportsToolCalling = true
    )

    /**
     * Gemma-3-1B-IT — smallest model, fastest but limited capability.
     * 1B params, runs on 2+ GB RAM. ~800 MB download.
     * Good for simple Q&A; struggles with complex reasoning.
     */
    val GEMMA_3_1B = ModelInfo(
        id = "gemma-3-1b-it",
        displayName = "Gemma 3 1B (Smallest)",
        description = "Smallest model. 1B params, runs on 2+ GB RAM. " +
            "~800 MB download. Fastest inference but limited reasoning " +
            "capability. Use for simple Q&A only.",
        downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT.task",
        sizeBytes = 800_000_000L,
        minRamMb = 2048,
        supportsToolCalling = false
    )

    /** All models, ordered from smallest to largest. */
    val all: List<ModelInfo> = listOf(GEMMA_3_1B, GEMMA_3N_E2B, GEMMA_3N_E4B)

    /** Recommended model for the current device's RAM. */
    fun recommendedForRam(totalRamMb: Int): ModelInfo = when {
        totalRamMb >= 8192 -> GEMMA_3N_E4B
        totalRamMb >= 4096 -> GEMMA_3N_E2B
        else -> GEMMA_3_1B
    }
}
