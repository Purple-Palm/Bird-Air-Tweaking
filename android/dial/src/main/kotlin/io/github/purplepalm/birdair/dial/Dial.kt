package io.github.purplepalm.birdair.dial

import io.github.purplepalm.birdair.core.BatteryAdvisor
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.core.WarningLevel
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The dial (the approved mock3.html): geometry, texts, lamp colours and glyphs, blink and bulb-check timing. Pure, so
 * the JVM tests check it. Units: the 240-unit design grid, the mock's 480 px drawing halved. Angles are clock angles
 * (0° at 12 o'clock, clockwise); Compose's (0° at 3 o'clock) only through [Dial.composeDeg].
 */

object Dial {
    const val C = 120f
    const val GRID = 240f

    // outer power arc
    const val POWER_R = 111f
    const val POWER_W = 10f
    const val ZERO_TICK_IN = 103f
    const val ZERO_TICK_OUT = 117f
    const val ZERO_TICK_W = 1.5f

    // inner speed track: ±135° like the power arc
    const val START_DEG = -135f
    const val SPAN_DEG = 270f
    const val SPEED_R = 97.5f
    const val SPEED_W = 1.5f
    const val TICK_HALF = 3f
    const val TICK_W = 1f
    const val LIMIT_HALF = 4.5f
    const val LIMIT_W = 1.5f
    const val DOT_R = 4.5f
    const val DOT_FONT = 5f
    const val KMH_MAX = 30.0
    const val MPH_MAX = 20.0
    const val TICK_EVERY = 5.0

    // 12 lamps, two columns: IDS 0–5 on the right top to bottom, 6–11 on the left
    const val LAMP_R = 82.5f
    const val FIRST_DEG = 40f
    const val STEP_DEG = 20f
    const val ICON = 13f
    const val GLOW_R = 9.5f
    const val GLOW_ALPHA = 0.16f

    // centre battery (body, nub, fill, % text centre and baseline)
    const val BODY_X = 81f; const val BODY_Y = 80f; const val BODY_W = 70f; const val BODY_H = 33f; const val BODY_RX = 6f
    const val BODY_LINE = 2f
    const val NUB_X = 152f; const val NUB_Y = 89.5f; const val NUB_W = 6f; const val NUB_H = 14f; const val NUB_RX = 2f
    const val FILL_X = 85f; const val FILL_Y = 84f; const val FILL_W = 62f; const val FILL_H = 25f; const val FILL_RX = 3.5f
    const val FILL_MIN = 3f
    const val FILL_ALPHA = 0.9f
    const val PCT_X = 116f
    const val PCT_Y = 103.5f
    const val PCT_FONT = 20f
    const val PCT_HALO = 2.5f
    /** The % text is shrunk to fit this wide (the fill is 62). */
    const val PCT_MAX_W = 64f

    // text baselines and sizes
    const val CLOCK_Y = 61f; const val CLOCK_FONT = 10f
    /** The status label (two lines at most) is centred here, where the clock's digits are. */
    const val STATUS_Y = 57f; const val STATUS_FONT = 9.5f; const val STATUS_MAX_W = 90f
    const val RANGE_Y = 131f; const val RANGE_FONT = 13f
    const val DETAILS_Y = 145f; const val DETAILS_FONT = 9.5f
    const val MSG_Y = 163f; const val MSG_FONT = 9.5f; const val MSG_MIN_FONT = 8f; const val MSG_MAX_W = 124f
    const val POWER_Y = 176f
    /** Without a lamp line the power moves up into its place. */
    const val POWER_ALONE_Y = 165f
    const val POWER_FONT = 10f

    fun composeDeg(clockDeg: Float): Float = clockDeg - 90f

    /** Point at [r] and clock angle [deg] on the grid. */
    fun point(r: Float, deg: Float): Pair<Float, Float> {
        val a = Math.toRadians(deg.toDouble())
        return (C + r * sin(a)).toFloat() to (C - r * cos(a)).toFloat()
    }

    /** Clock angle of lamp [index] in [TellTales.IDS]: right 40°, 60° … 140°, left −40° … −140°. */
    fun lampDeg(index: Int): Float {
        require(index in TellTales.IDS.indices)
        val j = index % 6
        val a = FIRST_DEG + STEP_DEG * j
        return if (index < 6) a else -a
    }

    fun lampCentre(index: Int): Pair<Float, Float> = point(LAMP_R, lampDeg(index))

    fun speedMax(units: String): Double = if (Fmt.mph(units)) MPH_MAX else KMH_MAX

    /** Share of the speed track, clamped to 0…1; null without a speed. */
    fun speedFraction(kmh: Double?, units: String): Float? {
        val v = Fmt.speed(kmh, units)?.takeIf { it.isFinite() } ?: return null
        return (v / speedMax(units)).coerceIn(0.0, 1.0).toFloat()
    }

    fun speedDeg(fraction: Float): Float = START_DEG + SPAN_DEG * fraction.coerceIn(0f, 1f)

    /** The red tick: the mode's limit (TAKEOFF 9 / GLIDE 12,5 / FLY 16 mph) on the track; null for no known mode. */
    fun limitFraction(mode: Int?, units: String): Float? {
        val lim = if (Fmt.mph(units)) TellTales.limitMph(mode) else TellTales.limitKmh(mode)
        return lim?.let { (it / speedMax(units)).coerceIn(0.0, 1.0).toFloat() }
    }

    /** The small ticks, every [TICK_EVERY] units: 0, 5 … 30 km/h or 0 … 20 mph. */
    fun ticks(units: String): List<Float> {
        val max = speedMax(units)
        val n = (max / TICK_EVERY).roundToInt()
        return (0..n).map { (it * TICK_EVERY / max).toFloat() }
    }

    /** The number on the dot: the speed in the phone's units, whole. */
    fun dotLabel(kmh: Double?, units: String): String? =
        Fmt.speed(kmh, units)?.takeIf { it.isFinite() }?.let { maxOf(0.0, it).roundToInt().toString() }

    /** Battery shown: the fine BMS % (one decimal), else the whole one, else estimated from the voltage. */
    fun pct(t: Telemetry): Double? =
        t.batteryPctFine?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: Battery.level(t)?.pct

    /** «56,3 %» (floored like the BMS floors), «100 %», «54 %», «≈50 %» from the voltage, «— %». */
    fun pctText(t: Telemetry): String {
        val fine = t.batteryPctFine?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
        if (fine != null) {
            val tenths = floor(fine * 10 + 1e-6) / 10
            return (if (tenths >= 100) "100" else Fmt.num(tenths, 1)) + NNBSP + "%"
        }
        val l = Battery.level(t) ?: return Fmt.DASH + NNBSP + "%"
        return (if (l.estimated) "≈" else "") + Fmt.num(l.pct, 0) + NNBSP + "%"
    }

    /** Width of the fill: [FILL_W] at 100 %, never under [FILL_MIN] (an empty battery still shows its red). */
    fun fillWidth(pct: Double?): Float =
        if (pct == null || !pct.isFinite()) 0f else maxOf(FILL_MIN, (FILL_W * pct.coerceIn(0.0, 100.0) / 100).toFloat())

    /** The fill's colour: the % thresholds, but no milder than the battery lamp and the phone's low warning. */
    fun batteryTone(t: Telemetry, lamps: List<Lamp>): BatteryTone {
        val byWarning = Warnings.batteryTone(pct(t), t.warnings)
        val byLamp = when (lamps.firstOrNull { it.id == TellTales.BATTERY }?.level) {
            LampLevel.RED -> BatteryTone.CRIT
            LampLevel.AMBER -> BatteryTone.WARN
            else -> BatteryTone.NONE
        }
        return if (rank(byLamp) > rank(byWarning)) byLamp else byWarning
    }

    private fun rank(t: BatteryTone): Int = when (t) { BatteryTone.NONE -> 0; BatteryTone.GOOD -> 1; BatteryTone.WARN -> 2; BatteryTone.CRIT -> 3 }

    fun toneArgb(t: BatteryTone): Long = when (t) {
        BatteryTone.GOOD -> Palette.LAMP_GREEN
        BatteryTone.WARN -> Palette.LAMP_AMBER
        BatteryTone.CRIT -> Palette.LAMP_RED
        BatteryTone.NONE -> Palette.BASE
    }

    /** «≈ 4,2 км» / «≈ 2,6 mi» to the observed empty; null without a range. */
    fun rangeText(r: RangeEstimate?, units: String): String? {
        if (r == null) return null
        val d = if (Fmt.mph(units)) r.mi else r.km
        if (!d.isFinite() || d < 0) return null
        return "≈" + NBSP + Fmt.withUnit(d, if (d < 10) 1 else 0, Fmt.distUnit(units))
    }

    /** The hotter of the two battery sensors (as the temperature lamp judges). */
    fun hotterC(t: Telemetry): Double? = listOfNotNull(t.temp1C, t.temp2C).filter { it.isFinite() }.maxOrNull()

    /** «40,2 В · 31 °C · FLY»; what is unknown is left out. */
    fun detailsText(t: Telemetry): String = listOfNotNull(
        t.voltage?.takeIf { it.isFinite() }?.let { Fmt.withUnit(it, 1, "В") },
        hotterC(t)?.let { Fmt.withUnit(it, 0, "°C") },
        Modes.name(t.mode),
    ).joinToString(" · ")

    /** «180 Вт тяга», «150 Вт рекуп.», «3 Вт накат»; standing still, the watts alone. */
    fun powerText(p: Double?, flow: Flow): String {
        val label = when (flow) {
            Flow.DRIVE -> "тяга"
            Flow.COAST -> "накат"
            Flow.REGEN -> "рекуп."
            Flow.CHARGE -> "зарядка"
            Flow.INTO -> "в батарею"
            Flow.IDLE, Flow.NONE -> ""
        }
        return Fmt.watts(p) + (if (label.isEmpty()) "" else NBSP + label)
    }

    /** The watts' colour: their sign, but coasting is grey — the −0.2 A of a released throttle is not regen. */
    fun powerArgb(p: Double?, flow: Flow = Flow.NONE): Long = when {
        flow == Flow.COAST -> Flow.COAST.tone
        p == null || !p.isFinite() || p == 0.0 -> Palette.MUTED
        p > 0 -> Palette.DRAW
        else -> Palette.FLOW
    }

    /**
     * The lamp line's font: [MSG_FONT], smaller (down to [MSG_MIN_FONT]) for a text [width] wide at it that would not
     * fit [maxW] («Руль на связи, BMS молчит»); what still does not fit is ellipsized.
     */
    fun msgFont(width: Float, maxW: Float): Float =
        if (width <= maxW || width <= 0f) MSG_FONT else maxOf(MSG_MIN_FONT, MSG_FONT * maxW / width * MSG_FIT)

    /** Text widths do not scale exactly with the size (hinting, rounding): a little room to spare. */
    const val MSG_FIT = 0.97f

    /** The text line under the details: the top lamp's, «проверка ламп» during the check. */
    data class Message(val text: String, val argb: Long)

    fun message(lamps: List<Lamp>, bulb: Boolean): Message? {
        if (bulb) return Message(BULB_TEXT, Palette.MUTED)
        val top = TellTales.top(lamps) ?: return null
        if (top.text.isBlank()) return null
        return Message(top.text, Lamps.argb(top.level))
    }

    const val BULB_TEXT = "проверка ламп"
    private const val NBSP = '\u00A0'
    /** Narrow no-break space: «56,3 %» must fit in the battery. */
    const val NNBSP = '\u202F'
}

/** Lamp colours (mock3.html) and what a lamp shows at a moment. */
object Lamps {
    fun argb(level: LampLevel): Long = when (level) {
        LampLevel.OFF -> Palette.LAMP_OFF
        LampLevel.GREEN -> Palette.LAMP_GREEN
        LampLevel.BLUE -> Palette.LAMP_BLUE
        LampLevel.YELLOW -> Palette.LAMP_YELLOW
        LampLevel.AMBER -> Palette.LAMP_AMBER
        LampLevel.RED -> Palette.LAMP_RED
    }

    /** RED lamps blink (not in ambient, not in the bulb check); a blinking lamp in its dark half is drawn off. */
    fun blinks(level: LampLevel): Boolean = level == LampLevel.RED

    fun shown(level: LampLevel, blinkOn: Boolean, ambient: Boolean, bulb: Boolean = false): LampLevel =
        if (blinks(level) && !blinkOn && !ambient && !bulb) LampLevel.OFF else level
}

/** ~1 Hz: [HALF_MS] lit, [HALF_MS] dark, starting lit when a red lamp appears. */
object Blink {
    const val HALF_MS = 500L

    fun on(sinceMs: Long): Boolean = Math.floorMod(sinceMs / HALF_MS, 2L) == 0L
}

/**
 * «Проверка ламп»: all 12 lit for [TellTales.BULB_CHECK_MS] when the gauge opens and each time the phone's link to the
 * scooter turns CONNECTED again. [onFeed] sees every message; the first one only sets the link (the opening check
 * already covers it).
 */
class BulbCheck(val durationMs: Long = TellTales.BULB_CHECK_MS) {
    private var link: Link? = null
    private var startedAtMs: Long? = null

    fun start(nowMs: Long) { startedAtMs = nowMs }

    /** A message (rxAtMs null: the feed has none yet); true when it starts a check. */
    fun onFeed(rxAtMs: Long?, l: Link, nowMs: Long): Boolean {
        if (rxAtMs == null) return false
        val before = link
        link = l
        if (l == Link.CONNECTED && before != null && before != Link.CONNECTED) { start(nowMs); return true }
        return false
    }

    fun active(nowMs: Long): Boolean = startedAtMs?.let { nowMs - it in 0 until durationMs } ?: false

    /** Until the check ends, ms (0 when not on). */
    fun leftMs(nowMs: Long): Long = startedAtMs?.let { (it + durationMs - nowMs).coerceAtLeast(0) } ?: 0
}

/** The phone's lamps; a phone without them (an older build) has only its battery warnings, which stand in. */
object Cluster {
    fun lamps(t: Telemetry): List<Lamp> {
        if (t.lamps.isNotEmpty() || t.warnings.isEmpty()) return t.lamps
        val by = HashMap<String, Lamp>()
        for (w in t.warnings) {
            val id = when (w.id) { BatteryAdvisor.LOW -> TellTales.BATTERY; BatteryAdvisor.HOT -> TellTales.TEMP; else -> continue }
            val red = w.level == WarningLevel.CRITICAL
            val text = when {
                id == TellTales.TEMP && red -> "Перегрев — сбавь газ"
                id == TellTales.TEMP -> "Батарея горячая"
                else -> Warnings.text(w)
            }
            val lamp = Lamp(id, if (red) LampLevel.RED else LampLevel.AMBER, text, w.value)
            if ((by[id]?.level?.ordinal ?: -1) < lamp.level.ordinal) by[id] = lamp
        }
        return TellTales.IDS.mapNotNull { by[it] }
    }

    /** The phone's own bulb check, if it ever sends one: not a real state, nothing to buzz about. */
    fun isBulbCheck(lamps: List<Lamp>): Boolean = lamps.size == TellTales.IDS.size && lamps == TellTales.bulbCheck()

    /** The phone's texts the watch tells apart (TellTales; the 20.10 replay in SpeedCueLogTest checks them). The
     *  battery: the BMS raised its under-voltage flag (the lamp's value: the voltage then) … */
    const val BMS_CUT = "BMS отключает батарею"
    /** … the controller says the BMS is gone (code 0x26); the link: the dash answers, no frame comes. */
    const val NO_BMS = "Нет связи с BMS"
    const val DASH_NO_BMS = "Руль на связи, BMS молчит"

    /** The top line for [Health.SILENT] while the dash still answers: it is the BMS that went quiet, not the dash. */
    const val BMS_SILENT = "BMS молчит"

    /** [Health.gaugeLabel], or [BMS_SILENT] for SILENT with the link lamp saying the dash answers (20.10 after the cut). */
    fun statusLabel(h: Health, lamps: List<Lamp>, gauge: Boolean = true): String? =
        if (h == Health.SILENT && lamps.any { it.id == TellTales.LINK && it.text == DASH_NO_BMS }) BMS_SILENT
        else if (gauge) h.gaugeLabel else h.label
}

/**
 * One stroke or fill of a lamp icon on a 24-unit box (SVG path data from mock3.html). [width]: the stroke, in
 * icon units; ambient draws [fill] parts as outlines too; [knock]: drawn in the background's colour (a cut-out).
 */
data class Glyph(
    val d: String, val fill: Boolean = false, val width: Float = 2f, val roundCap: Boolean = false, val roundJoin: Boolean = false,
    val knock: Boolean = false,
)

object Glyphs {
    const val BOX = 24f

    private fun circle(cx: Float, cy: Float, r: Float): String =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    val BY_ID: Map<String, List<Glyph>> = mapOf(
        TellTales.TEMP to listOf(
            Glyph("M9.5 4a2.5 2.5 0 0 1 5 0v9.2a4.5 4.5 0 1 1-5 0z"),
            Glyph(circle(12f, 17f, 2.2f), fill = true),
            Glyph("M12 15V8"),
        ),
        TellTales.BATTERY to listOf(
            Glyph("M5 7h13a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z"),
            Glyph("M20.5 11a1 1 0 0 1 2 0v2a1 1 0 0 1-2 0z", fill = true),
            Glyph("M5.5 9.5h5v5h-5z", fill = true),
        ),
        TellTales.TRACTION to listOf(
            Glyph(circle(12f, 12f, 7.5f)),
            Glyph(circle(12f, 12f, 2f), fill = true),
            Glyph("M12 4.5v3M12 16.5v3M4.5 12h3M16.5 12h3"),
        ),
        TellTales.CONSUMPTION to listOf(
            Glyph("M12 3c3 4 6 7 6 11a6 6 0 0 1-12 0c0-4 3-7 6-11z"),
            Glyph("M12 10v6M9.5 13.5l2.5 2.5 2.5-2.5", width = 1.8f),
        ),
        TellTales.WEAK to listOf(
            Glyph("M3 5l5 7 4-3 9 10", width = 2.2f, roundCap = true, roundJoin = true),
            Glyph("M16 19h5v-5"),
        ),
        TellTales.OVERVOLT to listOf(Glyph("M13 2L5 13h6l-1 9 8-11h-6z", fill = true)),
        TellTales.ECO to listOf(Glyph("M5 19c0-9 6-14 15-14 0 9-5 15-14 15M5 19l8-8", roundCap = true)),
        TellTales.SERVICE to listOf(
            Glyph("M14.5 3.5a5 5 0 0 0-6 6.5L3 15.5 5.5 18 11 12.5a5 5 0 0 0 6.5-6l-3 3-2.5-.5-.5-2.5z", roundJoin = true),
        ),
        TellTales.LINK to listOf(Glyph("M7 7l10 10-5 4V3l5 4L7 17", roundJoin = true)),
        TellTales.OVERSPEED to listOf(
            Glyph("M4 17a8 8 0 1 1 16 0"),
            Glyph("M12 17l5-6", width = 2.2f, roundCap = true),
        ),
        TellTales.CHARGING to listOf(Glyph("M8 3v5M16 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4", roundCap = true)),
        TellTales.COLD to listOf(Glyph("M12 2v20M3.3 7l17.4 10M3.3 17L20.7 7", roundCap = true)),
    )

    private const val BAT_BODY = "M5 7h13a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z"
    private const val BAT_NUB = "M20.5 11a1 1 0 0 1 2 0v2a1 1 0 0 1-2 0z"

    /** «BMS отключает батарею» / «Нет связи с BMS»: the battery struck through (the galleries; [FaceKit.icon]). */
    val BATTERY_CUT: List<Glyph> = listOf(Glyph(BAT_BODY), Glyph(BAT_NUB, fill = true), Glyph("M3.5 3.5L19.5 20.5", width = 2.3f, roundCap = true))

    /** «Сейчас отключится»: the battery with its last division only (grow). */
    val BATTERY_LOW: List<Glyph> = listOf(Glyph(BAT_BODY), Glyph(BAT_NUB, fill = true), Glyph("M5.5 9.5h3v5h-3z", fill = true))

    /** «Мало тяги» as grow draws it: a turtle (its shell's seams knocked out). */
    val TURTLE: List<Glyph> = listOf(
        Glyph("M3.5 15.5C3.5 10 7 7 11.5 7S19.5 10 19.5 15.5z", fill = true),
        Glyph(circle(21f, 12.6f, 1.9f), fill = true),
        Glyph("M5.5 15.5h3v3.2h-3zM14.5 15.5h3v3.2h-3z", fill = true),
        Glyph("M3.6 15.5l-1.8 1.6", width = 1.6f, roundCap = true),
        Glyph("M6.5 12.5h10M11.5 7.5v5", width = 1.3f, knock = true),
    )

    /** The ▲ «быстро греется» right of the temperature lamp, as offsets on the grid from the lamp's centre. */
    val RISING: List<Pair<Float, Float>> = listOf(7f to -2f, 9.5f to -5.5f, 12f to -2f)
}
