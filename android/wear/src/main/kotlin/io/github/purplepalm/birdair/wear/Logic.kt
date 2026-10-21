package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.PowerState
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TelemetryTracker
import io.github.purplepalm.birdair.core.TellTales
import kotlin.math.abs
import kotlin.math.roundToLong

/* Pure logic of the watch screen: no Android, no Compose, so it runs in plain JVM tests. */

/** Colours as ARGB: the page's dark-theme tokens (assets/app.css) plus the ambient greys. */
object Palette {
    const val DRAW = 0xFFD95926     // --draw: energy out of the battery (traction)
    const val FLOW = 0xFF3987E5     // --flow: energy into the battery (regen, charging)
    const val GOOD = 0xFF0CA30C
    const val WARN = 0xFFFAB219
    const val CRIT = 0xFFD03B3B
    const val CRIT_INK = 0xFFF07A7A
    const val INK = 0xFFE8EEEC
    const val INK2 = 0xFFB3BCB8
    const val MUTED = 0xFF8A938F
    const val HAIR = 0xFF252B29
    const val BASE = 0xFF3A413E
    const val MODE_TAKE = 0xFF9AA5A1
    const val MODE_GLIDE = 0xFFF0BF2A
    const val MODE_FLY = 0xFFEF5A5A
    // the dial (mock3.html): tracks, the mode-limit tick, the dot
    const val POWER_TRACK = 0xFF1D2422
    const val SPEED_TRACK = 0xFF262E2B
    const val SPEED_TICK = 0xFF4B5451
    const val LIMIT = 0xFFE3324A
    // lamps: off, then lit by level
    const val LAMP_OFF = 0xFF2C3532
    const val LAMP_GREEN = 0xFF2ECC40
    const val LAMP_BLUE = 0xFF4AA3FF
    const val LAMP_YELLOW = 0xFFFFE14D
    const val LAMP_AMBER = 0xFFFFB020
    const val LAMP_RED = 0xFFFF3B30
    // ambient: greys only, the power arc and lit lamps keep a thin line of their colour
    const val AMB_INK = 0xFFC4C4C4
    const val AMB_DIM = 0xFF6A6A6A
    const val AMB_LINE = 0xFF3C3C3C
}

/** What the watch can say about its data. [label]: the line at the top of the gauge, null when all is live. */
enum class Health(val label: String?) {
    NO_PHONE("нет данных с телефона"),
    IDLE("самокат не подключён"),
    SCANNING("ищу самокат"),
    CONNECTING("подключаюсь"),
    RECONNECTING("переподключаюсь"),
    SILENT("руль молчит"),
    LIVE(null);

    val live: Boolean get() = this == LIVE

    /** The phone is on a ride or about to be: the Ongoing Activity should exist before the wrist goes down. */
    val active: Boolean get() = this != NO_PHONE && this != IDLE

    /** [label] as the gauge shows it: broken where the round top has room. */
    val gaugeLabel: String? get() = if (this == NO_PHONE) "нет данных\nс телефона" else label

    /** Values on screen: full when live, dimmed when old, dimmer still when the phone is gone. */
    val alpha: Float get() = when (this) { LIVE -> 1f; NO_PHONE -> 0.35f; else -> 0.55f }

    /** Colour of [label]. */
    val tone: Long get() = when (this) { LIVE -> Palette.MUTED; NO_PHONE -> Palette.CRIT_INK; else -> Palette.WARN }

    companion object {
        /** No message from the phone for longer than this: the phone (or the watch's link to it) is gone. */
        const val STALE_MS = 3000L
        /** With no ride the phone streams nothing and only answers each [Hello] (every [Hello.RETRY_MS]):
         *  an IDLE answer holds this long, two missed answers, so the top line does not flicker. */
        const val IDLE_STALE_MS = 12_000L

        fun stale(rxAtMs: Long?, nowMs: Long, idle: Boolean = false): Boolean =
            rxAtMs == null || nowMs - rxAtMs > if (idle) IDLE_STALE_MS else STALE_MS

        /** [rxAtMs] and [nowMs]: the watch's own clock (elapsedRealtime) — the phone's [Telemetry.t] is not comparable. */
        fun of(t: Telemetry, rxAtMs: Long?, nowMs: Long): Health = when {
            stale(rxAtMs, nowMs, idle = t.link == Link.IDLE) -> NO_PHONE
            t.link == Link.IDLE -> IDLE
            t.link == Link.SCANNING -> SCANNING
            t.link == Link.CONNECTING -> CONNECTING
            t.link == Link.RECONNECTING -> RECONNECTING
            !t.streaming -> SILENT
            else -> LIVE
        }
    }
}

