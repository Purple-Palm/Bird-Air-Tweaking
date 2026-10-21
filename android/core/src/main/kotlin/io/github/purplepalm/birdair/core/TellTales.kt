package io.github.purplepalm.birdair.core

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.roundToLong

/** How a tell-tale is lit, like a car cluster: GREEN/BLUE are states, YELLOW → AMBER → RED rising attention. */
@Serializable
enum class LampLevel { OFF, GREEN, BLUE, YELLOW, AMBER, RED }

/**
 * One lit tell-tale. [text]: a short line for the dial (≤ ~24 chars); [value]: what it is about (°C, V, A, Wh/km,
 * W, %, km/h, Ω — see [TellTales.HELP]), rounded to 0.01; [rising]: the temperature climbs fast (the ▲).
 * [level] has a default only so that a level an older watch does not know reads as OFF instead of breaking the message.
 */
@Serializable
data class Lamp(val id: String, val level: LampLevel = LampLevel.OFF, val text: String = "", val value: Double? = null, val rising: Boolean = false)

/** A lamp that changed level between two snapshots; [lamp] is the new one (null: it went off). */
data class LampChange(val id: String, val from: LampLevel, val to: LampLevel, val lamp: Lamp?)

/**
 * The cluster's tell-tales from the controller frames (ScooterFrames), FastPower, BatteryAdvisor and the link
 * state. Thresholds are tuned on every log in logs/ (TellTalesTest replays them all; docs/bird-air-protocol.md
 * has the table):
 *  - the 15.10 ride went dark at 57 / 61 °C while other rides ran to 31.45–31.5 V under load: the stop was the
 *    battery's over-temperature protection, so «перегрев» is RED from 55 °C;
 *  - the controller holds ~16 A; two-up it sits there for seconds on end, solo only on a hard start;
 *  - in the bottom ~30 % the controller cuts the current (16 → 9–12 A) to keep the pack above ~31.5 V;
 *  - regen right after a full charge lifts the pack to 43.5–44 V.
 * Each lamp has a debounce and a hold ([Latch]) or a hysteresis, so none flickers at 1 Hz. Lamps about what the
 * scooter does right now (traction, overvolt, ECO, overspeed, charging) light only while frames stream, the ▲ up
 * to [Params.riseHoldMs] after the last frame; the others keep the last state seen (temperature, charge, wear).
 *
 * Feed [onBms] / [onMotion] with every 3A 18 20 10 / 3A 17 00 0D frame, ask [lamps] on every snapshot (any rate:
 * it only reads the state, the link lamp aside). Not thread-safe: one caller (TelemetryTracker holds its lock).
 */
class TellTales(val params: Params = Params()) {

