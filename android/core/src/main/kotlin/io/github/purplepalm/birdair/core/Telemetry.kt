package io.github.purplepalm.birdair.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.math.abs

/** State of the scooter link, as the phone service sees it. */
@Serializable
enum class Link { IDLE, SCANNING, CONNECTING, CONNECTED, RECONNECTING }

/**
 * What the phone shows and sends to the watch. Values are the last ones decoded (null: never seen);
 * [streaming] says whether they are live (a frame came within [TelemetryTracker.STREAMING_MS]).
 * [currentA] and [powerW]: + from the battery (traction), − into it (regen). [whOut] / [whIn]: energy
 * from / into the battery since the tracker started. [peakW]: the power bar's scale, a rolling max
 * max(prev·0.999, |P|·1.1, 300 W) updated on every new power value. [units]: "kmh" or "mph" for display;
 * speeds and distances are always km/h and km. [extras]: custom decoder fields by name.
 */
@Serializable
data class Telemetry(
    val t: Long = 0,
    val link: Link = Link.IDLE,
    val streaming: Boolean = false,
    val units: String = "kmh",
    val speedKmh: Double? = null,
    val mode: Int? = null,
    val batteryPct: Double? = null,
    val voltage: Double? = null,
    val currentA: Double? = null,
    val powerW: Double? = null,
    val odometerKm: Double? = null,
    val tripKm: Double? = null,
    val avgSpeedKmh: Double? = null,
    val remainingMah: Double? = null,
    val fullMah: Double? = null,
    val temp1C: Double? = null,
    val temp2C: Double? = null,
    val motorOn: Boolean? = null,
    val whOut: Double = 0.0,
    val whIn: Double = 0.0,
    val peakW: Double = 300.0,
    val extras: Map<String, Double> = emptyMap(),
)

/**
 * Notifications in, [Telemetry] out — the page's decodeEntry()/afterTelemetry() for one scooter.
 * With "framing": "colon-sum16" each characteristic gets its own [FrameAssembler] and fields read whole
 * frames; otherwise fields read raw packets (and JSON lines when the decoder says json).
 * Times ([tMs], [snapshot] nowMs) are any one millisecond clock, e.g. ms since the recording started.
 * All methods are synchronized: BLE callbacks and the UI may call from different threads.
 */
class TelemetryTracker(decoder: Decoder) {
    @Volatile var link: Link = Link.IDLE
    @Volatile var units: String = "kmh"

    private var dec: Decoder = decoder
    val decoder: Decoder
        @Synchronized get() = dec

    private val asms = LinkedHashMap<String, FrameAssembler>()
    private val jsonBufs = HashMap<String, String>()
    private val vals = HashMap<String, Double>()
    private val extras = LinkedHashMap<String, Double>()
    private var frameT: Long? = null
    private var dataT: Long? = null
    private var powerTouched = false
    private var lastPT: Long? = null
    private var whOut = 0.0
    private var whIn = 0.0
    private var peakW = PEAK_MIN

    /** Counters summed over the characteristics' assemblers. */
    val frameStats: FrameStats
        @Synchronized get() = asms.values.fold(FrameStats()) { acc, a -> acc + a.stats }

    /** A notification from the scooter (an rx entry of the log). */
    @Synchronized
    fun onNotification(serviceUuid: String, charUuid: String, bytes: ByteArray, tMs: Long) {
        val u = Uuids.full(charUuid)
        val key = Uuids.full(serviceUuid) + "|" + u
        var touched = false
        if (dec.spec.json) touched = decodeJson(key, bytes, tMs)
        // frames are assembled whatever the decoder (the page does the same); fields read them only when framed
        val frames = asms.getOrPut(key) { FrameAssembler() }.feed(bytes)
        if (frames.isNotEmpty()) frameT = tMs
        if (dec.framed) { for (f in frames) touched = apply(f, u, tMs) || touched }
        else touched = apply(bytes, u, tMs) || touched
        if (touched) dataT = tMs
        if (touched) afterTelemetry(tMs)
    }