/**
 * The power bar along the round edge: zero at 12 o'clock, traction clockwise, regen counter-clockwise,
 * ±[MAX_DEG] at the phone's rolling peak ([Telemetry.peakW]). Angles are Compose's: 0° at 3 o'clock, clockwise.
 */
object Arc {
    const val MAX_DEG = 135f
    const val ZERO_DEG = -90f

    /** Signed share of the scale, + traction / − regen, clamped to ±1; 0 when there is no power value. */
    fun fraction(powerW: Double?, peakW: Double): Float {
        if (powerW == null || !powerW.isFinite()) return 0f
        val scale = if (peakW.isFinite() && peakW > 0) peakW else TelemetryTracker.PEAK_MIN
        return (powerW / scale).coerceIn(-1.0, 1.0).toFloat()
    }

    /** Sweep from 12 o'clock: positive clockwise (traction), negative counter-clockwise (regen). */
    fun sweep(fraction: Float): Float = if (fraction.isNaN()) 0f else fraction.coerceIn(-1f, 1f) * MAX_DEG

    /** The bar: [dir] +1 traction / −1 regen; [body] the signed sweep of its straight part from 12 o'clock. */
    data class Bar(val dir: Float, val body: Float)

    /**
     * The bar for [fraction]: square at 12 o'clock, a round tip [capDeg] long (half the band, in degrees) past
     * the body, so the tip lands on the value and nothing shows on the wrong side of zero; a value shorter than
     * the tip is the tip alone. Null when too small to draw.
     */
    fun bar(fraction: Float, capDeg: Float, minDeg: Float = 0.5f): Bar? {
        val s = sweep(fraction)
        if (abs(s) < minDeg) return null
        val dir = if (s > 0) 1f else -1f
        val body = abs(s) - capDeg
        return Bar(dir, if (body > 0) dir * body else 0f)
    }

    /** The faint track behind: the whole ±[MAX_DEG] range. */
    val track: Pair<Float, Float> = (ZERO_DEG - MAX_DEG) to 2 * MAX_DEG

    fun colorFor(powerW: Double?): Long = when {
        powerW == null || !powerW.isFinite() || powerW == 0.0 -> Palette.MUTED
        powerW > 0 -> Palette.DRAW
        else -> Palette.FLOW
    }
}

/** The page's flowState(): what the energy is doing, from current, power and speed; riding, the phone's [PowerState]
 *  (with its deadband: coasting at −0.2 A is «накат», not «рекуперация»). */
enum class Flow(val label: String, val tone: Long) {
    NONE("нет данных", Palette.MUTED),
    IDLE("стоит", Palette.MUTED),
    DRIVE("тяга", Palette.DRAW),
    COAST("накат", Palette.MUTED),
    REGEN("рекуперация", Palette.FLOW),
    CHARGE("зарядка", Palette.FLOW),
    INTO("в батарею", Palette.FLOW);

    companion object {
        /** [fast]: judge by the fast current and power ([PowerView]), as the gauge shows them. */
        fun of(t: Telemetry, fast: Boolean = false): Flow {
            val i = PowerView.amps(t, fast); val spd = t.speedKmh; val p = PowerView.watts(t, fast)
            t.powerState?.let { s ->
                if (spd != null && spd > 1) return when (s) { PowerState.TRACTION -> DRIVE; PowerState.COAST -> COAST; PowerState.REGEN -> REGEN }
            }
            if (i == null && p == null && spd == null) return NONE
            val into = if (i != null) i < -0.15 else (p != null && p < -5)
            if (into) return when { spd != null && spd > 1 -> REGEN; spd != null -> CHARGE; else -> INTO }
            val moving = (spd != null && spd > 1) || (i != null && i > 0.6) || (p != null && p > 20)
            return if (moving) DRIVE else IDLE
        }
    }
}

/** [argb]: the bar's fill, the page's meter colours; [ink]: the % next to it. */
enum class BatteryTone(val argb: Long, val ink: Long) {
    GOOD(Palette.GOOD, Palette.INK),
    WARN(Palette.WARN, Palette.WARN),
    CRIT(Palette.CRIT, Palette.CRIT_INK),
    NONE(Palette.BASE, Palette.INK2),
}