    data class Params(
        val warmC: Double = 40.0,          // YELLOW «тёплая»
        val hotC: Double = 48.0,           // AMBER «горячая»
        val overheatC: Double = 55.0,      // RED «перегрев» (15.10 went dark at 57 / 61 °C)
        val coldC: Double = 5.0,           // BLUE «холодная»
        val hystC: Double = 2.0,           // each level clears this much lower
        val riseOn: Double = 1.0,          // ▲ «быстро греется»: °C/min over riseWindowMs …
        val riseOff: Double = 0.5,         // … clears below this
        val riseWindowMs: Long = 120_000,
        val riseMinC: Double = 30.0,
        val riseHoldMs: Long = 60_000,
        val limitA: Double = 15.0,         // «мало тяги»: the BMS current at the ~16 A limit …
        val limitMs: Long = 5_000,         // … this long (solo hard starts: 4 in 40 min; two-up 10–19 times)
        val sagV: Double = 32.0,           // … or the pack under load below this (the controller cuts the current)
        val sagLoadA: Double = 5.0,
        val sagMs: Long = 3_000,
        val lowPct: Double = 25.0,         // «заряд» AMBER at or below (and the advisor's battery_low)
        val lowPctOff: Double = 26.0,
        val highWhKm: Double = 20.0,       // «высокий расход»: over the last 2 km (solo 10–13, two-up 18–21)
        val highWhKmOff: Double = 19.0,
        val weakR: Double = 0.35,          // «батарея устала»: FastPower's R (logs: 0.22–0.33 Ω)
        val weakROff: Double = 0.33,
        val weakAnchors: Int = 20,
        val overV: Double = 43.0,          // «перенапряжение»: regen at full (19.10: 43.5 / 44.0 V)
        val overVOff: Double = 42.8,
        val ecoAvgW: Double = 250.0,       // ECO: the fast power over ecoWindowMs, average and peak …
        val ecoPeakW: Double = 450.0,
        val ecoAvgOffW: Double = 300.0,    // … goes off above these
        val ecoPeakOffW: Double = 500.0,
        val ecoWindowMs: Long = 10_000,
        val ecoMinKmh: Double = 3.0,
        val ecoOnMs: Long = 3_000,
        val ecoHoldMs: Long = 3_000,
        val overspeedFactor: Double = 1.1, // «быстрее ограничителя»: the limiter lets ~+7 % under traction
        val overspeedHystKmh: Double = 1.0,
        val overspeedHoldMs: Long = 2_000,
        val chargeA: Double = 0.3,         // «зарядка»: current into the pack at standstill (the CV tail ~0.3 A)
        val chargeOffA: Double = 0.1,
        val chargeMs: Long = 5_000,
        val serviceRatio: Double = 0.85,   // «сервис»: BMS full capacity below this share of the rating
        val ratedMah: Double = 5200.0,
        val holdMs: Long = 5_000,
        val silentMs: Long = TelemetryTracker.STREAMING_MS,
        val linkHoldMs: Long = 3_000,
        val gapMs: Long = 2_500,
    )

    /** Lights once the condition has held [onMs], stays while it holds and [holdMs] after; true again within the hold
     *  relights at once. */
    private class Latch(val onMs: Long, val holdMs: Long) {
        private var since: Long? = null
        private var last = 0L
        private var lit = false
        var cond = false
            private set

        fun reset() { since = null; lit = false; cond = false }

        fun update(t: Long, c: Boolean) {
            cond = c
            if (c) {
                val s = since ?: t.also { since = it }
                lit = (lit && t - last <= holdMs) || t - s >= onMs
                if (lit) last = t
            } else {
                since = null
                if (lit && t - last > holdMs) lit = false
            }
        }

        fun on(now: Long) = lit && (cond || now - last <= holdMs)
    }

    private val p = params
    private val win = ArrayDeque<Pair<Long, Double>>()      // fast power over ecoWindowMs
    private val temps = ArrayDeque<Pair<Long, Double>>()    // the hotter sensor over riseWindowMs
    private var tempC: Double? = null
    private var tempLvl = 0                                 // 0 off, 1 warm, 2 hot, 3 overheat
    private var risingNow = false
    private var cold = false
    private var pctFine: Double? = null
    private var fullMah: Double? = null
    private var lowPct = false
    private var lastV = 0.0
    private var lastI = 0.0
    private var lastT: Long? = null
    private var lastBmsT: Long? = null
    private var speed = 0.0
    private var r: Double? = null
    private var ecoNow = false
    private var ecoW = 0.0
    private var ecoShownW = 0.0
    private var overNow = false
    private var overVNow = false
    private var overPeakV = 0.0
    private var chargingNow = false
    private var chargeShownA = 0.0
    private var high = false
    private var highWh = 0.0
    private var weak = false
    private var connectedSince: Long? = null
    private val limit = Latch(p.limitMs, p.holdMs)
    private val sag = Latch(p.sagMs, p.holdMs)
    private val charge = Latch(p.chargeMs, p.holdMs)
    private val eco = Latch(p.ecoOnMs, p.ecoHoldMs)
    private val overspeed = Latch(0, p.overspeedHoldMs)
    private val overvolt = Latch(0, p.holdMs)
    private val rise = Latch(0, p.riseHoldMs)
    private val consumption = Latch(0, p.holdMs)
    private val link = Latch(0, p.linkHoldMs)

    /** 100 · remaining / full mAh of the last BMS frame, floored to 0.1 % like the BMS floors to 1 % (56.3 → BMS 56):
     *  1 mAh ≈ 0.025 %, so one decimal is worth showing. */
    val batteryPctFine: Double? get() = pctFine?.let { floor(it * 10 + 1e-9) / 10 }

