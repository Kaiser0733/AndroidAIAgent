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
     * Gemma-4-E2B — the recommended model for mid-range devices.
     * 2B effective parameters. Runs on phones with 4+ GB RAM.
     * ~2 GB download. Supports function calling.
     * Public download from HuggingFace litert-community (no auth needed).
     */
    val GEMMA_4_E2B = ModelInfo(
        id = "gemma-4-e2b-it",
        displayName = "Gemma 4 E2B (Recommended)",
        description = "Google's latest edge-optimized model. 2B params, " +
            "runs on 4+ GB RAM phones. ~2 GB download. Best balance of " +
            "quality and speed for mid-range devices. Supports function calling.",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-web.task",
        sizeBytes = 2_003_697_664L,
        minRamMb = 4096,
        supportsToolCalling = true
    )

    /**
     * DeepSeek-R1-Distill-Qwen-1.5B — a reasoning model.
     * 1.5B params. Runs on 3+ GB RAM. ~1.86 GB download.
     * Good at step-by-step reasoning. Public download (no auth needed).
     */
    val DEEPSEEK_R1_1_5B = ModelInfo(
        id = "deepseek-r1-1.5b",
        displayName = "DeepSeek R1 1.5B (Reasoning)",
        description = "DeepSeek R1 distilled to 1.5B. Excels at " +
            "step-by-step reasoning. ~1.86 GB download. Needs 3+ GB RAM. " +
            "Good alternative if Gemma is too slow on your device.",
        downloadUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/deepseek_q8_ekv1280.task",
        sizeBytes = 1_860_686_856L,
        minRamMb = 3072,
        supportsToolCalling = false
    )

    /** All models, ordered from smallest to largest. */
    val all: List<ModelInfo> = listOf(DEEPSEEK_R1_1_5B, GEMMA_4_E2B)

    /** Recommended model for the current device's RAM. */
    fun recommendedForRam(totalRamMb: Int): ModelInfo = when {
        totalRamMb >= 4096 -> GEMMA_4_E2B
        else -> DEEPSEEK_R1_1_5B
    }
}
