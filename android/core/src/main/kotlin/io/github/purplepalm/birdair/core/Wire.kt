package io.github.purplepalm.birdair.core

import kotlinx.serialization.json.Json

/**
 * Phone ⇄ watch over the Wear Data Layer (MessageClient): [PATH] carries [Telemetry] as compact JSON,
 * defaults left out; [HELLO] is the watch saying it is on screen and wants data; [FACE] the dial face (DataClient).
 * Both apps share one applicationId and signing key, which the Data Layer requires.
 */
object Wire {
    const val PATH = "/birdair/telemetry"
    const val HELLO = "/birdair/hello"
    /** The dial face picked on the phone or the watch: a DataItem per node (wear://<node>/birdair/face), the newest wins. */
    const val FACE = "/birdair/face"
    /** Capability names for CapabilityClient, if the apps declare them (res/values/wear.xml). */
    const val CAPABILITY_PHONE = "birdair_phone"
    const val CAPABILITY_WATCH = "birdair_watch"

    private val JSON = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    fun encode(t: Telemetry): ByteArray = JSON.encodeToString(Telemetry.serializer(), t).toByteArray(Charsets.UTF_8)

    /** null for anything that is not a Telemetry object. */
    fun decode(b: ByteArray): Telemetry? = try {
        val s = String(b, Charsets.UTF_8).trim()
        if (!s.startsWith("{")) null else JSON.decodeFromString(Telemetry.serializer(), s)
    } catch (e: Exception) { null }
}