    /** Everything back to the start (a new recording). */
    fun reset() {
        restartStreams()
        temps.clear(); tempC = null; tempLvl = 0; risingNow = false; rise.reset(); cold = false
        pctFine = null; fullMah = null; lowPct = false; lastV = 0.0; lastI = 0.0; lastBmsT = null; speed = 0.0
        r = null; high = false; weak = false; consumption.reset(); link.reset(); connectedSince = null
    }

    /** A new stream of frames (a reconnection, a gap): the windows of what the scooter does now start over. */
    fun restartStreams() {
        win.clear(); lastT = null; ecoNow = false; overNow = false; overVNow = false; chargingNow = false
        for (l in listOf(limit, sag, charge, eco, overspeed, overvolt)) l.reset()
    }

    /** One 3A 18 20 10 frame; [fast] = FastPower's sample for it, [rOhm] / [anchorCount] its learned R and anchors. */
    fun onBms(tMs: Long, b: BmsFrame, fast: FastPower.Sample?, rOhm: Double?, anchorCount: Int) {
        lastT?.let { if (tMs - it > p.gapMs || tMs < it) restartStreams() }
        lastT = tMs; lastBmsT = tMs
        lastV = b.voltage; lastI = b.currentA
        r = rOhm
        if (b.fullMah > 0) {
            fullMah = b.fullMah.toDouble()
            val pc = 100.0 * b.remainingMah / b.fullMah
            pctFine = pc
            lowPct = pc <= p.lowPct || (lowPct && pc <= p.lowPctOff)
        }
        temperature(tMs, b)
        limit.update(tMs, b.motor && b.currentA >= p.limitA)
        sag.update(tMs, b.motor && b.currentA >= p.sagLoadA && b.voltage < p.sagV)
        val still = speed < 0.5
        chargingNow = still && (b.currentA <= -p.chargeA || (chargingNow && b.currentA <= -p.chargeOffA))
        if (chargingNow) chargeShownA = -b.currentA
        charge.update(tMs, chargingNow)
        overVNow = b.voltage > p.overV || (overVNow && b.voltage > p.overVOff)
        if (overVNow) overPeakV = if (overvolt.on(tMs)) maxOf(overPeakV, b.voltage) else b.voltage
        overvolt.update(tMs, overVNow)
        val rx = rOhm
        weak = rx != null && anchorCount >= p.weakAnchors && rx > (if (weak) p.weakROff else p.weakR)
        // ECO: moving gently, whatever the mode, and not while the traction lamp is (or is about to be) lit
        win.addLast(tMs to (fast?.powerW ?: b.voltage * b.currentA))
        while (tMs - win.first().first > p.ecoWindowMs) win.removeFirst()
        if (speed < p.ecoMinKmh || win.size < 3 || win.last().first - win.first().first < p.ecoWindowMs / 2) ecoNow = false
        else {
            var sum = 0.0; var peak = Double.NEGATIVE_INFINITY
            for ((_, w) in win) { sum += w; peak = maxOf(peak, w) }
            ecoW = sum / win.size
            ecoNow = if (ecoNow) ecoW <= p.ecoAvgOffW && peak <= p.ecoPeakOffW else ecoW <= p.ecoAvgW && peak <= p.ecoPeakW
        }
        val traction = limit.on(tMs) || sag.on(tMs)
        val gentle = ecoNow && !limit.cond && !sag.cond && !traction
        if (gentle) ecoShownW = ecoW
        eco.update(tMs, gentle)
        if (traction) eco.reset()                                       // never next to «мало тяги», not even held
    }

    /** One 3A 17 00 0D frame. */
    fun onMotion(tMs: Long, m: MotionFrame) {
        speed = m.speedKmh
        val lim = limitKmh(m.mode)
        overNow = if (lim == null) false else {
            val thr = lim * p.overspeedFactor
            m.speedKmh > thr || (overNow && m.speedKmh > thr - p.overspeedHystKmh)
        }
        overspeed.update(tMs, overNow)
    }

