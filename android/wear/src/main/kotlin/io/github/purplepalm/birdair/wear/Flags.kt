package io.github.purplepalm.birdair.wear

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * The watch app's version, sent as the payload of every Wire.HELLO (an empty payload before this version), so
 * the phone can show which watch build it talks to: {"app":"wear","versionCode":3,"versionName":"0.3"}.
 */
data class HelloInfo(val versionCode: Int, val versionName: String) {
    fun encode(): ByteArray = buildJsonObject {
        put("app", APP)
        put("versionCode", versionCode)
        put("versionName", versionName)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val APP = "wear"

        /** This build (-PversionCode / -PversionName, a local build is 1 / "dev"). */
        val THIS = HelloInfo(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)

        /** The phone's side: null for an empty or foreign payload (an older watch says hello with no bytes). */
        fun decode(bytes: ByteArray): HelloInfo? {
            if (bytes.isEmpty()) return null
            val o = try { Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject } catch (e: Exception) { null } ?: return null
            val code = (o["versionCode"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: return null
            val name = (o["versionName"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return HelloInfo(code, name)
        }
    }
}