    /** A value read from a characteristic (an rd entry): raw-packet fields only, read values are not frames. */
    @Synchronized
    fun onRead(serviceUuid: String, charUuid: String, bytes: ByteArray, tMs: Long) {
        val u = Uuids.full(charUuid)
        var touched = false
        if (dec.spec.json) touched = decodeJson(Uuids.full(serviceUuid) + "|" + u, bytes, tMs)
        if (!dec.framed) touched = apply(bytes, u, tMs) || touched
        if (touched) afterTelemetry(tMs)
    }

    /** A new connection (services discovered again): half-received frames are dropped, like the page's
     *  «Найдено сервисов» line. Values and energy stay. */
    @Synchronized
    fun restartStreams() {
        for (a in asms.values) a.reset()
        jsonBufs.clear()
    }

    @Synchronized
    fun snapshot(nowMs: Long): Telemetry {
        val live = maxOf(frameT ?: Long.MIN_VALUE, dataT ?: Long.MIN_VALUE)
        return Telemetry(
            t = nowMs,
            link = link,
            streaming = live != Long.MIN_VALUE && nowMs - live <= STREAMING_MS,
            units = units,
            speedKmh = vals["speed_kmh"],
            mode = vals["mode"]?.toInt(),
            batteryPct = vals["battery_pct"],
            voltage = vals["voltage"],
            currentA = vals["current"],
            powerW = powerNow(),
            odometerKm = vals["odometer_km"],
            tripKm = vals[TRIP],
            avgSpeedKmh = vals[AVG],
            remainingMah = vals[REMAIN],
            fullMah = vals[FULL],
            temp1C = vals["temp"],
            temp2C = vals[TEMP2],
            motorOn = vals[MOTOR]?.let { it != 0.0 },
            whOut = whOut,
            whIn = whIn,
            peakW = peakW,
            extras = LinkedHashMap(extras),
        )
    }

    /** Everything back to the start: values, energy, power scale, assemblers and their counters. */
    @Synchronized
    fun reset() {
        asms.clear(); jsonBufs.clear(); vals.clear(); extras.clear()
        frameT = null; dataT = null; powerTouched = false; lastPT = null
        whOut = 0.0; whIn = 0.0; peakW = PEAK_MIN
    }

    /** Energy counters only (a new trip). */
    @Synchronized
    fun resetEnergy() {
        whOut = 0.0; whIn = 0.0; lastPT = null
    }

    /** Another decoder: values of the old one are dropped; energy, assemblers and counters stay. */
    @Synchronized
    fun setDecoder(d: Decoder) {
        dec = d
        vals.clear(); extras.clear(); jsonBufs.clear()
        powerTouched = false; lastPT = null
    }

    // -- internals

    private fun apply(b: ByteArray, u: String, t: Long): Boolean {
        val got = dec.decode(b, u)
        for ((f, v) in got) set(f, v)
        return got.isNotEmpty()
    }

    private fun set(f: FieldSpec, v: Double) {
        if (!v.isFinite()) return
        when (f.key) {
            "speed_mph" -> put("speed_kmh", v * MILE)
            "odometer_mi" -> put("odometer_km", v * MILE)
            "custom", "error" -> {
                extras[f.name.ifEmpty { if (f.key == "error") "Код ошибки" else "Поле ${f.match} @${f.offset}".replace("  ", " ") }] = v
                CUSTOM[f.name]?.let { put(it, v) }
            }
            else -> put(f.key, v)
        }
    }

    private fun put(key: String, v: Double) {
        vals[key] = v
        if (key == "voltage" || key == "current" || key == "power") powerTouched = true
    }

    private fun powerNow(): Double? {
        vals["power"]?.let { return it }
        val v = vals["voltage"] ?: return null
        val i = vals["current"] ?: return null
        return v * i
    }

