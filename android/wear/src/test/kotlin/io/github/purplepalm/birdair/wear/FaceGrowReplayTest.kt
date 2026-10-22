package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.dial.DialState
import io.github.purplepalm.birdair.dial.GrowFrame
import io.github.purplepalm.birdair.dial.GrowLayout
import io.github.purplepalm.birdair.dial.GrowMeasure
import io.github.purplepalm.birdair.dial.GrowMode
import io.github.purplepalm.birdair.dial.GrowMotion
import io.github.purplepalm.birdair.dial.GrowScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * «Растущие лампы» on real rides ([DialReplay]: the phone's pipeline, the watch's cues and lamp buzzes): every state's
 * layout keeps the mock's invariants, every frame (and 150 / 350 / 700 ms into each change) stays on the screen, the
 * main lamp is TellTales.top's, and the moments that matter wear the right tablet. FaceGrowReplayShots draws them.
 */
class FaceGrowReplayTest {
    /** Roboto, roughly (FaceGrowLayoutTest checks such a model against the mock's browser). */
    private val model = GrowMeasure { text, px, weight -> text.length * 0.53 * px * (1 + (weight - 400) / 3000.0) }

    private class Seen {
        var states = 0
        var frames = 0
        var maxBadges = 0
        var red = 0
        val plates = HashMap<String, MutableSet<String>>()
        val problems = ArrayList<String>()
    }

    private fun ride(path: String): Seen {
        RideReplay.assume(path)
        val scene = GrowScene(model)
        val seen = Seen()
        DialReplay.replay(path) { s ->
            seen.states++
            val lit = GrowScene.known(s.lamps.lit.map { it.lamp })
            val input = scene.input(s, lit, false)
            val plan = scene.plan(input)
            for (p in GrowLayout.problems(input, plan)) if (seen.problems.size < 20) seen.problems += "${s.nowMs} ${lit.map { it.id + ":" + it.level }} $p"
            seen.maxBadges = maxOf(seen.maxBadges, plan.badges.size)
            val times = if (s.lamps.changedMs == s.nowMs) listOf(0L, 150L, 350L, 700L) else listOf(0L)
            for (dt in times) {
                val f = scene.frame(s, s.nowMs + dt, s.speed.fraction(), s.power.fraction)
                seen.frames++
                check(f, s, "${s.nowMs}+$dt", seen)
            }
            s.lamps.top?.let { top ->
                if (top.level == LampLevel.RED) seen.red++
                val f = scene.frame(s, s.nowMs + GrowMotion.SETTLE_MS, s.speed.fraction(), s.power.fraction)
                f.sprites.first { it.id == top.id }.plateText?.let { seen.plates.getOrPut(top.id + ":" + top.lamp.text) { HashSet() } += it }
            }
        }
        println("$path: ${seen.states} states, ${seen.frames} frames, ≤ ${seen.maxBadges} badges, ${seen.red} red; tablets ${seen.plates}")
        return seen
    }

    private fun check(f: GrowFrame, s: DialState, tag: String, seen: Seen) {
        fun bad(what: String) { if (seen.problems.size < 20) seen.problems += "$tag $what" }
        for (sp in f.sprites) {
            if (!(sp.x.isFinite() && sp.y.isFinite() && sp.d.isFinite() && sp.q.isFinite())) bad("${sp.id} not finite")
            if (sp.lit && !sp.hide && hypot(sp.x - 240f, sp.y - 240f) + sp.d / 2 > 240f) bad("${sp.id} off the screen")
            if (sp.mode == GrowMode.LOUD && sp.plate != null && sp.plateText.isNullOrBlank()) bad("${sp.id} no tablet text")
        }
        val top = s.lamps.top
        if (top != null && f.sprites.first { it.id == top.id }.mode != GrowMode.LOUD) bad("the main lamp ${top.id} is not a badge")
        for (r in listOfNotNull(f.clock, f.bat, f.range, f.det, f.msg, f.pow)) if (!(r.cx.isFinite() && r.y.isFinite() && r.s > 0.5f)) bad("row ${r.cx} ${r.y} ${r.s}")
    }

    /** 20.10, 100 % to the BMS's cut: the struck battery wears «BMS», «Сейчас отключится» the voltage. */
    @Test fun theRideToZero() {
        val r = ride(RideReplay.TO_ZERO)
        assertEquals(emptyList<String>(), r.problems)
        assertTrue(r.states > 10_000)
        assertTrue(r.red > 0)
        assertEquals(setOf("BMS"), r.plates["battery:" + TellTales.T_BMS_CUT])
        assertTrue(r.maxBadges >= 1)
    }

    /** 15.10, two-up to the battery's over-temperature cut at 57 / 61 °C: the red thermometer wears the °C. */
    @Test fun theHotCutoff() {
        val r = ride(CUTOFF_HOT)
        assertEquals(emptyList<String>(), r.problems)
        val hot = r.plates["temp:" + TellTales.T_OVERHEAT].orEmpty()
        assertTrue("$hot", hot.isNotEmpty() && hot.all { it.endsWith("°C") })
    }

    /** 16.10, two-up, half a battery: «Мало тяги» wears «Тяга», the full pack under regen «Полная». */
    @Test fun aTwoUpRide() {
        val r = ride(TWO_UP_HALF)
        assertEquals(emptyList<String>(), r.problems)
        assertTrue("${r.plates}", r.plates.filterKeys { it.startsWith("traction:") }.values.flatten().all { it == "Тяга" })
        assertEquals(setOf("Полная"), r.plates["overvolt:" + TellTales.T_FULL])
    }

    companion object {
        const val CUTOFF_HOT = "logs/20261015-155523-log-app.json.gz"
        const val TWO_UP_HALF = "logs/20261016-135138-log-app.json.gz"
    }
}