/** Battery % (the BMS's, else estimated from the pack voltage like the page) and its colour. */
object Battery {
    data class Level(val pct: Double, val estimated: Boolean)

    fun level(t: Telemetry): Level? {
        t.batteryPct?.let { if (it.isFinite()) return Level(it.coerceIn(0.0, 100.0), false) }
        val v = t.voltage ?: return null
        if (!v.isFinite() || v <= 0) return null
        return Level(socFromVoltage(v), true)
    }

    /** The page's thresholds: red below 15 %, amber below 30 %, green from 30 %. */
    fun tone(pct: Double?): BatteryTone = when {
        pct == null || pct.isNaN() -> BatteryTone.NONE
        pct < 15 -> BatteryTone.CRIT
        pct < 30 -> BatteryTone.WARN
        else -> BatteryTone.GOOD
    }

    private val SOC = arrayOf(
        3.0 to 0.0, 3.4 to 5.0, 3.55 to 10.0, 3.65 to 20.0, 3.7 to 30.0, 3.75 to 40.0, 3.8 to 50.0,
        3.85 to 60.0, 3.92 to 70.0, 4.0 to 80.0, 4.1 to 90.0, 4.2 to 100.0,
    )

    /** The page's socFromVoltage(): 10S pack, per-cell resting curve. */
    fun socFromVoltage(pack: Double): Double {
        val cell = pack / 10
        if (cell <= SOC[0].first) return 0.0
        if (cell >= 4.2) return 100.0
        for (k in 1 until SOC.size) if (cell <= SOC[k].first) {
            val (v0, s0) = SOC[k - 1]; val (v1, s1) = SOC[k]
            return s0 + (s1 - s0) * (cell - v0) / (v1 - v0)
        }
        return 100.0
    }
}

/** Ride modes of decoder v5 (byte 9 & 3 of 3A 17 00 0D) and the dash's ring colours. */
object Modes {
    fun name(mode: Int?): String? = when (mode) {
        null -> null
        1 -> "TAKEOFF"
        2 -> "GLIDE"
        3 -> "FLY"
        else -> "режим $mode"
    }

    fun argb(mode: Int?): Long = when (mode) {
        1 -> Palette.MODE_TAKE
        2 -> Palette.MODE_GLIDE
        3 -> Palette.MODE_FLY
        else -> Palette.MUTED
    }
}

/** Russian number formatting: decimal comma, no-break space between thousands, "—" for no value. */
object Fmt {
    const val MILE = 1.609344
    const val DASH = "—"
    private const val NBSP = ' '

    fun mph(units: String): Boolean = units == "mph"
    fun speedUnit(units: String): String = if (mph(units)) "mph" else "км/ч"
    fun distUnit(units: String): String = if (mph(units)) "mi" else "км"
    fun speed(kmh: Double?, units: String): Double? = kmh?.let { if (mph(units)) it / MILE else it }
    fun dist(km: Double?, units: String): Double? = km?.let { if (mph(units)) it / MILE else it }

    fun num(v: Double?, digits: Int = 0): String {
        if (v == null || !v.isFinite()) return DASH
        var p = 1L
        repeat(digits) { p *= 10 }
        val scaled = (abs(v) * p).roundToLong()
        val whole = scaled / p
        val neg = v < 0 && scaled != 0L
        val sb = StringBuilder()
        val ws = whole.toString()
        for ((k, c) in ws.withIndex()) {
            if (k > 0 && (ws.length - k) % 3 == 0) sb.append(NBSP)
            sb.append(c)
        }
        if (digits > 0) sb.append(',').append((scaled % p).toString().padStart(digits, '0'))
        return (if (neg) "−" else "") + sb
    }

    /** The big speed: whole part and ",d" tail, e.g. 23.44 → ("23", ",4"); null → ("—", ""). */
    fun speedParts(kmh: Double?, units: String): Pair<String, String> {
        val v = speed(kmh, units)?.takeIf { it.isFinite() } ?: return DASH to ""
        val s = num(abs(v), 1)
        val c = s.lastIndexOf(',')
        return s.substring(0, c) to s.substring(c)
    }

    /** "472 Вт" — the magnitude; the direction is the colour and the [Flow] label. */
    fun watts(p: Double?): String = num(p?.let { abs(it) }, 0) + NBSP + "Вт"

