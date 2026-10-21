package io.github.purplepalm.birdair.dial

import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * «Растущие лампы» (gallery/grow.html), the layout: a pure function of the lit lamps and the centre's texts → the discs,
 * their tablets, the mini badges, pips, quiet icons, dots and the centre rows, on the mock's 480-px grid (doubles, as the
 * mock computes). FaceGrow.kt draws it and moves between two of them; FaceGrowLayoutTest checks the mock's selftest
 * (every combination of up to 4 lamps × levels) and a golden sample of the mock's own layouts.
 */

/** A text's width in mock px at [px] mock px, [weight] 400 … 700 (the face measures with its TextMeasurer, tests with a model). */
fun interface GrowMeasure {
    fun width(text: String, px: Double, weight: Int): Double
}

object GrowLayout {
    const val C = 240.0
    /** The discs touch this radius from inside (the speed track is at 195). */
    const val RMAX = 183.0
    /** The ring of off dots, the quiet icons, the mini badges; the centre rows stay inside [RROW]. */
    const val RD = 175.0
    const val RQ = 168.0
    const val RMINI = 165.0
    const val RROW = 170.0
    const val MAX_LOUD = 3
    const val DMIN = 62.0
    const val DPLATE = 72.0
    const val MINI_D = 42.0
    const val BARE_D = 30.0
    const val PIP_D = 14.0
    const val RPIP = 172.0
    const val DOT_D = 8.0
    const val QUIET_D = 28.0
    /** A badge never leaves its home by more than this (degrees). */
    const val SHIFT = 12.0
    const val GAP = 8.0
    const val ICON_K = 0.62
    /** Tablets stay inside this radius (the speed track's inner edge). */
    const val PLATE_SAFE = 187.0
    /** The bulb check's places: r 158, 44 px. */
    const val CHECK_R = 158.0
    const val CHECK_D = 44.0

    /** Home angles, clock degrees: TellTales.IDS, the right column top → bottom (40° … 140°), then the left. */
    val HOME: Map<String, Double> = TellTales.IDS.withIndex().associate { (i, id) -> id to Dial.lampDeg(i).toDouble() }

    /** The main lamp: red 126 (icon 0,62 · D = 78 px ≈ 39′ at 55 cm), amber 108, yellow / blue / green 104. */
    fun baseD(level: LampLevel): Double = when (level) { LampLevel.RED -> 126.0; LampLevel.AMBER -> 108.0; else -> 104.0 }

    /** The second ×0,8, the third ×0,68. */
    private val RANK_K = doubleArrayOf(1.0, 0.8, 0.68)
    /** The tablet's font by rank: the main 28 (down to 22), the second 24 (20), the third 22 (20). */
    private val PLATE_PX = arrayOf(doubleArrayOf(28.0, 26.0, 24.0, 22.0), doubleArrayOf(24.0, 22.0, 20.0), doubleArrayOf(22.0, 20.0))

    // the centre rows: their bands (y0, y1), the text line's and the power's places, the clearances
    val BAT = 150.0 to 236.0
    val RANGE = 240.0 to 272.0
    val DET = 274.0 to 298.0
    val CLOCK = 104.0 to 128.0
    const val MSG_TOP = 142.0
    /** (text line y, power y): in place, lower into the open bottom, or the line up to the clock's place. */
    val MSG_CANDS = listOf(326.0 to 352.0, 352.0 to 378.0, 378.0 to 404.0, MSG_TOP to 330.0, MSG_TOP to 356.0, MSG_TOP to 404.0)
    val POW_ALONE = listOf(330.0, 356.0, 404.0)
    private const val M_DISC = 14.0
    private const val M_PLATE = 30.0
    private const val M_MINI = 8.0
    /** The vertical clearance between a row's band and a disc or tablet. */
    private const val MV = 5.0
    /** The battery block's width at full size; its least scale and the others' ([rowFit]'s minS). */
    const val BAT_W = 156.0
    const val BAT_MIN = 0.62
    const val RANGE_MIN = 0.7
    const val DET_MIN = 0.84
    const val MSG_MIN = 0.9
    const val POW_MIN = 0.8
    const val CLOCK_MIN = 0.8
    /** A text line wider than this does not keep a badge away (it shrinks, then ellipsizes); the mock's are ≤ 27 chars. */
    const val MSG_NEED_MAX = 300.0
    /** The main lamp's last resorts: the line counted this wide, then the centre not counted at all. */
    const val MSG_RELAXED = 150.0

