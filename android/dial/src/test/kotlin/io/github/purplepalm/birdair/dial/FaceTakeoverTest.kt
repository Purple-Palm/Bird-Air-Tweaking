package io.github.purplepalm.birdair.dial

import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.PowerState
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.dial.TakeoverLayout.Frame
import io.github.purplepalm.birdair.dial.TakeoverLayout.Icon
import io.github.purplepalm.birdair.dial.TakeoverLayout.Role
import io.github.purplepalm.birdair.dial.TakeoverLayout.Txt
import org.junit.Test
import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Захват экрана» laid out (TakeoverLayout, the rough Roboto): the gallery's scenarios, any lamps, the takeover's timing. */
class FaceTakeoverTest {
    private val m = TakeoverLayout.ROUGH
    private val mph = TellTales.MPH
    private val kmh = TellTales.KMH

    private fun tele(
        lamps: List<Lamp>, units: String = mph, kmhSpeed: Double? = 22.0, mode: Int? = 3, pct: Double? = 56.3,
        range: Boolean = true, volts: Double? = 38.0, temp: Double? = 31.0, streaming: Boolean = true,
    ) = Telemetry(
        link = Link.CONNECTED, streaming = streaming, units = units, speedKmh = kmhSpeed, mode = mode, batteryPctFine = pct,
        batteryPct = pct?.let { floor(it) }, voltage = volts, temp1C = temp, temp2C = temp, powerW = 300.0, powerFastW = 320.0,
        currentA = 8.0, currentFastA = 8.4, peakW = 640.0, range = if (range) RangeEstimate(5.6, 3.5, 67.0, 12.0, "recent") else null,
        lamps = lamps, powerState = PowerState.TRACTION,
    )

    private fun lamp(id: String, level: LampLevel, text: String, value: Double? = null, rising: Boolean = false, units: String = mph) =
        TellTales.lamp(id, level, text, value, rising, units)

