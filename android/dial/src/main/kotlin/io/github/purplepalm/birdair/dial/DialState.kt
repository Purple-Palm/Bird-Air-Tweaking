package io.github.purplepalm.birdair.dial

import androidx.compose.runtime.Immutable
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.PowerState
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales

/*
 * What a face draws, one value per message from the phone (or per demo frame): [DialState], built by [DialBuilder]. The
 * faces read nothing else. Every time in it (…Ms) is on one clock — the watch's elapsedRealtime, a scenario's own ms —
 * and [DialState.nowMs] is when the state was built; a face's own clock ([rememberFaceClock]) runs on from there.
 */

/**
 * The speed in the phone's unit ([unit]: «км/ч» or «mph»), the other unit beside it; the speed track's end [max] (30 km/h
 * or 20 mph, as the classic dial), the mode's limit and the watch's two cue points in this mode — all in [unit]. [tapAt]
 * / [rumbleAt]: null while that cue is off.
 */
@Immutable
data class DialSpeed(
    val value: Double? = null,
    val other: Double? = null,
    val unit: String = "км/ч",
    val otherUnit: String = "mph",
    val max: Double = Dial.KMH_MAX,
    val mode: Int? = null,
    /** «TAKEOFF», «GLIDE», «FLY». */
    val modeName: String? = null,
    /** TAKEOFF 9 / GLIDE 12,5 / FLY 16 mph, in [unit]; null for no known mode. */
    val limit: Double? = null,
    /** «Тап у ограничителя»: FLY 15,5 mph, × the mode's limit / 16 ([CuePoints]). */
    val tapAt: Double? = null,
    /** «Вибро быстрее ограничителя»: FLY 17,5 mph. */
    val rumbleAt: Double? = null,
) {
    /** [v] as a share of the track, 0…1; null without a value. */
    fun fraction(v: Double? = value): Float? = v?.takeIf { it.isFinite() }?.let { (it / max).coerceIn(0.0, 1.0).toFloat() }

    /** At or past the limiter's tap point (the limiter cuts the current from here). */
    val atLimiter: Boolean get() = value != null && tapAt != null && value >= tapAt
}

/**
 * The power as the arc and the line show it: [watts] the fast estimate when the phone says so ([DialState.fast]), null
 * unless live; [fraction] of the phone's rolling scale [peakW], ±1 (+ traction clockwise, − regen), 0 unless live;
 * [state] the phone's traction / coast / regen, [flow] what the line says, [text] «180 Вт тяга», [argb] its colour
 * (coasting grey, not regen blue).
 */
@Immutable
data class DialPower(
    val watts: Double? = null,
    val fraction: Float = 0f,
    val peakW: Double = 300.0,
    val state: PowerState? = null,
    val flow: Flow = Flow.NONE,
    val text: String = Fmt.watts(null),
    val argb: Long = Palette.MUTED,
)

/**
 * The battery: [pct] as shown (the fine % to 0.1, else the BMS's, else ≈ from the voltage, [estimated]); [text] «56,3 %»
 * (a narrow no-break space); [tone] the fill (the % thresholds, no milder than the charge lamp), [toneArgb] its colour;
 * [charging] current into the pack at standstill (the charging lamp is green); [voltage] and the hotter sensor [tempC].
 */
@Immutable
data class DialBattery(
    val pct: Double? = null,
    val pctFine: Double? = null,
    val text: String = Fmt.DASH + Dial.NNBSP + "%",
    val estimated: Boolean = false,
    val tone: BatteryTone = BatteryTone.NONE,
    val toneArgb: Long = Palette.BASE,
    val charging: Boolean = false,
    val voltage: Double? = null,
    val tempC: Double? = null,
)