    // text sizes (mock px) and weights, as the mock's fonts
    const val CLOCK_PX = 20.0
    const val PCT_PX = 40.0
    const val RANGE_PX = 26.0
    const val DET_PX = 19.0
    const val MSG_PX = 20.0
    const val POW_PX = 20.0
    const val STATUS_PX = 18.0
    const val STATUS2_PX = 17.0
    const val PLATE_W = 700

    private val OFFS: DoubleArray = buildList { add(0.0); var d = 0.5; while (d <= SHIFT + 1e-9) { add(d); add(-d); d += 0.5 } }.toDoubleArray()
    private val NO_OFFS = doubleArrayOf(0.0)

    private fun rad(deg: Double) = Math.toRadians(deg)
    fun point(r: Double, deg: Double): Pair<Double, Double> = (C + r * sin(rad(deg))) to (C - r * cos(rad(deg)))

    // ---------------- input

    /** One lit lamp as the layout sees it: [plates] the tablet's texts, best first (the value, the title, a word). */
    data class In(val id: String, val level: LampLevel, val plates: List<String>)

    /** The centre's widths at full size (mock px; 0: no such row): range, «V · °C · MODE» full and short, the text line, the power, the clock, the status lines. */
    data class Need(
        val range: Double, val det: Double, val detShort: Double, val msg: Double, val pow: Double,
        val clock: Double = 0.0, val status: List<Double> = emptyList(),
    )

    /** What [plan] lays out: the lit lamps (any order), the widths, and the measure for the tablets. */
    data class Input(val lamps: List<In>, val need: Need)