    private val hot = lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 49.0)
    private val cutting = lamp(TellTales.BATTERY, LampLevel.RED, TellTales.T_CUTTING, 31.5)
    private val svc = lamp(TellTales.SERVICE, LampLevel.AMBER, "Ёмкость 75 % от паспорта", 75.0)

    private fun frame(s: DialState, now: Long) = TakeoverLayout.frame(s, now, m)

    private fun assertFits(f: Frame, what: String) {
        val p = TakeoverLayout.problems(f, m)
        assertTrue(p.isEmpty(), "$what: $p")
    }

    /** The centre's first place is TellTales.top(); a yellow top grows in place and its text is the line. */
    private fun assertMainIsTop(s: DialState, f: Frame, what: String) {
        if (!f.settled) return
        val top = s.lamps.top
        if (top == null) { assertTrue(f.slot.isEmpty(), "$what: ${f.slot} without a top lamp"); return }
        if (top.level == LampLevel.YELLOW) {
            if (f.slot.isEmpty()) {
                assertEquals(top.id, f.main, what)
                if (top.lamp.text.isNotBlank() && s.cues.tapAtMs == null) assertEquals(top.lamp.text, f.line, what)
            }
            return
        }
        assertEquals(top.id, f.main, what)
        if (f.takeover > 0f) assertEquals(top.id, f.ops.filterIsInstance<Icon>().single { it.role == Role.TAKEOVER }.id, what)
    }

    @Test fun theGalleryScenariosFitEveryFrame() {
        for (sc in DialScenarios.ALL) {
            var settled = 0
            var t = 0L
            while (t <= sc.lengthMs + 9_000) {
                val s = sc.at(t)
                val f = frame(s, t)
                assertFits(f, "${sc.key} at $t ms")
                assertMainIsTop(s, f, "${sc.key} at $t ms")
                if (f.settled) settled++
                t += 25
            }
            assertTrue(settled > 20, "${sc.key}: $settled settled frames")
        }
    }

    @Test fun theGalleryScenariosShowWhatTheMockShows() {
        fun end(key: String) = DialScenarios[key].let { it.changeMs + 1_200 }.let { t -> frame(DialScenarios[key].at(t), t) }
        // calm: no slot, the battery big
        val norm = end("norm")
        assertTrue(norm.slot.isEmpty() && norm.takeover == 0f)
        assertEquals(140f, norm.ops.filterIsInstance<TakeoverLayout.Battery>().single().w)
        // yellow: a circle where it is, its text on the line
        val warm = end("warm")
        assertTrue(warm.slot.isEmpty())
        assertEquals(TellTales.T_WARM_RISING, warm.line)
        assertTrue(warm.ops.any { it is TakeoverLayout.Circle && it.role == Role.YELLOW && it.r == TakeoverLayout.YEL_PX / 2 })
        assertTrue(warm.ops.any { it is TakeoverLayout.Tri })
        // two ambers side by side, 72 px, their segments on the bezel
        val amber = end("amber")
        assertEquals(listOf(TellTales.TEMP, TellTales.TRACTION), amber.slot)
        assertEquals(listOf(72f, 72f), amber.ops.filterIsInstance<Icon>().filter { it.role == Role.SLOT }.map { it.px })
        assertEquals(2, amber.ops.filterIsInstance<TakeoverLayout.Seg>().size)
        // red: the takeover, 150 px, the words in capitals, the ring
        val critical = end("critical")
        assertTrue(critical.takeover > 0.99f)
        assertEquals(TakeoverLayout.BIG_PX, critical.ops.filterIsInstance<Icon>().single { it.role == Role.TAKEOVER }.px)
        assertTrue(critical.ops.filterIsInstance<Txt>().any { it.text == "СЕЙЧАС" })
        assertTrue(critical.ops.any { it is TakeoverLayout.Ring })
        // three ambers: two in the slot, «+1», the third lit 40 px in its column
        val many = end("many")
        assertEquals(listOf(TellTales.CONSUMPTION, TellTales.OVERVOLT), many.slot)
        assertEquals(1, many.more)
        assertTrue(many.ops.filterIsInstance<Txt>().any { it.text == "+1" })
        assertEquals(TakeoverLayout.STRONG_PX, many.ops.filterIsInstance<Icon>().single { it.id == TellTales.LINK && it.role == Role.COLUMN }.px)
        // the rumble: comets, the dot red; the blue lamp in the slot
        val over = end("overspeed")
        assertTrue(over.ops.any { it is TakeoverLayout.Comets })
        assertTrue(over.ops.filterIsInstance<TakeoverLayout.Speed>().single().over)
        assertEquals(listOf(TellTales.OVERSPEED), over.slot)
        // charging: the plug in the centre, a band through the fill
        val charging = end("charging")
        assertEquals(listOf(TellTales.CHARGING), charging.slot)
        assertNull(charging.ops.filterIsInstance<TakeoverLayout.Battery>().single().shimmer)        // between two bands
        val band = frame(DialScenarios["charging"].at(5_000), 5_000).ops.filterIsInstance<TakeoverLayout.Battery>().single().shimmer
        assertNotNull(band)
        assertEquals(1_000f / TakeoverLayout.SHIMMER_MS, band, 1e-4f)
    }

    @Test fun theRedTakesOverForEightSecondsAndAgainOnARepeatBuzz() {
        val b = DialBuilder()
        b.build(tele(listOf(hot, svc)), Health.LIVE, 0)
        val s = b.build(tele(listOf(hot, cutting, svc)), Health.LIVE, 10_000, cues = DialCues(buzz = LampBuzz(10_000, LampLevel.RED)))
        assertEquals(0f, frame(s, 10_000).takeover)
        assertTrue(frame(s, 10_300).takeover in 0.1f..0.9f)
        assertTrue(frame(s, 10_700).takeover > 0.99f)
        assertTrue(frame(s, 17_990).takeover > 0.99f)
        val calm = frame(s, 18_700)
        assertEquals(0f, calm.takeover)                                               // the calm red layout
        assertEquals(listOf(TellTales.BATTERY, TellTales.TEMP), calm.slot)
        val icons = calm.ops.filterIsInstance<Icon>().filter { it.role == Role.SLOT }
        assertEquals(80f, icons.single { it.id == TellTales.BATTERY }.px)
        assertEquals(62f, icons.single { it.id == TellTales.TEMP }.px)
        assertFits(calm, "calm red")
        // the repeat buzz 30 s later: the takeover again
        val s2 = b.build(tele(listOf(hot, cutting, svc)), Health.LIVE, 40_000, cues = DialCues(buzz = LampBuzz(40_000, LampLevel.RED)))
        assertEquals(0f, frame(s2, 40_000).takeover)
        assertTrue(frame(s2, 40_700).takeover > 0.99f)
        assertFits(frame(s2, 41_000), "the repeat")
        // it goes out: the takeover folds back with it, then the slot holds the amber alone
        val s3 = b.build(tele(listOf(hot, svc)), Health.LIVE, 42_000, cues = DialCues(buzz = LampBuzz(40_000, LampLevel.RED)))
        val folding = frame(s3, 42_200).takeover
        assertTrue(folding in 0.01f..0.99f, "$folding")
        assertEquals(0f, frame(s3, 43_000).takeover)
        assertEquals(listOf(TellTales.TEMP), frame(s3, 44_000).slot)
    }

    @Test fun aLampLeavingTheSlotForItsColumnHandsOverAsItLands() {
        val warm = lamp(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_WARM, 46.0)
        val b = DialBuilder()
        b.build(tele(listOf(hot)), Health.LIVE, 0)
        val s = b.build(tele(listOf(warm)), Health.LIVE, 5_000)                        // cooled: amber → yellow
        val leaving = { t: Long -> frame(s, t).ops.filterIsInstance<Icon>().firstOrNull { it.role == Role.SLOT } }
        val circle = { t: Long -> frame(s, t).ops.filterIsInstance<TakeoverLayout.Circle>().firstOrNull { it.role == Role.YELLOW } }
        assertEquals(1f, leaving(5_100)!!.alpha)
        assertNull(circle(5_100))
        assertTrue(leaving(5_350)!!.alpha < 1f && circle(5_350) != null)            // it fades as the circle grows
        assertNull(leaving(5_700))
        assertEquals(TakeoverLayout.YEL_PX / 2, circle(6_500)!!.r)
        for (t in 5_000L..7_000L step 20) assertFits(frame(s, t), "cooling at $t")
        // a blue one in the slot, pushed out by an amber: lit 34 px in its column after the flight
        val fast = lamp(TellTales.OVERSPEED, LampLevel.BLUE, TellTales.T_OVERSPEED, 29.0)
        val b2 = DialBuilder()
        b2.build(tele(listOf(fast)), Health.LIVE, 0)
        assertEquals(listOf(TellTales.OVERSPEED), frame(b2.build(tele(listOf(fast)), Health.LIVE, 2_000), 2_000).slot)
        val s2 = b2.build(tele(listOf(hot, fast)), Health.LIVE, 3_000)
        val end = frame(s2, 4_500)
        assertEquals(listOf(TellTales.TEMP), end.slot)
        assertEquals(TakeoverLayout.LIT_PX, end.ops.filterIsInstance<Icon>().single { it.id == TellTales.OVERSPEED && it.role == Role.COLUMN }.px)
        for (t in 3_000L..4_500L step 20) assertFits(frame(s2, t), "pushed out at $t")
    }

    @Test fun theRedPulsesAtOneHertzWithItsBuzzAndTheTextNeverBlinks() {
        val b = DialBuilder()
        b.build(tele(listOf(svc)), Health.LIVE, 0)
        // the lamp lit one message before its buzz: the pulse runs from the buzz
        val s = b.build(tele(listOf(cutting, svc)), Health.LIVE, 9_800, cues = DialCues(buzz = LampBuzz(10_000, LampLevel.RED)))
        val ring = { t: Long -> frame(s, t).ops.filterIsInstance<TakeoverLayout.Ring>().single().alpha }
        assertEquals(1f, ring(12_000), 1e-3f)
        assertEquals(0.55f, ring(12_500), 1e-3f)
        assertEquals(0.775f, ring(12_250), 1e-3f)
        assertEquals(1f, ring(13_000), 1e-3f)
        for (t in 11_000L..14_000L step 37) assertTrue(ring(t) in 0.549f..1.001f)
        val texts = { t: Long -> frame(s, t).ops.filterIsInstance<Txt>().map { it.text to it.alpha } }
        val first = texts(12_000)
        for (t in 12_000L..13_000L step 50) assertEquals(first, texts(t), "at $t")
        // the halo pulses, the icon does not dim
        val icon = { t: Long -> frame(s, t).ops.filterIsInstance<Icon>().single { it.role == Role.TAKEOVER } }
        assertEquals(1f, icon(12_500).alpha)
        assertTrue(icon(12_500).halo < icon(12_000).halo)
    }

    @Test fun ambientIsStaticOutlinesWithoutFrames() {
        val sc = DialScenarios["ambient"]
        val s = sc.at(sc.changeMs + 1_200)
        val f1 = frame(s, sc.changeMs + 1_200)
        val f2 = frame(s, sc.changeMs + 6_200)
        assertEquals(f1.dim, f2.dim)
        assertEquals(f1.top, f2.top)
        assertTrue(f1.ops.filterIsInstance<Icon>().all { it.outline > 0f && it.halo == 0f })
        assertTrue(f1.ops.none { it is TakeoverLayout.Glow || it is TakeoverLayout.Ring || it is TakeoverLayout.Comets })
        assertNull(TakeoverLayout.wakeAt(s, sc.changeMs + 1_200))
        // a red one in ambient: the takeover's layout, still, no ring
        val b = DialBuilder()
        b.build(tele(emptyList()), Health.LIVE, 0)
        val red = b.build(tele(listOf(cutting)), Health.LIVE, 1_000, ambient = true, burnInDp = BurnIn.offset(3))
        val fr = frame(red, 1_000)
        assertEquals(1f, fr.takeover)
        assertTrue(fr.ops.none { it is TakeoverLayout.Ring })
        assertNull(TakeoverLayout.wakeAt(red, 1_000))
        assertFits(fr, "ambient red")
    }

    @Test fun framesOnlyWhileSomethingMoves() {
        val eco = lamp(TellTales.ECO, LampLevel.GREEN, "ECO", 160.0)
        val b = DialBuilder()
        val s0 = b.build(tele(listOf(eco)), Health.LIVE, 0)
        assertEquals(300L, TakeoverLayout.wakeAt(s0, 300))                            // its entrance
        assertNull(TakeoverLayout.wakeAt(s0, 5_000))                                  // all still: no frames
        val red = b.build(tele(listOf(eco, cutting)), Health.LIVE, 6_000)
        assertEquals(60_000L, TakeoverLayout.wakeAt(red, 60_000))                     // red pulses on
        assertNull(TakeoverLayout.wakeAt(red.copy(ambient = true), 60_000))
        // the ▲: hops at its appearance and every 10 s, nothing in between
        val b2 = DialBuilder()
        val warm = b2.build(tele(listOf(lamp(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_WARM_RISING, 42.0, rising = true))), Health.LIVE, 1_000)
        assertEquals(1_500L, TakeoverLayout.wakeAt(warm, 1_500))
        assertEquals(11_000L, TakeoverLayout.wakeAt(warm, 3_000))
        assertEquals(11_500L, TakeoverLayout.wakeAt(warm, 11_500))
        assertEquals(21_000L, TakeoverLayout.wakeAt(warm, 12_000))
        // the rumble while live; on old data it does not move
        val fast = DialBuilder().build(tele(emptyList()), Health.LIVE, 0, cues = DialCues(rumble = true, rumbleSinceMs = 0))
        assertEquals(5_000L, TakeoverLayout.wakeAt(fast, 5_000))
        assertNull(TakeoverLayout.wakeAt(fast.copy(health = Health.SILENT), 5_000))
        // the limiter's line stays 3 s, then still
        val tap = DialBuilder().build(tele(emptyList()), Health.LIVE, 0, cues = DialCues(tapAtMs = 1_000))
        assertEquals(3_000L, TakeoverLayout.wakeAt(tap, 3_000))
        assertNull(TakeoverLayout.wakeAt(tap, 5_000))
        // charging: one band through the fill every 4 s
        val chg = DialBuilder().build(tele(listOf(lamp(TellTales.CHARGING, LampLevel.GREEN, "Зарядка 1,7 А", 1.7)), kmhSpeed = 0.0), Health.LIVE, 0)
        assertEquals(8_000L, TakeoverLayout.wakeAt(chg, 6_000))
        assertEquals(8_500L, TakeoverLayout.wakeAt(chg, 8_500))
    }

    @Test fun theLimiterTapAndTheRumbleComeFromTheCues() {
        val b = DialBuilder()
        val s = b.build(tele(emptyList(), kmhSpeed = 15.6 * Fmt.MILE), Health.LIVE, 1_000, cues = DialCues(tapAtMs = 1_000))
        assertTrue(frame(s, 1_100).ops.any { it is TakeoverLayout.Flash })
        assertTrue(frame(s, 1_300).ops.filterIsInstance<TakeoverLayout.Speed>().single().tickFlash > 0f)
        assertEquals("Ограничитель · 15,6 mph", frame(s, 1_600).line)
        assertNull(frame(s, 4_500).line)
        val sp = frame(s, 4_500).ops.filterIsInstance<TakeoverLayout.Speed>().single()
        assertTrue(sp.hot && sp.pill)
        assertEquals("15,6", sp.label)
        assertTrue(frame(s, 4_500).ops.none { it is TakeoverLayout.Flash })
        // km/h: the pill near the limit in km/h, whole numbers below it
        val k = DialBuilder().build(tele(emptyList(), units = kmh, kmhSpeed = 24.9), Health.LIVE, 0)
        assertEquals("24,9", frame(k, 0).ops.filterIsInstance<TakeoverLayout.Speed>().single().label)
        val slow = DialBuilder().build(tele(emptyList(), units = kmh, kmhSpeed = 10.4), Health.LIVE, 0)
        assertEquals("10", frame(slow, 0).ops.filterIsInstance<TakeoverLayout.Speed>().single().label)
        // the rumble: comets and the red dot only while it runs on live data
        val r = DialBuilder().build(tele(emptyList(), kmhSpeed = 18.2 * Fmt.MILE), Health.LIVE, 0, cues = DialCues(rumble = true, rumbleSinceMs = 0))
        assertTrue(frame(r, 600).ops.any { it is TakeoverLayout.Comets })
        assertTrue(frame(r, 600).ops.filterIsInstance<TakeoverLayout.Speed>().single().over)
        val old = DialBuilder().build(tele(emptyList(), kmhSpeed = 18.2 * Fmt.MILE), Health.RECONNECTING, 0, cues = DialCues(rumble = true, rumbleSinceMs = 0))
        assertTrue(frame(old, 600).ops.none { it is TakeoverLayout.Comets })
    }

    @Test fun missingValuesAreLeftOut() {
        val s = DialBuilder().build(tele(listOf(hot), kmhSpeed = null, mode = null, pct = null, range = false, volts = null, temp = null), Health.LIVE, 0)
        val f = frame(s, 2_000)
        val sp = f.ops.filterIsInstance<TakeoverLayout.Speed>().single()
        assertNull(sp.fraction); assertNull(sp.label); assertNull(sp.limitDeg)
        assertTrue(f.ops.filterIsInstance<Txt>().none { it.text.startsWith("≈") })
        assertTrue(f.ops.none { it is TakeoverLayout.Parts })
        val bat = f.ops.filterIsInstance<TakeoverLayout.Battery>().single()
        assertNull(bat.pct)
        assertEquals("— %", bat.text)
        assertFits(f, "nothing known")
    }

    @Test fun oldDataDimsTheValuesButNotTheStatus() {
        val b = DialBuilder()
        val s = b.build(tele(listOf(hot)), Health.NO_PHONE, 0)
        val f = frame(s, 2_000)
        assertEquals(0.35f, f.alpha)
        val status = f.top.filterIsInstance<Txt>().filter { it.role == Role.STATUS }
        assertEquals(listOf("нет данных", "с телефона"), status.map { it.text })
        assertTrue(f.dim.none { it is Txt && it.role == Role.STATUS })
        assertFits(f, "no phone")
        val re = frame(b.build(tele(listOf(hot)), Health.RECONNECTING, 3_000), 5_000)
        assertEquals(listOf("переподключаюсь"), re.top.filterIsInstance<Txt>().map { it.text })
        // in the takeover the status stands over the %
        val red = DialBuilder().build(tele(listOf(cutting)), Health.SILENT, 0)
        val fr = frame(red, 2_000)
        val st = fr.top.filterIsInstance<Txt>().single()
        val pct = fr.dim.filterIsInstance<TakeoverLayout.Battery>().single()
        assertTrue(st.baseline + 0.2f * st.px < pct.ty - 0.74f * pct.px, "${st.baseline} over ${pct.ty}")
        assertFits(fr, "silent takeover")
        // live: the time
        val live = frame(DialBuilder().build(tele(emptyList()), Health.LIVE, 0, clock = "21:41"), 0)
        assertEquals(listOf("21:41"), live.top.filterIsInstance<Txt>().map { it.text })
        assertEquals(1f, live.alpha)
    }

    @Test fun theBulbCheckSweepsThenTheRealLampsComeIn() {
        val b = DialBuilder()
        b.build(tele(listOf(hot)), Health.LIVE, 0)
        val s = b.build(tele(listOf(hot)), Health.LIVE, 5_000, bulbAtMs = 5_000, bulbOn = true)
        val during = frame(s, 6_400)
        assertTrue(during.slot.isEmpty())
        assertEquals(12, during.top.filterIsInstance<Icon>().count { it.role == Role.BULB })
        assertEquals(12, during.top.filterIsInstance<TakeoverLayout.Seg>().size)
        assertEquals(Dial.BULB_TEXT, during.line)
        val after = frame(s, 7_800)
        assertEquals(listOf(TellTales.TEMP), after.slot)
        assertTrue(after.top.none { it is Icon })
        // the slot's entrance runs from the check's end
        val entering = frame(s, 6_700).ops.filterIsInstance<Icon>().single { it.role == Role.SLOT }
        assertTrue(entering.px < 80f)
    }

    @Test fun longTextsShrinkSplitOrEllipsizeInside() {
        val long = Lamp(TellTales.LINK, LampLevel.AMBER, "Очень длинный текст лампы", null, title = "Сейчасотключится!", valueText = "1 234 567,8 Вт·ч/км", detail = "подпись в двадцать четыре")
        val pairs = listOf(
            listOf(cutting, hot), listOf(cutting, long), listOf(hot, long),
            listOf(lamp(TellTales.TEMP, LampLevel.RED, TellTales.T_OVERHEAT, 57.0), lamp(TellTales.BATTERY, LampLevel.RED, TellTales.T_BMS_CUT, 31.2)),
            listOf(lamp(TellTales.LINK, LampLevel.AMBER, TellTales.T_DASH_NO_BMS), lamp(TellTales.TRACTION, LampLevel.AMBER, TellTales.T_SAG, 31.9)),
            listOf(long),
        )
        for (units in listOf(mph, kmh)) for (lamps in pairs) {
            val b = DialBuilder()
            b.build(tele(emptyList(), units = units), Health.LIVE, 0)
            val s = b.build(tele(lamps.sortedBy { TellTales.IDS.indexOf(it.id) }, units = units), Health.LIVE, 1_000, cues = DialCues(buzz = LampBuzz(1_000, LampLevel.RED)))
            for (t in listOf(2_500L, 9_800L, 20_000L)) {
                val f = frame(s, t)
                assertFits(f, "${lamps.map { it.id }} $units at $t")
                assertMainIsTop(s, f, "${lamps.map { it.id }} at $t")
            }
        }
        // «Сейчас отключится» beside another lamp takes two lines
        val b = DialBuilder()
        val s = b.build(tele(listOf(lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 49.0), lamp(TellTales.TRACTION, LampLevel.AMBER, TellTales.T_LIMIT, 15.8))), Health.LIVE, 0)
        val words = frame(s, 2_000).ops.filterIsInstance<Txt>().filter { it.role == Role.SLOT && it.id == TellTales.TEMP }.map { it.text }
        assertEquals(listOf("Батарея", "горячая", "49 °C"), words)
    }

    /** Lamps as TellTales lights them (and an older phone's, and some longer than the limits), any set of them. */
    private fun randomLamps(r: Random, units: String): List<Lamp> {
        fun l(id: String, level: LampLevel, text: String, value: Double? = null, rising: Boolean = false) = lamp(id, level, text, value, rising, units)
        val options: Map<String, List<Lamp>> = mapOf(
            TellTales.TEMP to listOf(
                l(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_WARM, 41.0), l(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_WARM_RISING, 43.0, true),
                l(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_RISING, 36.0, true), l(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 49.0),
                l(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT_RISING, 51.0, true), l(TellTales.TEMP, LampLevel.RED, TellTales.T_OVERHEAT, 57.0),
            ),
            TellTales.BATTERY to listOf(
                l(TellTales.BATTERY, LampLevel.AMBER, TellTales.T_LOW, 33.1), l(TellTales.BATTERY, LampLevel.AMBER, "Заряд 12,3 %", 12.3),
                l(TellTales.BATTERY, LampLevel.RED, TellTales.T_CUTTING, 31.4), l(TellTales.BATTERY, LampLevel.RED, TellTales.T_BMS_CUT, 31.249),
                l(TellTales.BATTERY, LampLevel.RED, TellTales.T_NO_BMS),
            ),
            TellTales.TRACTION to listOf(l(TellTales.TRACTION, LampLevel.AMBER, TellTales.T_LIMIT, 15.8), l(TellTales.TRACTION, LampLevel.AMBER, TellTales.T_SAG, 31.9)),
            TellTales.CONSUMPTION to listOf(l(TellTales.CONSUMPTION, LampLevel.AMBER, "Расход 24 Вт·ч/км", 24.0)),
            TellTales.WEAK to listOf(l(TellTales.WEAK, LampLevel.YELLOW, "Батарея устала: 0,38 Ом", 0.38)),
            TellTales.OVERVOLT to listOf(
                l(TellTales.OVERVOLT, LampLevel.AMBER, TellTales.T_FULL, 42.4), l(TellTales.OVERVOLT, LampLevel.AMBER, "Перенапряжение 42,5 В", 42.5),
                l(TellTales.OVERVOLT, LampLevel.RED, TellTales.T_OV_CUT, 43.5),
            ),
            TellTales.ECO to listOf(l(TellTales.ECO, LampLevel.GREEN, "ECO", 160.0)),
            TellTales.SERVICE to listOf(svc),
            TellTales.LINK to listOf(
                l(TellTales.LINK, LampLevel.AMBER, TellTales.T_LINK_LOST), l(TellTales.LINK, LampLevel.AMBER, TellTales.T_DASH_NO_BMS),
                l(TellTales.LINK, LampLevel.AMBER, TellTales.T_SILENT), l(TellTales.LINK, LampLevel.AMBER, "Код контроллера 0x21", 33.0),
            ),
            TellTales.OVERSPEED to listOf(l(TellTales.OVERSPEED, LampLevel.BLUE, TellTales.T_OVERSPEED, 29.3)),
            TellTales.CHARGING to listOf(l(TellTales.CHARGING, LampLevel.GREEN, "Зарядка 1,7 А", 1.7), l(TellTales.CHARGING, LampLevel.AMBER, TellTales.T_CHARGE_WAIT, 46.0)),
            TellTales.COLD to listOf(l(TellTales.COLD, LampLevel.BLUE, TellTales.T_COLD, -3.0)),
        )
        val out = ArrayList<Lamp>()
        for (id in TellTales.IDS) {
            if (r.nextFloat() > 0.42f) continue
            var x = options.getValue(id).random(r)
            when (r.nextInt(10)) {
                0 -> x = Lamp(x.id, x.level, x.text, x.value, x.rising)                                   // an older phone: no titles
                1 -> x = x.copy(title = "Ёмкостьбатареиуже".take(TellTales.TITLE_MAX), valueText = "−12 345,6 Вт·ч/mi", detail = "очень длинная подпись ла".take(TellTales.DETAIL_MAX))
            }
            out += x
        }
        return out
    }

    @Test fun anyLampsAnyLevelsAnyUnitsAnyHealth() {
        val r = Random(20261021)
        val healths = listOf(Health.LIVE, Health.LIVE, Health.LIVE, Health.SILENT, Health.RECONNECTING, Health.NO_PHONE, Health.IDLE, Health.CONNECTING)
        var takeovers = 0
        repeat(1_200) { n ->
            val units = if (r.nextBoolean()) mph else kmh
            val b = DialBuilder()
            val health = healths.random(r)
            val amb = r.nextInt(7) == 0
            val speed = if (r.nextInt(8) == 0) null else r.nextDouble(0.0, 34.0)
            fun t(lamps: List<Lamp>) = tele(
                lamps, units = units, kmhSpeed = speed, mode = listOf(1, 2, 3, null).random(r), pct = if (r.nextInt(9) == 0) null else r.nextDouble(0.0, 100.0),
                range = r.nextInt(6) != 0, volts = if (r.nextInt(9) == 0) null else r.nextDouble(30.0, 43.0), temp = if (r.nextInt(9) == 0) null else r.nextDouble(-5.0, 62.0),
            )
            b.build(t(emptyList()), health, 0, ambient = amb)
            val lamps = randomLamps(r, units)
            val cues = DialCues(
                tapAtMs = if (r.nextInt(5) == 0) 1_000L else null, rumble = r.nextInt(6) == 0, rumbleSinceMs = 900,
                buzz = if (r.nextInt(3) == 0) LampBuzz(1_000, if (r.nextBoolean()) LampLevel.RED else LampLevel.AMBER) else null,
            )
            val bulb = if (r.nextInt(8) == 0) 800L else null
            val s1 = b.build(t(lamps), health, 1_000, cues = cues, ambient = amb, bulbAtMs = bulb, bulbOn = bulb != null,
                burnInDp = if (amb) BurnIn.offset(n) else 0f to 0f, clock = "21:41")
            for (dt in listOf(0L, 150L, 350L, 600L, 1_200L, 2_500L, 4_500L, 9_500L, 30_000L)) {
                val f = frame(s1, 1_000 + dt)
                assertFits(f, "#$n ${lamps.map { it.id + ":" + it.level }} $units $health amb=$amb at +$dt")
                assertMainIsTop(s1, f, "#$n at +$dt")
                if (f.takeover > 0.99f) takeovers++
            }
            // some go out, some come
            val next = lamps.filter { r.nextBoolean() } + randomLamps(r, units).filter { x -> lamps.none { it.id == x.id } && r.nextInt(3) == 0 }
            val s2 = b.build(t(next.sortedBy { TellTales.IDS.indexOf(it.id) }), health, 40_000, ambient = amb, clock = "21:42")
            for (dt in listOf(0L, 100L, 300L, 500L, 800L, 1_500L, 3_000L)) {
                val f = frame(s2, 40_000 + dt)
                assertFits(f, "#$n then ${next.map { it.id + ":" + it.level }} at +$dt")
                assertMainIsTop(s2, f, "#$n then at +$dt")
            }
        }
        assertTrue(takeovers > 100, "$takeovers")
    }
}