    private fun temperature(tMs: Long, b: BmsFrame) {
        var x: Double? = null
        for (c in intArrayOf(b.temp1C, b.temp2C)) if (c in -30..120) x = maxOf(x ?: c.toDouble(), c.toDouble())
        val t = x ?: return
        tempC = t
        temps.addLast(tMs to t)
        while (tMs - temps.first().first > p.riseWindowMs) temps.removeFirst()
        val up = if (t >= p.overheatC) 3 else if (t >= p.hotC) 2 else if (t >= p.warmC) 1 else 0
        val down = if (t > p.overheatC - p.hystC) 3 else if (t > p.hotC - p.hystC) 2 else if (t > p.warmC - p.hystC) 1 else 0
        tempLvl = if (up >= tempLvl) up else maxOf(up, minOf(tempLvl, down))
        val s = slope()
        risingNow = s != null && t >= p.riseMinC && s >= (if (risingNow) p.riseOff else p.riseOn)
        rise.update(tMs, risingNow)
        cold = t < p.coldC + (if (cold) p.hystC else 0.0)
    }

    /** °C per minute over the window (least squares), or null with under 3/4 of it. */
    private fun slope(): Double? {
        if (temps.size < 5) return null
        val t0 = temps.first().first
        if (temps.last().first - t0 < p.riseWindowMs * 3 / 4) return null
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for ((t, c) in temps) { val x = (t - t0) / 60_000.0; sx += x; sy += c; sxx += x * x; sxy += x * c }
        val n = temps.size
        val d = n * sxx - sx * sx
        return if (d <= 0) null else (n * sxy - sx * sy) / d
    }

    /** The lit lamps now, in [IDS] order. [range] gives the recent consumption, [warnings] the advisor's battery_low,
     *  [link] / [streaming] the phone's link to the scooter (as in the snapshot). */
    fun lamps(nowMs: Long, range: RangeEstimate?, warnings: List<BatteryWarning>, link: Link, streaming: Boolean): List<Lamp> {
        val on = HashMap<String, Lamp>()
        fun lit(id: String, level: LampLevel, text: String, value: Double? = null, rising: Boolean = false) {
            on[id] = Lamp(id, level, text, value?.takeIf { it.isFinite() }?.let { (it * 100).roundToLong() / 100.0 }, rising)
        }
        val rising = rise.on(nowMs) && nowMs - (lastBmsT ?: Long.MIN_VALUE / 2) <= p.riseHoldMs
        tempC?.let { t ->
            when {
                tempLvl == 3 -> lit(TEMP, LampLevel.RED, "Перегрев — сбавь газ", t, rising)
                tempLvl == 2 -> lit(TEMP, LampLevel.AMBER, if (rising) "Горячая, быстро греется" else "Батарея горячая", t, rising)
                tempLvl == 1 -> lit(TEMP, LampLevel.YELLOW, if (rising) "Тёплая, быстро греется" else "Батарея тёплая", t, rising)
                rising -> lit(TEMP, LampLevel.YELLOW, "Батарея быстро греется", t, true)
            }
            if (cold) lit(COLD, LampLevel.BLUE, "Батарея холодная", t)
        }
        val low = warnings.firstOrNull { it.id == BatteryAdvisor.LOW }
        when {
            low?.level == WarningLevel.CRITICAL -> lit(BATTERY, LampLevel.RED, "Сейчас отключится", low.value)
            low != null -> lit(BATTERY, LampLevel.AMBER, "Заряд на исходе", low.value)
            lowPct -> batteryPctFine?.let { lit(BATTERY, LampLevel.AMBER, "Заряд " + fmt1(it) + " %", it) }
        }
        if (streaming) {
            if (limit.on(nowMs)) lit(TRACTION, LampLevel.AMBER, "Мало тяги: предел тока", lastI)
            else if (sag.on(nowMs)) lit(TRACTION, LampLevel.AMBER, "Мало тяги: просадка", lastV)
            if (overvolt.on(nowMs)) lit(OVERVOLT, LampLevel.AMBER, "Перенапряжение " + fmt1(overPeakV) + " В", overPeakV)
            if (eco.on(nowMs) && TRACTION !in on) lit(ECO, LampLevel.GREEN, "ECO", ecoShownW)
            if (overspeed.on(nowMs)) lit(OVERSPEED, LampLevel.BLUE, "Быстрее ограничителя", speed)
            if (charge.on(nowMs)) lit(CHARGING, LampLevel.GREEN, "Зарядка " + fmt1(chargeShownA) + " А", chargeShownA)
        }
        val wh = range?.takeIf { it.basis == "recent" }?.whPerKm
        high = wh != null && wh > (if (high) p.highWhKmOff else p.highWhKm)
        if (high) highWh = wh!!
        consumption.update(nowMs, high)
        if (consumption.on(nowMs)) lit(CONSUMPTION, LampLevel.AMBER, "Расход " + fmt0(highWh) + " Вт·ч/км", highWh)
        if (weak) lit(WEAK, LampLevel.YELLOW, "Батарея устала: " + fmt2(r!!) + " Ом", r)
        fullMah?.let { f ->
            val share = 100 * f / p.ratedMah
            if (f < p.serviceRatio * p.ratedMah) lit(SERVICE, LampLevel.AMBER, "Ёмкость " + fmt0(share) + " % от паспорта", share)
        }
        // the link: reconnecting, or connected for a while and the handlebar silent
        if (link != Link.CONNECTED) connectedSince = null else if (connectedSince == null) connectedSince = nowMs
        val silent = link == Link.CONNECTED && !streaming && nowMs - (connectedSince ?: nowMs) > p.silentMs
        this.link.update(nowMs, link == Link.RECONNECTING || silent)
        if (this.link.on(nowMs) && (link == Link.CONNECTED || link == Link.RECONNECTING))
            lit(LINK, LampLevel.AMBER, if (link == Link.RECONNECTING) "Связь рвётся" else "Руль молчит")
        return IDS.mapNotNull { on[it] }
    }

