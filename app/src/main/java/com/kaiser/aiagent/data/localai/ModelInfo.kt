package com.kaiser.aiagent.data.localai

import kotlinx.serialization.Serializable

/**
 * Metadata about a downloadable on-device model.
 *
 * v0.5.3: switched from .task format (MediaPipe) to .litertlm format
 * (LiteRT-LM SDK). The .task files were causing "model file may be
 * corrupted" errors because the litertlm SDK can't load them.
 */
@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val minRamMb: Int,
    val supportsToolCalling: Boolean,
    val fileExtension: String = ".litertlm"
) {
    val sizeHuman: String
        get() = when {
            sizeBytes >= 1_000_000_000 -> "%.1f GB".format(sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000 -> "%.0f MB".format(sizeBytes / 1_000_000.0)
            else -> "$sizeBytes B"
        }
}

object ModelCatalog {

    /**
     * Qwen3-0.6B — smallest model, best for testing on mid-range devices.
     * Only 614 MB download. Runs on 2+ GB RAM. Fastest inference.
     * Use this to verify on-device AI works before downloading a larger model.
     */
    val QWEN3_0_6B = ModelInfo(
        id = "qwen3-0.6b",
        displayName = "Qwen3 0.6B (Smallest — Try This First!)",
        description = "Smallest model. Only 614 MB download. Runs on " +
            "2+ GB RAM. Fastest inference. Good for simple chat and " +
            "testing on-device AI. Limited reasoning capability.",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
        sizeBytes = 614_236_160L,
        minRamMb = 2048,
        supportsToolCalling = false
    )

    /**
     * Qwen3-1.7B — medium model, good balance for mid-range devices.
     * ~2 GB download. Needs 4+ GB RAM. Better reasoning than 0.6B.
     */
    val QWEN3_1_7B = ModelInfo(
        id = "qwen3-1.7b",
        displayName = "Qwen3 1.7B (Balanced)",
        description = "Medium model with good reasoning. ~2 GB download. " +
            "Needs 4+ GB RAM. Better quality than 0.6B, still fast on " +
            "mid-range devices.",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3_1.7B.litertlm",
        sizeBytes = 2_056_729_520L,
        minRamMb = 4096,
        supportsToolCalling = false
    )

    /**
     * Gemma-4-E2B — Google's latest edge model.
     * ~2.4 GB download. Needs 4+ GB RAM. Supports function calling.
     */
    val GEMMA_4_E2B = ModelInfo(
        id = "gemma-4-e2b-it",
        displayName = "Gemma 4 E2B (Best Quality)",
        description = "Google's latest edge-optimized model. ~2.4 GB " +
            "download. Needs 4+ GB RAM. Best quality, supports function " +
            "calling. Download this after confirming the 0.6B model works.",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        minRamMb = 4096,
        supportsToolCalling = true
    )

    /** All models, ordered from smallest to largest. */
    val all: List<ModelInfo> = listOf(QWEN3_0_6B, QWEN3_1_7B, GEMMA_4_E2B)

    /** Recommended model for the current device's RAM. */
    fun recommendedForRam(totalRamMb: Int): ModelInfo = when {
        totalRamMb >= 4096 -> GEMMA_4_E2B
        totalRamMb >= 3072 -> QWEN3_1_7B
        else -> QWEN3_0_6B
    }
}
