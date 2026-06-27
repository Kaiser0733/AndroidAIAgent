package com.kaiser.aiagent.tools.file

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Shared helpers for file tools — argument parsing, error wrapping, etc.
 */
internal object FileToolHelpers {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Parses raw argument JSON to a JsonObject; returns empty object on failure. */
    fun parseArgs(raw: String): JsonObject {
        return try {
            json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
    }

    /** Extracts a string field, returning null if missing or not a string. */
    fun getString(obj: JsonObject, key: String): String? =
        obj[key]?.let { it as? JsonPrimitive }?.contentOrNull

    /** Extracts a boolean field, defaulting to [default] if missing. */
    fun getBool(obj: JsonObject, key: String, default: Boolean = false): Boolean =
        obj[key]?.let { it as? JsonPrimitive }?.contentOrNull?.toBooleanStrictOrNull() ?: default
}
