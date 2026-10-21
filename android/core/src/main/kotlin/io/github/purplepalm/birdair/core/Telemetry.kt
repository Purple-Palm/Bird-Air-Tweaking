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
 *
 * From the controller frames themselves (3A 18 20 10 / 3A 17 00 0D on FFE1, whatever the decoder):
 * [currentFastA] / [powerFastW] the battery current and power from the voltage sag ([FastPower]), fresh on every
 * BMS frame where [currentA] refreshes ~1/s and lags ~0.6 s; [powerAtMs] the frame time (the tracker's clock) of
 * the latest power value; [warnings] low battery / overheat ([BatteryAdvisor]), CRITICAL first; [range] to the
 * observed empty; [appOdometerKm] the app's own odometer ([AppOdometer]); [lamps] the cluster's tell-tales
 * ([TellTales]); [batteryPctFine] the charge to 0.1 %; [powerState] traction / coasting / regen for the power line
 * ([PowerStateTracker]: the fast current with a deadband, so coasting at −0.2 A is not «рекуп.»). [seq] grows on
 * every notification that brought a new value, so the phone can publish on change instead of on a timer.
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
    val currentFastA: Double? = null,
    val powerFastW: Double? = null,
    val powerAtMs: Long? = null,
    val seq: Long = 0,
    val warnings: List<BatteryWarning> = emptyList(),
    val range: RangeEstimate? = null,
    val appOdometerKm: Double? = null,
    /** The lit tell-tales in [TellTales.IDS] order; an id not listed is off. */
    val lamps: List<Lamp> = emptyList(),
    /** 100 · remaining / full mAh of the last BMS frame, floored to 0.1 % (the BMS's own [batteryPct] floors to 1 %). */
    val batteryPctFine: Double? = null,
    /** Null until the first BMS frame of a stream; an older watch ignores it. */
    val powerState: PowerState? = null,
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
    /** tMs + this = the phone's wall clock (epoch ms), for the app odometer's checks; null: unknown. */
    @Volatile var epochOffsetMs: Long? = null

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
    private val fast = FastPower()
    private val advisor = BatteryAdvisor()
    private val tells = TellTales()
    private val flow = PowerStateTracker()
    private var frameSpeed = 0.0
    private var odometer = AppOdometer()
    private var fastSample: FastPower.Sample? = null
    private var powerAt: Long? = null
    private var seqN = 0L

    /** The app odometer's state, for the app to keep (null until the first frame) and to give back after a restart.
     *  Setting it mid-session (a correction) changes the total only: the range estimate goes on with its own distance. */
    var odometerState: AppOdometer.State?
        @Synchronized get() = odometer.state
        @Synchronized set(v) { odometer = AppOdometer(v) }

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
        var scooter = false
        if (u == Uuids.CHAR) for (f in frames) scooter = scooterFrame(f, tMs) || scooter
        if (u == Uuids.CHAR && frames.isEmpty() && bytes.isNotEmpty()) tells.onDash(tMs)
        if (dec.framed) { for (f in frames) touched = apply(f, u, tMs) || touched }
        else touched = apply(bytes, u, tMs) || touched
        if (touched) dataT = tMs
        if (touched) afterTelemetry(tMs)
        if (touched || scooter) seqN++
    }

    /** A value read from a characteristic (an rd entry): raw-packet fields only, read values are not frames. */
    @Synchronized
    fun onRead(serviceUuid: String, charUuid: String, bytes: ByteArray, tMs: Long) {
        val u = Uuids.full(charUuid)
        var touched = false
        if (dec.spec.json) touched = decodeJson(Uuids.full(serviceUuid) + "|" + u, bytes, tMs)
        if (!dec.framed) touched = apply(bytes, u, tMs) || touched
        if (touched) afterTelemetry(tMs)
        if (touched) seqN++
    }

    /** A new connection (services discovered again): half-received frames are dropped, like the page's
     *  «Найдено сервисов» line. Values and energy stay. */
    @Synchronized
    fun restartStreams() {
        for (a in asms.values) a.reset()
        jsonBufs.clear()
        fast.reset()
        flow.reset()
        tells.restartStreams()
    }

    @Synchronized
    fun snapshot(nowMs: Long): Telemetry {
        val live = maxOf(frameT ?: Long.MIN_VALUE, dataT ?: Long.MIN_VALUE)
        val streaming = live != Long.MIN_VALUE && nowMs - live <= STREAMING_MS
        val warns = advisor.warnings()
        val range = advisor.range()
        return Telemetry(
            t = nowMs,
            link = link,
            streaming = streaming,
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
            currentFastA = fastSample?.currentA,
            powerFastW = fastSample?.powerW,
            powerAtMs = powerAt,
            seq = seqN,
            warnings = warns,
            range = range,
            appOdometerKm = odometer.totalKm,
            lamps = tells.lamps(nowMs, range, warns, link, streaming),
            batteryPctFine = tells.batteryPctFine,
            powerState = flow.state,
        )
    }

    /** Everything back to the start: values, energy, power scale, assemblers and their counters, the fast power
     *  estimate, the battery advisor and the tell-tales. Not the app odometer ([odometerState]) and not [Telemetry.seq], which only grows. */
    @Synchronized
    fun reset() {
        asms.clear(); jsonBufs.clear(); vals.clear(); extras.clear()
        frameT = null; dataT = null; powerTouched = false; lastPT = null
        whOut = 0.0; whIn = 0.0; peakW = PEAK_MIN
        fast.reset(full = true); fastSample = null; powerAt = null
        flow.reset(); frameSpeed = 0.0
        advisor.reset()
        tells.reset()
        seqN++
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
                role(f)?.let { put(it, v) }
            }
            else -> put(f.key, v)
        }
    }

    private fun put(key: String, v: Double) {
        vals[key] = v
        if (key == "voltage" || key == "current" || key == "power") powerTouched = true
    }

    /** FastPower, BatteryAdvisor and AppOdometer read the controller frames directly (verified offsets, see ScooterFrames). */
    private fun scooterFrame(f: ByteArray, t: Long): Boolean {
        val b = ScooterFrames.bms(f)
        if (b != null) {
            fastSample = fast.update(t, b.voltage, b.currentA, b.motor)
            powerAt = t
            flow.update(t, fastSample?.currentA ?: b.currentA, frameSpeed)
            advisor.onBms(t, b.voltage, b.currentA, b.temp1C.toDouble(), b.temp2C.toDouble(), b.remainingMah.toDouble(), b.fullMah.toDouble())
            tells.onBms(t, b, fastSample, fast.r, fast.anchors)
            return true
        }
        val m = ScooterFrames.motion(f) ?: return false
        frameSpeed = m.speedKmh
        tells.onMotion(t, m)
        odometer.update(m.tripKm, m.odometerKm, epochOffsetMs?.let { it + t })
        // the distance ridden on this frame, not the total: «Задать» / «Взять с руля» ([odometerState]) is not a ride
        advisor.onDistance(t, odometer.lastAddedKm)
        return true
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
        if (fastSample == null) powerAt = t
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

        /** Custom fields of decoder v5 that also fill typed fields (DecoderTest checks the names against decoders/current.json). */
        val CUSTOM: Map<String, String> = mapOf(
            "Поездка с включения, км" to TRIP,
            "Средняя скорость с включения, км/ч" to AVG,
            "Остаток в батарее, мА·ч" to REMAIN,
            "Полная ёмкость по BMS, мА·ч" to FULL,
            "Температура 2 (батарея?), °C" to TEMP2,
            "Мотор тянет (бит)" to MOTOR,
        )

        /**
         * The raw status bytes of decoder v6 (custom u8 fields, as they are): the tracker reads them from the frames itself
         * (ScooterFrames: BmsFrame b4 / b5 / b6 / b18, MotionFrame code) into the lamps, so the watch leaves them out of
         * its details. DecoderTest checks the names against decoders/current.json.
         */
        val STATUS: Set<String> = setOf(
            "Статус BMS: байт 4", "Статус BMS: байт 5", "Статус BMS: байт 6", "Счётчик циклов? (байт 18)", "Код контроллера (байт 8)",
        )

        /**
         * The verified ones also by where they sit, so a later decoder that only renames them keeps them. Not the
         * temperatures and the motor bit: their meaning is unchecked, a new name there may well be a new meaning.
         */
        private val CUSTOM_AT: Map<String, String> = mapOf(
            at("3A 17 00 0D", 10, "u16be") to TRIP,
            at("3A 17 00 0D", 5, "u8") to AVG,
            at("3A 18 20 10", 13, "u16le") to REMAIN,
            at("3A 18 20 10", 15, "u16le") to FULL,
        )

        private fun at(match: String, offset: Int, type: String) =
            match.trim().uppercase().split(Regex("\\s+")).joinToString(" ") + "@" + offset + ":" + type

        /** The typed field a custom decoder field fills, if any: by name, else (verified ones) by position. */
        fun role(f: FieldSpec): String? = CUSTOM[f.name] ?: if (f.mask == null) CUSTOM_AT[at(f.match, f.offset, f.type)] else null

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