    fun withUnit(v: Double?, digits: Int, unit: String): String = num(v, digits) + NBSP + unit

    /** "54 %", "≈54 %" when estimated from the voltage, "— %" without a value. */
    fun pct(l: Battery.Level?): String =
        (if (l?.estimated == true) "≈" else "") + num(l?.pct, 0) + NBSP + "%"
}

/** Ambient pixel shift: walks a 3×3 grid (in [STEP_DP]) once a minute so no pixel stays lit for long. */
object BurnIn {
    const val STEP_DP = 3f
    private val GRID = arrayOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)

    fun offset(tick: Int): Pair<Float, Float> {
        val (x, y) = GRID[Math.floorMod(tick, GRID.size)]
        return x * STEP_DP to y * STEP_DP
    }
}

/**
 * "Still riding?": the last time the data was live ([Health.LIVE]) decides how long the screen is kept on once the
 * scooter stops streaming; the ride service goes by the phone's session instead ([SessionIdle]).
 */
object Idle {
    /** No live data for this long: the screen may time out; no session on the phone for this long: the ride service stops. */
    const val AFTER_MS = 5 * 60_000L

    /** [sinceMs]: the later of the last live message and the moment the screen was opened. */
    fun expired(lastLiveMs: Long?, sinceMs: Long, nowMs: Long): Boolean =
        nowMs - maxOf(lastLiveMs ?: Long.MIN_VALUE, sinceMs) > AFTER_MS

    fun keepScreenOn(enabled: Boolean, lastLiveMs: Long?, openedAtMs: Long, nowMs: Long): Boolean =
        enabled && !expired(lastLiveMs, openedAtMs, nowMs)
}

/**
 * When the ride service stops: [Idle.AFTER_MS] after the phone last reported a session ([Health.active]: connecting,
 * reconnecting, a silent handlebar or live data), not after the last live data — a stop with the scooter off keeps
 * the service, and with it the overspeed vibration, for the rest of the ride (only the activity, while visible, could
 * start it again). IDLE or no phone for that long: the ride is over.
 */
class SessionIdle(startedAtMs: Long) {
    private var activeAtMs = startedAtMs

    /** Call about once a second with the health at [nowMs]. */
    fun expired(h: Health, nowMs: Long): Boolean {
        if (h.active) activeAtMs = nowMs
        return nowMs - activeAtMs > Idle.AFTER_MS
    }
}

/** When to (re)send [io.github.purplepalm.birdair.core.Wire.HELLO]: soon while nothing comes, rarely while data flows. */
object Hello {
    const val RETRY_MS = 5_000L
    const val EVERY_MS = 30_000L

    fun due(rxAtMs: Long?, lastHelloMs: Long?, nowMs: Long): Boolean {
        if (lastHelloMs == null) return true
        val since = nowMs - lastHelloMs
        return since >= if (Health.stale(rxAtMs, nowMs)) RETRY_MS else EVERY_MS
    }
}

/** Text of the Ongoing Activity / notification: "54 % · 3,2 км" while live, else the state. */
object RideStatus {
    /** Status updates at most this often: each one reposts the notification. */
    const val EVERY_MS = 30_000L

    /** The top lamp's line first (as on the dial): «Перегрев — сбавь газ · 11 % · 5,8 км». */
    fun text(t: Telemetry, h: Health): String {
        if (!h.live) return Cluster.statusLabel(h, Cluster.lamps(t), gauge = false) ?: "Bird Air"
        val parts = ArrayList<String>(3)
        TellTales.top(Cluster.lamps(t))?.text?.takeIf { it.isNotBlank() }?.let { parts += it }
        Battery.level(t)?.let { parts += Fmt.pct(it) }
        t.tripKm?.let { parts += Fmt.withUnit(Fmt.dist(it, t.units), 1, Fmt.distUnit(t.units)) }
        return if (parts.isEmpty()) "едем" else parts.joinToString(" · ")
    }

    /** The top lamp as it changes the status: a new or stronger one is shown at once, not after [EVERY_MS]. */
    fun warningKey(t: Telemetry, h: Health): String? =
        if (!h.live) null else TellTales.top(Cluster.lamps(t))?.let { it.id + ":" + it.level }

    fun due(text: String, shown: String?, key: String?, shownKey: String?, sinceMs: Long): Boolean =
        text != shown && (key != shownKey || sinceMs >= EVERY_MS)
}
