package io.github.purplepalm.birdair.wear

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/*
 * What rides along with the phone's telemetry, and what the watch says in its hello. Pure JVM.
 *
 * The phone's screen settings come in the same /birdair/telemetry message. The phone app puts them into
 * Telemetry.extras as 1/0 (ScooterRepo.forWatch: EXTRA_FAST / EXTRA_SMOOTH), so they need no new Wire field:
 *     {"t":…,"link":"CONNECTED",…,"extras":{"fast":1.0,"smooth":1.0}}
 * "fast": the phone's «Быстрая мощность» — the arc and the watts use powerFastW; "smooth": the phone animates
 * its power bar. Also accepted: the same keys at the top level or inside a "flags" / "ui" / "settings" / "watch"
 * object, the aliases in [PhoneFlags.FAST_KEYS] / [PhoneFlags.SMOOTH_KEYS], and true/false, 1/0 or
 * "true"/"false"/"on"/"off" values. They are settings, not readings: the details page does not list them.
 */

/** The phone's «fast» and «smooth» flags; null: this message did not say (keep the last one, see [merge]). */
data class PhoneFlags(val fast: Boolean? = null, val smooth: Boolean? = null) {
    /** This message's flags over the ones before: a message without a flag keeps the old value. */
    fun merge(newer: PhoneFlags): PhoneFlags = PhoneFlags(newer.fast ?: fast, newer.smooth ?: smooth)

    companion object {
        val FAST_KEYS = listOf("fast", "fastPower", "powerFast", "useFastPower")
        val SMOOTH_KEYS = listOf("smooth", "smoothing", "animate")
        /** Where else to look, in this order after the top level; "extras" is what the phone app sends. */
        val NESTED = listOf("extras", "flags", "ui", "settings", "watch")
        /** Flag names, kept out of the details page's custom fields. */
        val KEYS: Set<String> = (FAST_KEYS + SMOOTH_KEYS).toSet()
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Flags of one telemetry message; both null for anything unreadable. */
        fun parse(bytes: ByteArray): PhoneFlags {
            val o = try {
                JSON.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
            } catch (e: Exception) { null } ?: return PhoneFlags()
            var flags = PhoneFlags(find(o, FAST_KEYS), find(o, SMOOTH_KEYS))
            for (k in NESTED) {
                val inner = o[k] as? JsonObject ?: continue
                flags = PhoneFlags(flags.fast ?: find(inner, FAST_KEYS), flags.smooth ?: find(inner, SMOOTH_KEYS))
            }
            return flags
        }

        private fun find(o: JsonObject, keys: List<String>): Boolean? {
            for (k in keys) bool(o[k])?.let { return it }
            return null
        }

        private fun bool(e: JsonElement?): Boolean? {
            val p = e as? JsonPrimitive ?: return null
            if (p.isString) return when (p.content.trim().lowercase()) {
                "true", "on", "1", "yes" -> true
                "false", "off", "0", "no" -> false
                else -> null
            }
            p.booleanOrNull?.let { return it }
            return p.doubleOrNull?.let { if (it.isFinite()) it != 0.0 else null }
        }
    }
}

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