    /** The page's afterTelemetry(): integrate power on each new voltage/current/power value, dt clamped to 2 s. */
    private fun afterTelemetry(t: Long) {
        if (!powerTouched) return
        powerTouched = false
        val p = powerNow() ?: return
        if (!p.isFinite()) return
        lastPT?.let {
            val dt = ((t - it) / 1000.0).coerceIn(0.0, MAX_DT_S)
            if (p >= 0) whOut += p * dt / 3600 else whIn += -p * dt / 3600
        }
        lastPT = t
        peakW = maxOf(peakW * PEAK_DECAY, abs(p) * PEAK_HEADROOM, PEAK_MIN)
    }

    private fun decodeJson(key: String, b: ByteArray, t: Long): Boolean {
        val txt = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (e: CharacterCodingException) { return false }
        var buf = (jsonBufs[key] ?: "") + txt
        var touched = false
        while (true) {
            val nl = buf.indexOf('\n')
            if (nl < 0) break
            val line = buf.substring(0, nl).trim()
            buf = buf.substring(nl + 1)
            if (line.startsWith("{")) touched = jsonLine(line) || touched
        }
        val tb = buf.trim()
        if (tb.startsWith("{") && tb.endsWith("}") && jsonLine(tb)) { touched = true; buf = "" }
        if (buf.length > 4000) buf = ""
        jsonBufs[key] = buf
        return touched
    }

    private fun jsonLine(line: String): Boolean {
        val o = try { JSON.parseToJsonElement(line) as? JsonObject } catch (e: Exception) { null } ?: return false
        var any = false
        for ((k, el) in o) {
            val m = JSON_KEYS[k.lowercase()] ?: continue
            val p = el as? JsonPrimitive ?: continue
            val n = p.doubleOrNull ?: p.booleanOrNull?.let { if (it) 1.0 else 0.0 } ?: continue
            if (!n.isFinite()) continue
            when (m) {
                "speed_mph" -> put("speed_kmh", n * MILE)
                "error" -> extras["Код ошибки"] = n
                else -> put(m, n)
            }
            any = true
        }
        return any
    }

    companion object {
        /** A frame (or decoded packet) within this many ms: the data is live. */
        const val STREAMING_MS = 3000L
        const val MAX_DT_S = 2.0
        const val PEAK_MIN = 300.0
        const val PEAK_DECAY = 0.999
        const val PEAK_HEADROOM = 1.1
        private const val MILE = 1.609344

        private const val TRIP = "trip_km"
        private const val AVG = "avg_speed_kmh"
        private const val REMAIN = "remaining_mah"
        private const val FULL = "full_mah"
        private const val TEMP2 = "temp2"
        private const val MOTOR = "motor"

        /** Custom fields of decoder v5 that also fill typed fields. */
        val CUSTOM: Map<String, String> = mapOf(
            "Поездка с включения, км" to TRIP,
            "Средняя скорость с включения, км/ч" to AVG,
            "Остаток в батарее, мА·ч" to REMAIN,
            "Полная ёмкость по BMS, мА·ч" to FULL,
            "Температура 2 (батарея?), °C" to TEMP2,
            "Мотор тянет (бит)" to MOTOR,
        )

        /** JSON-line keys (decoder.json boards), the page's JSON_KEYS. */
        private val JSON_KEYS = mapOf(
            "v" to "voltage", "voltage" to "voltage", "i" to "current", "a" to "current", "current" to "current",
            "p" to "power", "w" to "power", "power" to "power", "soc" to "battery_pct", "bat" to "battery_pct",
            "battery" to "battery_pct", "spd" to "speed_kmh", "speed" to "speed_kmh", "kmh" to "speed_kmh",
            "mph" to "speed_mph", "t" to "temp", "temp" to "temp", "mode" to "mode", "odo" to "odometer_km",
            "err" to "error", "error" to "error",
        )
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