/**
 * One lit lamp: [lamp] with its display fields ([Lamp.title], [Lamp.valueText], [Lamp.detail]; an older phone's are
 * derived, [TellTales.display]); [sinceMs] when it lit (from off); [changedMs] when its level or text last changed
 * (= [sinceMs] when it has not); [before] what it was until then (null: it was off); [levelSinceMs] since when it has
 * its level (a new text keeps it) — a red one's 1 Hz pulse runs from here, or from its buzz. In [DialLamps.gone],
 * [changedMs] is when it went out.
 */
@Immutable
data class DialLamp(val lamp: Lamp, val sinceMs: Long, val changedMs: Long = sinceMs, val before: Lamp? = null, val levelSinceMs: Long = changedMs) {
    val id: String get() = lamp.id
    val level: LampLevel get() = lamp.level
    /** Standing conditions and ECO ([TellTales.QUIET]): no text line, never buzz. */
    val quiet: Boolean get() = lamp.id in TellTales.QUIET
    /** It rose (lit, or a stronger level) at [changedMs]: the moment for an entrance and a ripple. */
    val rose: Boolean get() = before == null || lamp.level > before.level
}

/**
 * The lamps: [lit] in [TellTales.IDS] order; [top] the one a face puts first ([TellTales.top]: the strongest level, then
 * the IDS order — the phone's journal and the watch's line name the same lamp); [gone] went out within
 * [DialBuilder.GONE_MS]; [changedMs] when the lit set (ids or levels) last changed, [before] the lit set until then;
 * [message] the classic dial's line (the top lamp's text in its colour, «проверка ламп»).
 */
@Immutable
data class DialLamps(
    val lit: List<DialLamp> = emptyList(),
    val top: DialLamp? = null,
    val gone: List<DialLamp> = emptyList(),
    val changedMs: Long? = null,
    val before: List<Lamp> = emptyList(),
    val message: Dial.Message? = null,
) {
    operator fun get(id: String): DialLamp? = lit.firstOrNull { it.id == id }

    /** The lit lamps that are not [DialLamp.quiet], strongest first (as [top]). */
    val alerts: List<DialLamp> get() = lit.filter { !it.quiet }.sortedWith(SEVERITY)

    /** The quiet ones (service, weak, ECO), in IDS order. */
    val quiet: List<DialLamp> get() = lit.filter { it.quiet }

    val anyRed: Boolean get() = lit.any { it.level == LampLevel.RED }

    companion object {
        /** [TellTales.top]'s order: the level, then the IDS order. */
        val SEVERITY: Comparator<DialLamp> = compareByDescending<DialLamp> { it.level.ordinal }.thenBy { TellTales.IDS.indexOf(it.id) }
    }
}

/** A lamp vibration the watch played: [level] AMBER — one tap ([io.github.purplepalm.birdair.core.LampLevel]), RED — three pulses. */
@Immutable
data class LampBuzz(val atMs: Long, val level: LampLevel)

/**
 * What the watch's motor did, so a face's ripples and the red pulse go with it: [tapAtMs] the last limiter tap (one short
 * vibration), [rumble] the vibration past the limiter runs now (since [rumbleSinceMs]), [buzz] the last lamp vibration,
 * [redAtMs] the last red one (the red pulse's beat, kept when an amber tap follows; null: [buzz] if red).
 */
@Immutable
data class DialCues(
    val tapAtMs: Long? = null,
    val rumble: Boolean = false,
    val rumbleSinceMs: Long? = null,
    val buzz: LampBuzz? = null,
    val redAtMs: Long? = null,
) {
    /** The last red lamp vibration, as far as known. */
    val redBuzzMs: Long? get() = redAtMs ?: buzz?.takeIf { it.level == LampLevel.RED }?.atMs
}

/**
 * Everything a face draws. [telemetry]: the phone's snapshot as it came (the classic face reads it as v108 did; the others
 * use the fields below). [health]: what the watch makes of its data (live, reconnecting, no phone…; [Health.alpha] dims
 * the values), [status] the line where the clock is (null: all live — show [clock]); [ambient] / [lowBit] / [burnInDp]
 * (the pixel shift, dp): always-on, static, outlines; [bulbAtMs] / [bulbOn] the «проверка ламп» ([bulbProgress]);
 * [smooth]: the phone's or the watch's «Плавно»; [fast]: [power] is the fast estimate.
 */