    /**
     * [In] for [l]: its tablet texts, distinct and non-blank — as the mock: the number where the number is the news
     * (°C, V, speed, A: the value with its unit, then whole ([whole]), the title, a word), a word where it is not
     * («Тяга», «Расход», «Полная», «Связь», «BMS»: [word] first, then the value and the title).
     */
    fun of(l: Lamp): In {
        val v = listOfNotNull(l.valueText, l.valueText?.let(::whole))
        val texts = if (wordFirst(l)) listOfNotNull(word(l)) + v + l.title else v + listOfNotNull(l.title, word(l))
        return In(l.id, l.level, texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
    }

    private fun bms(l: Lamp) = l.id == TellTales.BATTERY && (l.text == TellTales.T_BMS_CUT || l.text == TellTales.T_NO_BMS)

    private fun wordFirst(l: Lamp): Boolean = when (l.id) {
        TellTales.TRACTION, TellTales.CONSUMPTION, TellTales.OVERVOLT -> true
        TellTales.LINK -> !l.text.startsWith("Код")
        TellTales.BATTERY -> bms(l)
        else -> false
    }

    private val DECIMAL = Regex("^(\\d+),(\\d+)( (?:км/ч|mph|%|А))$")

    /** «31,4 км/ч» → «31 км/ч»: a speed, a % or amps whole reads faster on a small tablet (volts keep their tenth); null: not such. */
    fun whole(valueText: String): String? {
        val g = DECIMAL.matchEntire(valueText)?.groupValues ?: return null
        return Math.round((g[1] + "." + g[2]).toDouble()).toString() + g[3]
    }

    /** The mock's one-word tablets: first for the lamps whose number is not the news ([of]), else the last resort. */
    fun word(l: Lamp): String? = when (l.id) {
        TellTales.TEMP -> "Темп."
        TellTales.BATTERY -> if (bms(l)) "BMS" else "Заряд"
        TellTales.TRACTION -> "Тяга"
        TellTales.CONSUMPTION -> "Расход"
        TellTales.WEAK -> "Слабая"
        // the BMS's over-voltage protection cut the charge (the motor does not brake); otherwise a full pack under regen
        TellTales.OVERVOLT -> if (l.text == TellTales.T_OV_CUT) "Защита" else "Полная"
        TellTales.ECO -> "ECO"
        TellTales.SERVICE -> "Сервис"
        TellTales.LINK -> if (l.text == TellTales.T_DASH_NO_BMS) "BMS" else if (l.text.startsWith("Код")) "Код" else "Связь"
        TellTales.OVERSPEED -> "Быстро"
        TellTales.CHARGING -> "Зарядка"
        TellTales.COLD -> "Холод"
        else -> null
    }

    // ---------------- output

    interface Box { val x0: Double; val x1: Double; val y0: Double; val y1: Double }

    /** A tablet: [index] into the lamp's [In.plates], at [px], [w]×[h], its box; [below]: sunk into the disc's lower rim. */
    data class Plate(
        val index: Int, val px: Double, val w: Double, val h: Double,
        override val x0: Double, override val x1: Double, override val y0: Double, override val y1: Double, val cx: Double, val below: Boolean,
    ) : Box

    /** A big badge: [rank] 0 (the main lamp, [TellTales.top]) … 2; [a] its clock angle (signed), [a0] its home's (unsigned). */
    data class Badge(
        val id: String, val level: LampLevel, val rank: Int, val a: Double, val a0: Double, val side: Int, val d: Double,
        val x: Double, val y: Double, val plate: Plate?,
    )

    data class Spot(val x: Double, val y: Double, val a: Double)

    enum class Kind { MINI, QUIET, DOT }

    /** The rest on the ring: a mini badge ([d] 42, or 30 bare, or a [pip] 14 over the neighbour), a quiet icon, an off dot; [spot] null: hidden under a badge. */
    data class Small(val id: String, val kind: Kind, val level: LampLevel, val spot: Spot?, val d: Double, val pip: Boolean = false)

    /** A row fitted into its band: centre [cx], scale [s] (≥ its minimum), the centre's range [lo]…[hi], the free [span]. */
    data class Fit(
        val cx: Double, val s: Double, val lo: Double, val hi: Double, val fits: Boolean, val ws: Double,
        val y0: Double, val y1: Double, val span: Double,
    )

    /**
     * The centre: [clock] (shown: [clockA] 1), the battery, range and details (one column), [detShort] «41°» instead of
     * «41 °C», the text line at [msgY] ([msg] null: none), the power at [powY]; [status] the status lines in the clock's place.
     */
    data class Rows(
        val clock: Fit, val clockA: Double, val bat: Fit, val range: Fit, val det: Fit, val detShort: Boolean,
        val msg: Fit?, val msgY: Double, val pow: Fit, val powY: Double, val status: List<Fit>,
    )

    data class Plan(val badges: List<Badge>, val small: List<Small>, val rows: Rows) {
        fun badge(id: String): Badge? = badges.firstOrNull { it.id == id }
        fun small(id: String): Small? = small.firstOrNull { it.id == id }
    }

    // ---------------- the layout

    /** [TellTales.top]'s order of the loud ones: the strongest level first, then the IDS order. */
    fun order(lamps: List<In>): List<In> = lamps
        .filter { it.id in HOME && it.id !in TellTales.QUIET && it.level != LampLevel.OFF }
        .sortedWith(compareByDescending<In> { it.level.ordinal }.thenBy { TellTales.IDS.indexOf(it.id) })

    fun plan(input: Input, m: GrowMeasure): Plan {
        val list = order(input.lamps)
        val (badges, minis, need) = placeAll(list, input.need.let { if (it.msg > MSG_NEED_MAX) it.copy(msg = MSG_NEED_MAX) else it }, m)
        val ring = ringObstacles(badges)
        val small = ArrayList<Small>()
        for (mi in minis) {
            small += mi
            if (!mi.pip && mi.spot != null) ring += Obs.disc(mi.spot.x, mi.spot.y, mi.d / 2)
        }
        val lit = input.lamps.associateBy { it.id }
        for (id in TellTales.IDS) {
            if (badges.any { it.id == id } || minis.any { it.id == id }) continue
            val l = lit[id]?.takeIf { it.level != LampLevel.OFF }
            val quiet = l != null && id in TellTales.QUIET
            val sp = ringSpot(id, if (quiet) QUIET_D else DOT_D, if (quiet) RQ else RD, ring)
            small += Small(id, if (quiet) Kind.QUIET else Kind.DOT, l?.level ?: LampLevel.OFF, sp, if (quiet) 0.0 else DOT_D)
        }
        return Plan(badges, small, rows(badges, small.filter { it.kind == Kind.MINI }, need))
    }

    /**
     * In [TellTales.top] order: the main lamp stays on its home (it only shrinks), the next ones may slide ≤ 12° and shrink,
     * then lose the tablet; never more than [MAX_LOUD] big ones. The rest: a mini badge 42 px on the ring (≤ 12°), or a
     * bare icon 30 px, or — when even that has no room — a pip on its home over the neighbour's rim. Past the mock: the
     * main lamp always gets a badge — the text line counted narrower ([MSG_RELAXED], it shrinks and ellipsizes), then
     * none (hidden), then the centre not counted, then no tablet — and the rest are laid out with what it needed.
     */
    private fun placeAll(list: List<In>, full: Need, m: GrowMeasure): Triple<List<Badge>, List<Small>, Need> {
        val badges = ArrayList<Badge>()
        val rest = ArrayList<In>()
        var need = full
        for (e in list) {
            val rank = badges.size
            var b: Badge? = null
            if (rank == 0) {
                b = tryPlace(e, 0, badges, true, need, false, m) ?: tryPlace(e, 0, badges, true, need, true, m)
                if (b == null) for (relaxed in listOf(need.copy(msg = min(need.msg, MSG_RELAXED)), need.copy(msg = 0.0))) {
                    b = tryPlace(e, 0, badges, true, relaxed, true, m)
                    if (b != null) { need = relaxed; break }
                }
                if (b == null) {
                    need = need.copy(msg = 0.0)
                    b = tryPlace(e, 0, badges, true, need, false, m, core = false) ?: tryPlace(e, 0, badges, false, need, false, m, core = false)
                }
            } else if (rank < MAX_LOUD) {
                b = tryPlace(e, rank, badges, true, need, true, m) ?: tryPlace(e, rank, badges, false, need, true, m)
            }
            if (b != null) badges += b else rest += e
        }
        val ring = ringObstacles(badges)
        val done = ArrayList<Small>()
        for (e in rest) {
            val test = { d: Double -> { x: Double, y: Double -> coreOK(badges, done + Small(e.id, Kind.MINI, e.level, Spot(x, y, 0.0), d), need) } }
            var d = MINI_D
            var sp = ringSpot(e.id, MINI_D, RMINI, ring, 4.0, test(MINI_D))
            if (sp == null) { d = BARE_D; sp = ringSpot(e.id, BARE_D, RQ, ring, 3.0, test(BARE_D)) }
            val s = if (sp != null) {
                ring += Obs.disc(sp.x, sp.y, d / 2)
                Small(e.id, Kind.MINI, e.level, sp, d)
            } else {
                // a pip: the tablets stay readable, the discs may be overlapped
                val home = HOME.getValue(e.id)
                val (hx, hy) = point(RPIP, home)
                Small(e.id, Kind.MINI, e.level, ringSpot(e.id, PIP_D, RPIP, ring.filter { !it.disc }, 2.0) ?: Spot(hx, hy, home), PIP_D, pip = true)
            }
            done += s
        }
        return Triple(badges, done, need)
    }

    private fun tryPlace(e: In, rank: Int, placed: List<Badge>, withPlate: Boolean, need: Need, mayMove: Boolean, m: GrowMeasure, core: Boolean = true): Badge? {
        val home = HOME.getValue(e.id)
        val a0 = abs(home)
        val side = if (home > 0) 1 else -1
        val d0 = baseD(e.level) * RANK_K[rank]
        val dmin = if (withPlate) min(d0, DPLATE) else DMIN
        // the tablet sits on the lower rim; only the main lamp of the lowest places (120°, 140°) may wear it on the upper one
        val pos = if (rank == 0 && a0 >= 110) TWO_POS else ONE_POS
        var d = d0
        while (d >= dmin - 1e-6) {
            for (da in if (mayMove) OFFS else NO_OFFS) {
                val a = a0 + da
                if (a < 22 || a > 158) continue
                val r = RMAX - d / 2
                val x = C + side * r * sin(rad(a))
                val y = C - r * cos(rad(a))
                if (if (side > 0) x - d / 2 < C + 4 else x + d / 2 > C - 4) continue
                // the tablet's text before its rim: «Полная» on the upper rim beats «42,3 В» on the lower one
                for (i in if (withPlate) e.plates.indices else NO_TEXT) for (below in if (withPlate) pos else NO_POS) {
                    val plate = if (below != null) plateFor(i, e.plates[i], x, y, d, side, PLATE_PX[rank], below, m) ?: continue else null
                    val b = Badge(e.id, e.level, rank, a * side, a0, side, d, x, y, plate)
                    if (placed.any { clash(b, it) }) continue
                    if (core && !coreOK(placed + b, emptyList(), need)) continue
                    return b
                }
            }
            d = if (d - 4 >= dmin || d == dmin) d - 4 else dmin
        }
        return null
    }

    private val ONE_POS = listOf<Boolean?>(true)
    private val TWO_POS = listOf<Boolean?>(true, false)
    private val NO_POS = listOf<Boolean?>(null)
    private val NO_TEXT = 0..0

    /**
     * The tablet: a capsule sunk into the disc's lower rim (or the upper one), never more than 10 px past the disc's inner
     * edge, inside [PLATE_SAFE]; [text] (the lamp's [index]-th) at the first of [pxs] it fits.
     */
    private fun plateFor(index: Int, text: String, x: Double, y: Double, d: Double, side: Int, pxs: DoubleArray, below: Boolean, m: GrowMeasure): Plate? {
        for (px in pxs) {
            val h = (px * 1.3).roundToInt().toDouble()
            val w = ceil(m.width(text, px, PLATE_W) + px * 0.8)
            val y0 = if (below) y + d / 2 + 6 - h else y - d / 2 - 6
            val y1 = y0 + h
            val far = max(abs(y0 - C), abs(y1 - C)) - h * 0.15
            val lim = sqrt(max(0.0, PLATE_SAFE * PLATE_SAFE - far * far))
            val lo = if (side > 0) maxOf(C - lim, x - d / 2 - 10, C + 4) else C - lim
            val hi = if (side > 0) C + lim else minOf(C + lim, x + d / 2 + 10, C - 4)
            if (hi - lo < w) continue
            val cx = (x).coerceIn(lo + w / 2, hi - w / 2)
            if (abs(cx - x) > d * 0.3) continue
            return Plate(index, px, w, h, cx - w / 2, cx + w / 2, y0, y1, cx, below)
        }
        return null
    }

    fun rectPt(r: Box, px: Double, py: Double): Double = hypot(maxOf(r.x0 - px, 0.0, px - r.x1), maxOf(r.y0 - py, 0.0, py - r.y1))
    fun rectRect(a: Box, b: Box): Double = hypot(maxOf(a.x0 - b.x1, b.x0 - a.x1, 0.0), maxOf(a.y0 - b.y1, b.y0 - a.y1, 0.0))

    fun clash(a: Badge, b: Badge): Boolean {
        if (hypot(a.x - b.x, a.y - b.y) < (a.d + b.d) / 2 + GAP) return true
        if (a.plate != null && rectPt(a.plate, b.x, b.y) < b.d / 2 + GAP) return true
        if (b.plate != null && rectPt(b.plate, a.x, a.y) < a.d / 2 + GAP) return true
        return a.plate != null && b.plate != null && rectRect(a.plate, b.plate) < GAP
    }

    /** An obstacle: a disc ([rho]) or a box; [side] which half it bounds the rows from, [m] the rows' margin to it. */
    class Obs private constructor(
        val disc: Boolean, val x: Double, val y: Double, val rho: Double,
        override val x0: Double, override val x1: Double, override val y0: Double, override val y1: Double, val side: Int, val m: Double,
    ) : Box {
        companion object {
            fun disc(x: Double, y: Double, rho: Double, side: Int = 0, m: Double = 0.0) = Obs(true, x, y, rho, x - rho, x + rho, y - rho, y + rho, side, m)
            fun box(b: Box, side: Int = 0, m: Double = 0.0) = Obs(false, 0.0, 0.0, 0.0, b.x0, b.x1, b.y0, b.y1, side, m)
        }
    }

    private fun ringObstacles(badges: List<Badge>): ArrayList<Obs> {
        val o = ArrayList<Obs>()
        for (b in badges) { o += Obs.disc(b.x, b.y, b.d / 2); if (b.plate != null) o += Obs.box(b.plate) }
        return o
    }

    private fun rowObstacles(badges: List<Badge>, minis: List<Small>): List<Obs> {
        val o = ArrayList<Obs>(badges.size * 2 + minis.size)
        for (b in badges) {
            o += Obs.disc(b.x, b.y, b.d / 2, b.side, M_DISC)
            if (b.plate != null) o += Obs.box(b.plate, b.side, M_PLATE)
        }
        for (mi in minis) if (mi.spot != null && !mi.pip) o += Obs.disc(mi.spot.x, mi.spot.y, mi.d / 2, if (HOME.getValue(mi.id) > 0) 1 else -1, M_MINI)
        return o
    }

    private fun msgBand(y: Double) = (y - 16) to (y + 6)

    /** The centre keeps room for its rows at their smallest: a badge that would squeeze them is not placed there. */
    private fun coreOK(badges: List<Badge>, minis: List<Small>, need: Need): Boolean {
        val obs = rowObstacles(badges, minis)
        if (!rowFit(BAT, BAT_W, obs, BAT_MIN).fits) return false
        if (need.range > 0 && !rowFit(RANGE, need.range, obs, RANGE_MIN).fits) return false
        if (need.det > 0 && !rowFit(DET, need.det, obs, DET_MIN).fits && !rowFit(DET, need.detShort, obs, DET_MIN).fits) return false
        if (need.msg > 0) return MSG_CANDS.any { (my, py) -> rowFit(msgBand(my), need.msg, obs, MSG_MIN).fits && rowFit(msgBand(py), need.pow, obs, POW_MIN).fits }
        return POW_ALONE.any { rowFit(msgBand(it), need.pow, obs, POW_MIN).fits }
    }

    /** Small things on the ring (mini badges, quiet icons, off dots): on their home or ≤ 12° off, else null (hidden). */
    private fun ringSpot(id: String, size: Double, r: Double, obstacles: List<Obs>, gap: Double = 5.0, test: ((Double, Double) -> Boolean)? = null): Spot? {
        val home = HOME.getValue(id)
        val a0 = abs(home)
        val side = if (home > 0) 1 else -1
        for (da in OFFS) {
            val a = a0 + da
            if (a < 24 || a > 156) continue
            val (x, y) = point(r, a * side)
            var ok = true
            for (o in obstacles) {
                if (if (o.disc) hypot(o.x - x, o.y - y) < o.rho + size / 2 + gap else rectPt(o, x, y) < size / 2 + gap) { ok = false; break }
            }
            if (ok && test != null && !test(x, y)) ok = false
            if (ok) return Spot(x, y, a * side)
        }
        return null
    }

    private fun xExt(o: Obs, y0: Double, y1: Double): Pair<Double, Double>? {
        if (o.disc) {
            val dy = if (o.y < y0) y0 - o.y else if (o.y > y1) o.y - y1 else 0.0
            val rho = o.rho + MV
            if (dy >= rho) return null
            val h = sqrt(rho * rho - dy * dy) - MV
            return (o.x - h) to (o.x + h)
        }
        if (o.y1 + MV <= y0 || o.y0 - MV >= y1) return null
        return o.x0 to o.x1
    }

    /** The half-width free for text at height [y]: inside the speed track, except the open bottom sector (|angle| > 137°). */
    fun hwAt(y: Double): Double {
        val dy = y - C
        if (dy <= 0) return sqrt(max(0.0, RROW * RROW - dy * dy))
        if (dy * 1.37 <= 176) return sqrt(176.0 * 176.0 - dy * dy)
        return min(0.93 * dy - 4, sqrt(max(0.0, 226.0 * 226.0 - dy * dy)))
    }

    /** A row [w] wide in [band], kept off [obs] (each bounds its own half), scaled down to [minS] at most. */
    fun rowFit(band: Pair<Double, Double>, w: Double, obs: List<Obs>, minS: Double): Fit {
        val (y0, y1) = band
        val hw = minOf(hwAt(y0), hwAt(y1), if (y0 < C && y1 > C) RROW else 1e9)
        var lx = C - hw
        var rx = C + hw
        for (o in obs) {
            val e = xExt(o, y0, y1) ?: continue
            if (o.side > 0) rx = min(rx, e.first - o.m) else lx = max(lx, e.second + o.m)
        }
        val span = rx - lx
        if (w <= 0) return Fit(if (lx <= rx) C.coerceIn(lx, rx) else (lx + rx) / 2, 1.0, lx, rx, span >= -0.5, 0.0, y0, y1, span)
        val s = (span / w).coerceIn(minS, 1.0)
        val lo = lx + s * w / 2
        val hi = rx - s * w / 2
        val cx = if (lo <= hi) C.coerceIn(lo, hi) else (lx + rx) / 2
        return Fit(cx, s, lo, hi, span >= w * s - 0.5, s * w, y0, y1, span)
    }

    private fun rows(badges: List<Badge>, minis: List<Small>, need: Need): Rows {
        // the rows keep off discs 14 px, tablets 30 px (a row never runs on into a tablet), minis 8
        val obs = rowObstacles(badges, minis)
        val clock = rowFit(CLOCK, need.clock, obs, CLOCK_MIN)
        var bat = rowFit(BAT, BAT_W, obs, BAT_MIN).copy(y0 = 158.0, y1 = 228.0)
        var range = rowFit(RANGE, need.range, obs, RANGE_MIN)
        var det = rowFit(DET, need.det, obs, DET_MIN)
        var short = false
        if (!det.fits || det.s < 0.92) {
            val d2 = rowFit(DET, need.detShort, obs, DET_MIN)
            if (d2.s > det.s + 0.02 || (!det.fits && d2.fits)) { det = d2; short = true }
        }
        // battery, range and details move as one column when they can: one shared centre
        val blk = listOf(bat, range, det)
        val lo = blk.maxOf { it.lo }
        val hi = blk.minOf { it.hi }
        if (lo <= hi) {
            val cx = C.coerceIn(lo, hi)
            bat = bat.copy(cx = cx); range = range.copy(cx = cx); det = det.copy(cx = cx)
        } else {
            val mid = blk.map { it.cx }.sorted()[1]
            fun Fit.to(mid: Double) = if (this.lo <= this.hi) copy(cx = mid.coerceIn(this.lo, this.hi)) else this
            bat = bat.to(mid); range = range.to(mid); det = det.to(mid)
        }
        // the text line and the power: in place, or lower, down into the open bottom when the sides are taken
        val msg = need.msg > 0
        var best: Triple<Double, Fit?, Pair<Fit, Pair<Double, Double>>>? = null
        val cands = if (msg) MSG_CANDS else POW_ALONE.map { Double.NaN to it }
        cands.forEachIndexed { i, (my, py) ->
            val fm = if (msg) rowFit(msgBand(my), need.msg, obs, MSG_MIN) else null
            val fp = rowFit(msgBand(py), need.pow, obs, POW_MIN)
            val fmFits = fm?.fits ?: true
            val fmS = fm?.s ?: 1.0
            val ok = fmFits && fp.fits && fmS >= 0.999 && fp.s >= 0.999
            val ok2 = fmFits && fp.fits
            val q = (if (ok) 20.0 else if (ok2) 10.0 else 0.0) + min(fmS, fp.s) - 0.05 * i
            if (best == null || q > best!!.first) best = Triple(q, fm, fp to (my to py))
        }
        val b = best!!
        val msgY = if (msg) b.third.second.first else 326.0
        val clockA = if (clock.fits && clock.s >= 0.999 && !(msg && msgY == MSG_TOP)) 1.0 else 0.0
        // the status lines in the clock's place: one at 122, two at 106 / 127
        val status = when (need.status.size) {
            0 -> emptyList()
            1 -> listOf(rowFit(CLOCK, need.status[0], obs, 0.6))
            else -> listOf(rowFit(90.0 to 110.0, need.status[0], obs, 0.6), rowFit(111.0 to 131.0, need.status[1], obs, 0.6))
        }
        return Rows(clock, clockA, bat, range, det, short, b.second, msgY, b.third.first, b.third.second.second, status)
    }

    // ---------------- checks (the mock's selftest, unit-tested)

    /** What is wrong with [p] for [input] (empty: all is well): the mock's selftest invariants, plus «inside the screen». */
    fun problems(input: Input, p: Plan): List<String> {
        val out = ArrayList<String>()
        val bs = p.badges
        val top = order(input.lamps).firstOrNull()
        if (top != null && bs.isEmpty()) out += "no badge"
        if (bs.size > MAX_LOUD) out += "too many"
        if (top != null && bs.firstOrNull()?.id != top.id) out += "main is not top()"
        for (b in bs) {
            if (hypot(b.x - C, b.y - C) + b.d / 2 > RMAX + 0.5) out += "${b.id} outside"
            if (abs(abs(b.a) - b.a0) > SHIFT + 1e-6) out += "${b.id} moved"
            b.plate?.let { pl ->
                for ((px, py) in listOf(pl.x0 to pl.y0, pl.x1 to pl.y0, pl.x0 to pl.y1, pl.x1 to pl.y1)) if (hypot(px - C, py - C) > 200) out += "${b.id} plate off screen"
            }
        }
        for (i in bs.indices) for (j in i + 1 until bs.size) if (clash(bs[i], bs[j])) out += "clash ${bs[i].id}/${bs[j].id}"
        val r = p.rows
        val boxes = listOf("bat" to r.bat, "range" to r.range, "det" to r.det) + listOfNotNull(r.msg?.let { "msg" to it })
        for ((name, f) in boxes) {
            if (f.ws <= 0) continue
            val box = object : Box { override val x0 = f.cx - f.ws / 2; override val x1 = f.cx + f.ws / 2; override val y0 = f.y0; override val y1 = f.y1 }
            for (b in bs) {
                if (rectPt(box, b.x, b.y) < b.d / 2 + 2) out += "row hits $name ${b.id}"
                if (b.plate != null && rectRect(box, b.plate) < 2) out += "row hits plate $name ${b.id}"
            }
        }
        for (s in p.small) if (s.kind == Kind.MINI && s.spot == null) out += "mini hidden ${s.id}"
        if (p.small.size + bs.size != TellTales.IDS.size) out += "lamps lost"
        return out
    }
}
