package dev.opendroid.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

fun JsonObject.optString(key: String, default: String = ""): String =
    get(key)?.jsonPrimitive?.content ?: default

fun JsonObject.optBoolean(key: String, default: Boolean = false): Boolean =
    get(key)?.jsonPrimitive?.booleanOrNull ?: default

fun JsonObject.optInt(key: String, default: Int): Int =
    get(key)?.jsonPrimitive?.intOrNull ?: get(key)?.jsonPrimitive?.content?.toIntOrNull() ?: default

fun JsonObject.optDouble(key: String, default: Double): Double =
    get(key)?.jsonPrimitive?.doubleOrNull
        ?: get(key)?.jsonPrimitive?.content?.toDoubleOrNull()
        ?: default
