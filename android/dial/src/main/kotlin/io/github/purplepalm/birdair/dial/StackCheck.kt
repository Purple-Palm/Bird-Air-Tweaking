package io.github.purplepalm.birdair.dial

import io.github.purplepalm.birdair.core.TellTales
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * What must never happen on «Крупные лампы» (the tests' checks, on the demo scenarios and on replayed rides): a chip or
 * a text past the round edge, overlapping chips or texts on a still frame, a main lamp other than [DialLamps.top].
 * [WIDTH] measures without fonts (Roboto's widths, a little generous) so the checks run on the JVM.
 */
object StackCheck {
    val WIDTH = TextWidth { text, px, weight, _ ->
        var w = 0f
        for (c in text) w += px * when {
            c == ' ' || c == TellTales.NBSP || c == Dial.NNBSP -> 0.26f
            c.isDigit() -> 0.57f
            c in ",.·:" -> 0.27f
            c.isUpperCase() -> if (weight >= 600) 0.7f else 0.66f
            else -> if (weight >= 600) 0.6f else 0.55f
        }
        w
    }

    fun scene(s: DialState, tl: StackTimeline, t: Long, calm: Boolean = false): StackScene =
        StackScene.build(s, tl, t, calm, WIDTH, s.power.fraction, s.speed.fraction())

    /** Nothing moving at [t]: no change of the set ≤ 1 s ago, none queued, no startup. */
    fun settled(tl: StackTimeline, t: Long): Boolean {
        val k = tl.index(t)
        if (tl.snaps.last().t > t || (t - tl.snaps[k].t < 1_000 && tl.snaps[k].t != StackTimeline.NEVER)) return false
        return tl.bootAt?.let { t - it > Stack.BOOT_TOTAL } ?: true
    }

    private class Box(val l: Float, val t: Float, val r: Float, val b: Float, val what: String)

    private fun boxes(sc: StackScene): List<Box> = (sc.lines + sc.status + sc.text.filterIsInstance<TextEl>()).filter { it.alpha >= 0.05f }.map { e ->
        val w = WIDTH.width(e.text, e.px, e.weight, e.tnum)
        val x0 = when (e.anchor) { Anchor.START -> e.x; Anchor.CENTER -> e.x - w / 2; Anchor.END -> e.x - w }
        Box(x0, e.baseline - 0.74f * e.px, x0 + w, e.baseline + 0.2f * e.px, "«${e.text}»")
    }

    /** Any frame: chips clear of the power arc (r 236 at most), texts inside the speed track (r 196). */
    fun outside(sc: StackScene): List<String> {
        val out = ArrayList<String>()
        for (e in sc.all) if (e is ChipEl && e.kind != Kind.STAND && e.d > 20 && hypot(e.x - 240, e.y - 240) + e.d / 2 > 236f) {
            out += "${e.lamp.id} out at ${e.x},${e.y} d ${e.d}"
        }
        for (b in boxes(sc)) for ((x, y) in listOf(b.l to b.t, b.r to b.t, b.l to b.b, b.r to b.b)) {
            if (hypot(x - 240, y - 240) > 196f) { out += "${b.what} reaches the edge at $x,$y"; break }
        }
        return out
    }

    /** A still frame: the main chip is [DialLamps.top], chips, texts and the battery do not overlap. */
    fun overlaps(sc: StackScene, s: DialState): List<String> {
        val out = ArrayList<String>()
        val chips = (sc.hero + sc.sec + sc.stand).filterIsInstance<ChipEl>().filter { it.alpha > 0.5f && it.d > 20 }
        val heroes = chips.filter { it.kind == Kind.HERO }
        val top = s.lamps.top
        if (top != null && heroes.singleOrNull()?.lamp?.id != top.id) out += "the main lamp is ${heroes.map { it.lamp.id }}, not ${top.id}"
        if (top == null && heroes.isNotEmpty()) out += "a main lamp ${heroes.map { it.lamp.id }} without a top lamp"
        val discs = chips.map { Pos(it.x, it.y, it.d) to it.lamp.id } + sc.sec.filterIsInstance<PlusEl>().map { Pos(it.x, it.y, it.d) to it.text }
        for (i in discs.indices) for (j in i + 1 until discs.size) if (gap(discs[i].first, discs[j].first) < 0f) out += "${discs[i].second} overlaps ${discs[j].second}"
        val bt = sc.battery.b
        val boxes = boxes(sc) + Box(bt.x, bt.y, bt.x + bt.w + bt.h * 0.18f + 2, bt.y + bt.h, "the battery")
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]; val b = boxes[j]
            if (min(a.r, b.r) - max(a.l, b.l) > 0.5f && min(a.b, b.b) - max(a.t, b.t) > 0.5f) out += "${a.what} overlaps ${b.what}"
        }
        for (b in boxes) for ((c, id) in discs) {
            val nx = c.x.coerceIn(b.l, b.r)
            val ny = c.y.coerceIn(b.t, b.b)
            if (hypot(nx - c.x, ny - c.y) < c.d / 2 - 0.5f) out += "${b.what} overlaps $id"
        }
        return out
    }

    /** Between two chips' edges (negative: they overlap). */
    fun gap(a: Pos, b: Pos): Float = hypot(a.x - b.x, a.y - b.y) - a.d / 2 - b.d / 2
}