@Immutable
data class DialState(
    val nowMs: Long = 0,
    val telemetry: Telemetry = Telemetry(),
    val units: String = TellTales.KMH,
    val speed: DialSpeed = DialSpeed(),
    val power: DialPower = DialPower(),
    val battery: DialBattery = DialBattery(),
    /** «≈ 4,2 км» / «≈ 2,6 mi» to the observed empty; [rangeRough]: the default consumption, shown dimmer. */
    val range: String? = null,
    val rangeRough: Boolean = false,
    /** «40,2 В · 31 °C · FLY». */
    val details: String = "",
    val lamps: DialLamps = DialLamps(),
    val health: Health = Health.NO_PHONE,
    val status: String? = Health.NO_PHONE.gaugeLabel,
    val ambient: Boolean = false,
    val lowBit: Boolean = false,
    val burnInDp: Pair<Float, Float> = 0f to 0f,
    val bulbAtMs: Long? = null,
    val bulbOn: Boolean = false,
    val cues: DialCues = DialCues(),
    /** «21:41», the watch's time format. */
    val clock: String = "",
    val smooth: Boolean = true,
    val fast: Boolean = true,
) {
    val live: Boolean get() = health.live

    /** How far the bulb check is at [atMs], 0…1; null when none runs. */
    fun bulbProgress(atMs: Long): Float? {
        val at = bulbAtMs ?: return null
        val p = (atMs - at).toFloat() / TellTales.BULB_CHECK_MS
        return if (p < 0f || p >= 1f) null else p
    }
}

/**
 * Makes [DialState]s from what the watch has, and keeps the lamps' history between them ([DialLamp.sinceMs],
 * [DialLamps.gone], [DialLamps.changedMs]): one builder per screen, fed in time order. Pure, not thread-safe.
 */
class DialBuilder(val goneMs: Long = GONE_MS) {
    private var lit: Map<String, DialLamp> = emptyMap()
    private val gone = LinkedHashMap<String, DialLamp>()
    private var setKey: List<Pair<String, LampLevel>> = emptyList()
    private var setAtMs: Long? = null
    private var before: List<Lamp> = emptyList()
    private var shown: List<Lamp> = emptyList()