    companion object {
        const val TEMP = "temp"; const val BATTERY = "battery"; const val TRACTION = "traction"
        const val CONSUMPTION = "consumption"; const val WEAK = "weak"; const val OVERVOLT = "overvolt"
        const val ECO = "eco"; const val SERVICE = "service"; const val LINK = "link"
        const val OVERSPEED = "overspeed"; const val CHARGING = "charging"; const val COLD = "cold"

        /** Cluster order: the watch's right column top to bottom, then the left column. */
        val IDS = listOf(TEMP, BATTERY, TRACTION, CONSUMPTION, WEAK, OVERVOLT, ECO, SERVICE, LINK, OVERSPEED, CHARGING, COLD)

        /** Short names for the phone's «Приборка» and the log. */
        val NAMES = mapOf(
            TEMP to "Температура батареи", BATTERY to "Заряд", TRACTION to "Мало тяги", CONSUMPTION to "Высокий расход",
            WEAK to "Слабая батарея", OVERVOLT to "Перенапряжение", ECO to "ECO", SERVICE to "Сервис", LINK to "Связь",
            OVERSPEED to "Выше ограничителя", CHARGING to "Зарядка", COLD to "Холодная батарея",
        )

        /** When each lamp lights, for the phone's explanation. */
        val HELP = mapOf(
            TEMP to "жёлтая — батарея ≥ 40 °C или быстро греется (▲: больше 1 °C/мин за 2 мин); оранжевая — ≥ 48 °C; " +
                "красная — ≥ 55 °C: 15.10 самокат отключился на 57 / 61 °C (защита батареи от перегрева). Сбавь газ, дай остыть.",
            BATTERY to "оранжевая — заряд ≤ 25 % или напряжение под нагрузкой садится к отсечке («Заряд на исходе»); " +
                "красная — под нагрузкой ниже 31,6 В: сейчас отключится (19.10 — на 31,45 В).",
            TRACTION to "ток упёрся в предел контроллера (~16 А) дольше 5 с — вдвоём, в горку; или батарея под нагрузкой ниже " +
                "32 В: контроллер урезает ток (внизу заряда 9–12 А вместо 16), мощности больше не будет.",
            CONSUMPTION to "больше 20 Вт·ч/км за последние 2 км (один — 10–13, вдвоём — 18–21): запас хода тает быстрее.",
            WEAK to "внутреннее сопротивление батареи больше 0,35 Ом (по логам 0,22–0,33): она устала и сильнее проседает.",
            OVERVOLT to "рекуперация подняла батарею выше 43 В (19.10 — до 43,5 и 44 В): так бывает сразу после полной " +
                "зарядки. Тормози мягче или ручным тормозом.",
            ECO to "едешь мягко, в любом режиме: за 10 с в среднем до 250 Вт и без рывков выше 450 Вт.",
            SERVICE to "полная ёмкость по BMS меньше 85 % от паспортных 5200 мА·ч — батарея изношена (по логам 3900–4363).",
            LINK to "связь с самокатом рвётся или руль дольше 3 с не присылает данные.",
            OVERSPEED to "быстрее предела режима больше чем на 10 % — накатом с горки (TAKEOFF 9, GLIDE 12,5, FLY 16 mph).",
            CHARGING to "ток в батарею на стоянке: идёт зарядка.",
            COLD to "батарея холоднее 5 °C: ёмкость и тяга меньше.",
        )

        /** The bulb check at start and on each (re)connection lasts this long. */
        const val BULB_CHECK_MS = 1500L

        /** Every lamp in its strongest colour, steady: the bulb check. */
        fun bulbCheck(): List<Lamp> = IDS.map { Lamp(it, BULB.getValue(it), NAMES.getValue(it)) }
        private val BULB = mapOf(
            TEMP to LampLevel.RED, BATTERY to LampLevel.RED, TRACTION to LampLevel.AMBER, CONSUMPTION to LampLevel.AMBER,
            WEAK to LampLevel.YELLOW, OVERVOLT to LampLevel.AMBER, ECO to LampLevel.GREEN, SERVICE to LampLevel.AMBER,
            LINK to LampLevel.AMBER, OVERSPEED to LampLevel.BLUE, CHARGING to LampLevel.GREEN, COLD to LampLevel.BLUE,
        )

        /** Lamps the dial's text line skips: standing conditions (always lit with this battery) and ECO (the icon says it). */
        val QUIET = setOf(SERVICE, WEAK, ECO)

        /** The lamp whose text the dial shows: the strongest level (RED > AMBER > YELLOW > BLUE > GREEN), then [IDS] order. */
        fun top(lamps: List<Lamp>): Lamp? =
            lamps.filter { it.id !in QUIET && it.level != LampLevel.OFF }.maxWithOrNull(compareBy<Lamp> { it.level.ordinal }.thenByDescending { IDS.indexOf(it.id) })

        /** Lamps whose level changed, in [IDS] order (the phone's journal and the log marks). */
        fun changes(before: List<Lamp>, after: List<Lamp>): List<LampChange> {
            val b = before.associateBy { it.id }
            val a = after.associateBy { it.id }
            return IDS.mapNotNull { id ->
                val from = b[id]?.level ?: LampLevel.OFF
                val to = a[id]?.level ?: LampLevel.OFF
                if (from == to) null else LampChange(id, from, to, a[id])
            }
        }

        /** «Лампа «Температура батареи»: оранжевая — Батарея горячая», «… : погасла». */
        fun markText(c: LampChange): String =
            "Лампа «" + NAMES.getValue(c.id) + "»: " + LEVEL_NAMES.getValue(c.to) + (c.lamp?.text?.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: "")

        val LEVEL_NAMES = mapOf(
            LampLevel.OFF to "погасла", LampLevel.GREEN to "зелёная", LampLevel.BLUE to "синяя",
            LampLevel.YELLOW to "жёлтая", LampLevel.AMBER to "оранжевая", LampLevel.RED to "красная",
        )

        /** The mode's speed limit, mph (TAKEOFF 9, GLIDE 12.5, FLY 16), and in km/h. */
        fun limitMph(mode: Int?): Double? = when (mode) { 1 -> 9.0; 2 -> 12.5; 3 -> 16.0; else -> null }
        fun limitKmh(mode: Int?): Double? = limitMph(mode)?.let { it * BatteryAdvisor.MILE_KM }

        private fun fmt(x: Double, d: Int) = String.format(java.util.Locale.ROOT, "%.${d}f", x).replace('.', ',')
        fun fmt0(x: Double) = fmt(x, 0)
        fun fmt1(x: Double) = fmt(x, 1)
        fun fmt2(x: Double) = fmt(x, 2)
    }
}