    /**
     * [t] the phone's last snapshot, [health] as the watch judges it at [nowMs]; [fast] / [smooth] the power choice and
     * «Плавно»; [cues] what the motor did; [tapMph] / [rumbleMph] FLY's points of the two speed cues (null: off);
     * [bulbAtMs] the bulb check's start and [bulbOn] whether it is on; [ambient] / [lowBit] / [burnInDp]; [clock].
     */
    fun build(
        t: Telemetry,
        health: Health,
        nowMs: Long,
        fast: Boolean = true,
        smooth: Boolean = true,
        cues: DialCues = DialCues(),
        tapMph: Double? = CuePoints.TAP_MPH,
        rumbleMph: Double? = CuePoints.RUMBLE_MPH,
        bulbAtMs: Long? = null,
        bulbOn: Boolean = false,
        ambient: Boolean = false,
        lowBit: Boolean = false,
        burnInDp: Pair<Float, Float> = 0f to 0f,
        clock: String = "",
    ): DialState {
        val units = t.units
        val raw = Cluster.lamps(t)
        val lamps = track(raw.map { TellTales.display(it, units) }, nowMs)
        val live = health.live
        val watts = if (live) PowerView.watts(t, fast) else null
        val flow = if (live) Flow.of(t, fast) else Flow.NONE
        val mph = Fmt.mph(units)
        val toUnit = { flyMph: Double -> if (mph) flyMph else flyMph * Fmt.MILE }
        val speed = Fmt.speed(t.speedKmh, units)?.takeIf { it.isFinite() }?.coerceAtLeast(0.0)
        val tone = Dial.batteryTone(t, raw)
        return DialState(
            nowMs = nowMs,
            telemetry = t,
            units = units,
            speed = DialSpeed(
                value = speed,
                other = t.speedKmh?.takeIf { it.isFinite() }?.coerceAtLeast(0.0)?.let { if (mph) it else it / Fmt.MILE },
                unit = Fmt.speedUnit(units),
                otherUnit = Fmt.speedUnit(if (mph) TellTales.KMH else TellTales.MPH),
                max = Dial.speedMax(units),
                mode = t.mode,
                modeName = Modes.name(t.mode),
                limit = if (mph) TellTales.limitMph(t.mode) else TellTales.limitKmh(t.mode),
                tapAt = tapMph?.let { toUnit(CuePoints.tap(it, t.mode).onMph) },
                rumbleAt = rumbleMph?.let { toUnit(CuePoints.rumble(it, t.mode).onMph) },
            ),
            power = DialPower(
                watts = watts,
                fraction = if (live) Arc.fraction(watts, t.peakW) else 0f,
                peakW = t.peakW,
                state = t.powerState,
                flow = flow,
                text = Dial.powerText(watts, flow),
                argb = Dial.powerArgb(watts, flow),
            ),
            battery = DialBattery(
                pct = Dial.pct(t),
                pctFine = t.batteryPctFine,
                text = Dial.pctText(t),
                estimated = t.batteryPctFine == null && Battery.level(t)?.estimated == true,
                tone = tone,
                toneArgb = Dial.toneArgb(tone),
                charging = raw.any { it.id == TellTales.CHARGING && it.level == LampLevel.GREEN },
                voltage = t.voltage,
                tempC = Dial.hotterC(t),
            ),
            range = Dial.rangeText(t.range, units),
            rangeRough = RangeText.rough(t.range),
            details = Dial.detailsText(t),
            lamps = lamps.copy(message = Dial.message(raw, bulbOn && !ambient)),
            health = health,
            status = Cluster.statusLabel(health, raw),
            ambient = ambient,
            lowBit = lowBit,
            burnInDp = burnInDp,
            bulbAtMs = bulbAtMs,
            bulbOn = bulbOn,
            cues = cues,
            clock = clock,
            smooth = smooth,
            fast = fast,
        )
    }

    /** The lamps' history: what lit, changed and went out at [nowMs]. */
    private fun track(now: List<Lamp>, nowMs: Long): DialLamps {
        val next = LinkedHashMap<String, DialLamp>()
        for (l in now) {
            val p = lit[l.id]
            next[l.id] = when {
                p == null -> DialLamp(l, nowMs).also { gone.remove(l.id) }
                p.lamp.level != l.level -> DialLamp(l, p.sinceMs, nowMs, p.lamp)
                p.lamp.text != l.text -> DialLamp(l, p.sinceMs, nowMs, p.lamp, p.levelSinceMs)
                else -> p.copy(lamp = l)
            }
        }
        for ((id, p) in lit) if (id !in next) gone[id] = p.copy(changedMs = nowMs)
        gone.values.removeAll { nowMs - it.changedMs > goneMs || nowMs < it.changedMs }
        lit = next
        val key = now.map { it.id to it.level }
        if (key != setKey) {
            if (setAtMs != null || key.isNotEmpty()) { before = shown; setAtMs = nowMs }
            setKey = key
        }
        shown = now
        val list = next.values.toList()
        val top = TellTales.top(now)?.let { t -> list.firstOrNull { it.id == t.id } }
        return DialLamps(list, top, gone.values.toList(), setAtMs, before)
    }

    companion object {
        /** A lamp that went out stays in [DialLamps.gone] this long, for its exit. */
        const val GONE_MS = 1_500L
    }
}
