package io.github.purplepalm.birdair.wear

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.wear.compose.material.LocalTextStyle
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.PowerState
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.dial.BurnIn
import io.github.purplepalm.birdair.dial.CuePoints
import io.github.purplepalm.birdair.dial.DialBuilder
import io.github.purplepalm.birdair.dial.DialCues
import io.github.purplepalm.birdair.dial.DialState
import io.github.purplepalm.birdair.dial.Fmt
import io.github.purplepalm.birdair.dial.Health
import io.github.purplepalm.birdair.dial.LampBuzz
import io.github.purplepalm.birdair.dial.LocalFaceTextStyle
import io.github.purplepalm.birdair.dial.Stack
import io.github.purplepalm.birdair.dial.StackFace
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

/**
 * «Крупные лампы» beyond the shared scenarios: stack2's richer timelines (a step up with its sweep, the swap, a value
 * roll, a red repeat, the ✓, the startup) with the buzz as the watch plays it with this face ([Stack.LAND_MS] after the
 * change), the calmer variant, the data's health, km/h, low-bit ambient; and the three test rides drawn every second
 * (nothing may throw). wear/build/shots/stack/<name>-<ms>.png:
 * ./gradlew :wear:testDebugUnitTest -Pshots --tests '*StackShots*'.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w240dp-h240dp-round-watch-xhdpi")
class StackShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val dir = File(System.getProperty("shotDir") ?: "build/shots", "stack").apply { mkdirs() }
    private val state = mutableStateOf(DialState())
    private val at = mutableLongStateOf(0L)
    private val calm = mutableStateOf(false)
    private var bmp: Bitmap? = null
    private var started = false

    /** Once per test (the rule's content is set once); a new timeline starts by itself when the clock goes back. */
    private fun start() {
        if (started) return
        started = true
        rule.setContent {
            WatchTheme {
                CompositionLocalProvider(LocalFaceTextStyle provides LocalTextStyle.current) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        StackFace(state.value, Modifier.fillMaxSize(), nowMs = at.longValue, calm = calm.value)
                    }
                }
            }
        }
    }

    /** Draws [s] at [ms]; saved as [name] when given. */
    private fun frame(s: DialState, ms: Long, name: String? = null) {
        state.value = s
        at.longValue = ms
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = bmp?.takeIf { it.width == v.width && it.height == v.height } ?: Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { bmp = it }
        b.eraseColor(0)
        v.draw(Canvas(b))
        if (name != null) File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A demo timeline: lamps as [events] (ms → lamp; a lamp with level OFF goes out), the buzzes at [buzzes]. */
    private class Demo(
        val key: String, val lengthMs: Long, val tele: (Long) -> Tele, val events: List<Pair<Long, Lamp>>, val offMs: Long? = null,
        val buzzes: List<Pair<Long, LampLevel>> = emptyList(), val tapAt: Long? = null, val rumble: LongRange? = null,
        val bulbAt: Long? = null, val steady: List<Lamp> = listOf(SERVICE), val units: String = TellTales.MPH,
        val dynamic: (Long, Tele) -> List<Lamp> = { _, _ -> emptyList() },
    )

    private class Tele(val mph: Double, val watts: Double, val pct: Double, val rangeMi: Double, val volts: Double, val tempC: Double)

    /** [d]'s states every 20 ms. */
    private fun states(d: Demo, health: (Long) -> Health = { Health.LIVE }, ambient: Boolean = false, lowBit: Boolean = false): List<DialState> {
        val b = DialBuilder()
        val out = ArrayList<DialState>()
        var t = 0L
        var seq = 0L
        while (t <= d.lengthMs) {
            val x = d.tele(t)
            val lit = LinkedHashMap<String, Lamp>()
            for (l in d.steady) lit[l.id] = l
            for ((ms, l) in d.events) if (ms <= t) { if (l.level == LampLevel.OFF) lit.remove(l.id) else lit[l.id] = l }
            if (d.offMs != null && t >= d.offMs) lit.keys.retainAll(d.steady.map { it.id }.toSet())
            for (l in d.dynamic(t, x)) lit[l.id] = l
            val lamps = TellTales.IDS.mapNotNull { lit[it] }.map { TellTales.display(it, d.units) }
            val buzz = d.buzzes.lastOrNull { it.first <= t }?.let { LampBuzz(it.first, it.second) }
            val rOn = d.rumble?.contains(t) == true
            val h = health(t)
            out += b.build(
                tele(t, ++seq, x, lamps, d.units, h), h, t,
                cues = DialCues(tapAtMs = d.tapAt?.takeIf { it <= t }, rumble = rOn, rumbleSinceMs = if (rOn) d.rumble!!.first else null, buzz = buzz),
                bulbAtMs = d.bulbAt?.takeIf { it <= t }, bulbOn = d.bulbAt?.let { t - it in 0 until TellTales.BULB_CHECK_MS } == true,
                ambient = ambient, lowBit = lowBit, burnInDp = if (ambient) BurnIn.offset(3) else 0f to 0f, clock = "21:41",
            )
            t += 20
        }
        return out
    }

    private fun tele(ms: Long, seq: Long, x: Tele, lamps: List<Lamp>, units: String, h: Health): Telemetry {
        val amps = x.watts / x.volts
        val km = x.rangeMi * Fmt.MILE
        return Telemetry(
            t = ms, link = if (h == Health.RECONNECTING) Link.RECONNECTING else Link.CONNECTED, streaming = h == Health.LIVE, units = units,
            speedKmh = x.mph * Fmt.MILE, mode = 3, batteryPct = kotlin.math.floor(x.pct), voltage = x.volts,
            currentA = amps, powerW = x.watts, currentFastA = amps, powerFastW = x.watts, peakW = 700.0,
            remainingMah = 39.36 * x.pct, fullMah = 3936.0, temp1C = x.tempC - 2, temp2C = x.tempC, motorOn = x.watts > 20, seq = seq,
            range = RangeEstimate(km, x.rangeMi, km * 12.0, 12.0, "recent"), lamps = lamps, batteryPctFine = x.pct,
            powerState = when { x.mph < 1 -> null; x.watts > 20 -> PowerState.TRACTION; x.watts < -20 -> PowerState.REGEN; else -> PowerState.COAST },
        )
    }

    /** Draws [d] at [frames] (every state up to each, in order, as the watch would). */
    private fun play(d: Demo, frames: List<Long>, name: String = d.key, ss: List<DialState> = states(d), reduced: Boolean = false) {
        calm.value = reduced
        start()
        var i = 0
        for (ms in frames.sorted()) {
            while (i < ss.size && ss[i].nowMs <= ms) { frame(ss[i], ss[i].nowMs); i++ }
            frame(ss[maxOf(0, i - 1)], ms, "$name-$ms")
        }
    }

    private fun wob(t: Long, a: Double, per: Double) = a * sin(t / per)
    private fun lamp(id: String, level: LampLevel, text: String, value: Double? = null, rising: Boolean = false, units: String = TellTales.MPH) =
        TellTales.lamp(id, level, text, value, rising, units)
    private fun off(id: String) = Lamp(id, LampLevel.OFF)

    private val land = Stack.LAND_MS

    private fun warm() = Demo("warm", 6_200, { t -> Tele(14.1 + wob(t, 0.2, 800.0), 320 + wob(t, 30.0, 600.0), 52.7, 3.2, 37.4, if (t < 3300) 42.0 else 43.0) },
        listOf(300L to lamp(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_WARM_RISING, 42.0, true), 3300L to lamp(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_WARM_RISING, 43.0, true)),
        offMs = 5300)

    private fun amber() = Demo("amber", 6_800, { t -> Tele(13.2 + wob(t, 0.2, 700.0), 560 + wob(t, 20.0, 500.0), 41.2, 2.4, 35.6, 49.0) },
        listOf(
            300L to lamp(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_WARM, 47.0),
            1500L to lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 48.0),
            2900L to lamp(TellTales.TRACTION, LampLevel.AMBER, TellTales.T_LIMIT, 15.8),
            4300L to lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 49.0),
        ), offMs = 5900, buzzes = listOf(1500 + land to LampLevel.AMBER))

    private fun critical(units: String = TellTales.MPH) = Demo("critical", 15_200,
        { t -> Tele(11.0 + wob(t, 0.2, 700.0), 380 + wob(t, 25.0, 500.0), 4.1, 0.2, if (t < 6000) 31.5 else 31.4, 50.0) },
        listOf(
            300L to lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 50.0, units = units),
            1500L to lamp(TellTales.BATTERY, LampLevel.AMBER, TellTales.T_LOW, 32.4, units = units),
            2700L to lamp(TellTales.BATTERY, LampLevel.RED, TellTales.T_CUTTING, 31.5, units = units),
            6000L to lamp(TellTales.BATTERY, LampLevel.RED, TellTales.T_CUTTING, 31.4, units = units),
        ), offMs = 14_000, buzzes = listOf(300 + land to LampLevel.AMBER, 2700 + land to LampLevel.RED, 12_500L to LampLevel.RED), units = units,
        steady = listOf(lamp(TellTales.SERVICE, LampLevel.AMBER, "Ёмкость 75 % от паспорта", 75.0, units = units)))

    private fun bms() = Demo("bms", 6_200, { t -> Tele(9.8 + wob(t, 0.2, 700.0), 260 + wob(t, 20.0, 500.0), 18.3, 0.9, 32.6, 38.0) },
        listOf(300L to lamp(TellTales.BATTERY, LampLevel.RED, TellTales.T_BMS_CUT, 32.6)), offMs = 4800, buzzes = listOf(300 + land to LampLevel.RED))

    private fun many() = Demo("many", 9_200, { t -> Tele(13.5 + wob(t, 0.2, 700.0), -180 + wob(t, 20.0, 500.0), 97.8, 8.6, 42.4, 41.0) },
        listOf(
            300L to lamp(TellTales.LINK, LampLevel.AMBER, TellTales.T_LINK_LOST),
            1300L to lamp(TellTales.CONSUMPTION, LampLevel.AMBER, "Расход 24 Вт·ч/км", 24.0),
            2300L to lamp(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_WARM, 41.0),
            3300L to lamp(TellTales.WEAK, LampLevel.YELLOW, "Батарея устала: 0,38 Ом", 0.38),
            4300L to lamp(TellTales.OVERVOLT, LampLevel.AMBER, TellTales.T_FULL, 42.4),
        ), offMs = 8300, buzzes = listOf(1300 + land to LampLevel.AMBER))

    private fun limiterSpeed(t: Long): Double = when {
        t < 300 -> 14.6
        t < 1500 -> 14.6 + ease((t - 300) / 1200.0)
        t < 3200 -> 15.6 + 0.04 * sin((t - 1500) / 270.6)
        t < 4000 -> 15.6 - ease((t - 3200) / 800.0)
        else -> 14.6
    }

    private fun ease(x: Double): Double = x.coerceIn(0.0, 1.0).let { if (it < 0.5) 4 * it * it * it else 1 - Math.pow(-2 * it + 2, 3.0) / 2 }

    private fun crossing(f: (Long) -> Double, v: Double): Long = (10L..6000L step 10).first { f(it - 10) < v && f(it) >= v }

    private fun limiter(): Demo {
        val tap = crossing(::limiterSpeed, CuePoints.TAP_MPH) + 300
        return Demo("limiter", 4_500, { t -> Tele(limiterSpeed(t), if (t < 1500) 430.0 else if (t < 3200) 300.0 else 260.0, 61.8, 4.1, 38.9, 33.0) }, emptyList(), tapAt = tap)
    }

    private fun downhill(t: Long): Double = when {
        t < 1400 -> 16.9 + 1.3 * ease(t / 1400.0)
        t < 5000 -> 18.2 + 0.15 * sin((t - 1400) / 382.0)
        t < 5800 -> 18.2 - 1.3 * ease((t - 5000) / 800.0)
        else -> 16.9
    }

    private fun overspeed(units: String = TellTales.MPH): Demo {
        val on = crossing(::downhill, CuePoints.RUMBLE_MPH) + 300
        val off = (on..6000L step 10).first { downhill(it) < CuePoints.RUMBLE_MPH - CuePoints.RUMBLE_HYST_MPH }
        return Demo("overspeed", 6_000, { t -> Tele(downhill(t), -200.0, 72.5, 5.0, 39.6, 35.0) }, emptyList(), rumble = on until off, units = units,
            dynamic = { _, x -> if (x.mph >= 17.6) listOf(lamp(TellTales.OVERSPEED, LampLevel.BLUE, TellTales.T_OVERSPEED, x.mph * Fmt.MILE, units = units)) else emptyList() })
    }

    private fun startup() = Demo("bulb", 4_000, { Tele(0.0, 0.0, 64.0, 4.3, 39.2, 24.0) }, listOf(2000L to SERVICE), steady = emptyList(), bulbAt = 0L)

    private fun norm() = Demo("norm", 6_000, { t -> Tele(14.8 + wob(t, 0.25, 900.0), 180 + wob(t, 22.0, 700.0), if (t < 3200) 56.3 else 56.2, 3.5, 38.1, 31.0) },
        listOf(500L to lamp(TellTales.ECO, LampLevel.GREEN, "ECO", 160.0)), offMs = 5300)

    private fun charging() = Demo("charging", 6_000, { t -> Tele(0.0, -70.0, if (t < 3000) 61.4 else 61.5, 3.9, 40.6, 33.0) },
        listOf(300L to lamp(TellTales.CHARGING, LampLevel.GREEN, "Зарядка 1,7 А", 1.7)), offMs = 5300)

    @Test fun stepUpSwapRollRepeat() {
        // each list holds the mock's own shot time too (stack2: shots[id].main), for the side-by-side sheet
        play(warm(), listOf(300, 400, 440, 520, 580, 680, 760, 900, 1_200, 1_700, 3_300, 3_400, 3_500, 3_650, 4_000, 5_300, 5_450, 5_650, 5_850, 6_100))
        play(amber(), listOf(1_500, 1_650, 1_760, 1_850, 1_970, 2_050, 2_200, 2_500, 2_900, 3_100, 3_200, 3_368, 3_600, 4_300, 4_400, 4_500, 5_000, 5_900, 6_100, 6_300, 6_600))
        play(critical(), listOf(2_700, 2_850, 2_950, 3_080, 3_170, 3_300, 3_510, 3_850, 4_170, 4_670, 5_170, 6_000, 6_100, 6_200, 11_670, 12_500, 12_560, 12_620, 13_000, 14_000, 14_150, 14_300, 14_450, 14_700, 14_970))
        play(bms(), listOf(300, 440, 580, 768, 900, 1_110, 1_450, 2_270, 3_770, 4_800, 4_950, 5_100, 5_300, 5_500, 5_770))
        play(many(), listOf(1_300, 1_450, 1_600, 1_770, 1_900, 2_300, 2_500, 3_300, 4_300, 4_450, 4_600, 4_720, 5_000, 7_200, 8_300, 8_500, 8_800))
    }

    @Test fun cuesStartupAndBattery() {
        val l = limiter()
        val tap = l.tapAt!!
        play(l, listOf(tap - 50, tap, tap + 75, tap + 150, tap + 300, tap + 600, tap + 2_000, tap + 2_200, 2_100))
        val o = overspeed()
        val on = o.rumble!!.first
        play(o, listOf(on - 100, on, on + 100, on + 250, on + 500, on + 560, on + 2_000, on + 2_100, 3_000, o.rumble.last + 20, o.rumble.last + 500, o.rumble.last + 1_000))
        play(startup(), listOf(0, 100, 200, 350, 450, 600, 800, 1_000, 1_300, 1_450, 1_550, 1_650, 1_800, 1_950, 2_300, 2_600))
        play(norm(), listOf(500, 600, 700, 3_000, 3_200, 3_300, 3_400, 3_550, 5_300, 5_400, 5_600))
        play(charging(), listOf(300, 600, 1_000, 1_300, 1_700, 2_100, 2_400, 3_000, 3_100, 3_200, 3_400, 4_600, 5_300))
    }

    @Test fun variants() {
        // calmer (the system's «Remove animations»): no flight, the buzz count as still rings, red steady
        play(critical(), listOf(2_700, 2_800, 2_900, 3_100, 3_300, 3_800, 4_200, 14_000, 14_200, 14_600), "critical-calm", reduced = true)
        play(bms(), listOf(300, 450, 650, 900, 1_400, 4_800, 5_000, 5_300), "bms-calm", reduced = true)
        play(startup(), listOf(100, 300, 800, 1_500, 1_650, 2_000), "bulb-calm", reduced = true)
        // the data's health: reconnecting, then the phone gone
        val c = critical()
        play(c, listOf(5_000), "critical-reconnecting", states(c, { Health.RECONNECTING }))
        play(c, listOf(5_000), "critical-nophone", states(c, { Health.NO_PHONE }))
        play(norm(), listOf(4_000), "norm-silent", states(norm(), { Health.SILENT }))
        // km/h
        val k = critical(TellTales.KMH)
        play(k, listOf(5_000), "critical-kmh")
        val ok = overspeed(TellTales.KMH)
        play(ok, listOf(ok.rumble!!.first + 1_000), "overspeed-kmh")
        // ambient, low-bit
        play(amber(), listOf(5_000), "amber-ambient", states(amber(), ambient = true))
        play(amber(), listOf(5_000), "amber-lowbit", states(amber(), ambient = true, lowBit = true))
        play(critical(), listOf(5_000), "critical-ambient", states(critical(), ambient = true))
        // everything at once, the longest texts
        val all = Demo("all", 3_000, { Tele(12.0, 420.0, 9.7, 0.4, 31.9, 57.0) }, listOf(
            300L to lamp(TellTales.OVERVOLT, LampLevel.RED, TellTales.T_OV_CUT, 43.5),
            300L to lamp(TellTales.TEMP, LampLevel.RED, TellTales.T_OVERHEAT, 57.0),
            300L to lamp(TellTales.LINK, LampLevel.AMBER, TellTales.T_DASH_NO_BMS),
            300L to lamp(TellTales.CHARGING, LampLevel.AMBER, TellTales.T_CHARGE_WAIT, 46.0),
            300L to lamp(TellTales.COLD, LampLevel.BLUE, TellTales.T_COLD, -3.0),
            300L to lamp(TellTales.ECO, LampLevel.GREEN, "ECO", 120.0),
            300L to lamp(TellTales.WEAK, LampLevel.YELLOW, "Батарея устала: 0,38 Ом", 0.38),
        ))
        play(all, listOf(2_500), "all")
        val one = Demo("long", 3_000, { Tele(12.0, 420.0, 33.0, 2.0, 36.0, 40.0) }, listOf(300L to lamp(TellTales.OVERVOLT, LampLevel.RED, TellTales.T_OV_CUT, 43.5)))
        play(one, listOf(2_500), "long")
        val code = Demo("code", 3_000, { Tele(0.0, 0.0, 77.0, 5.0, 40.0, 30.0) }, listOf(300L to lamp(TellTales.LINK, LampLevel.AMBER, "Код контроллера 0x21")))
        play(code, listOf(2_500), "code")
    }

    /** The three test rides, every second of the log drawn (nothing may throw); a few saved. */
    @Test fun realRides() {
        for ((name, path) in listOf("zero" to RideReplay.TO_ZERO, "hot" to StackReplayTest.CUTOFF_HOT, "twoup" to StackReplayTest.TWO_UP_HALF)) {
            if (!File(RideReplay.root, path).isFile) continue
            calm.value = false
            start()
            var next = Long.MIN_VALUE
            var saveAt = Long.MIN_VALUE
            var savedAt = Long.MIN_VALUE / 2
            var lastTop: String? = null
            var drawn = 0
            DialReplay.replay(path) { s ->
                if (s.nowMs < next) return@replay
                next = s.nowMs + 1_000
                val top = s.lamps.top?.let { it.id + ":" + it.level }
                val save = (top != lastTop && top != null && s.nowMs - savedAt > 60_000) || s.nowMs >= saveAt
                if (save) { saveAt = s.nowMs + 300_000; savedAt = s.nowMs }
                frame(s, s.nowMs + if (top != lastTop) 1_200 else 0, if (save) "ride-$name-${s.nowMs / 1000}" else null)
                lastTop = top
                drawn++
            }
            assertTrue("$name: $drawn", drawn > 500)
        }
    }

    companion object {
        private val SERVICE = TellTales.lamp(TellTales.SERVICE, LampLevel.AMBER, "Ёмкость 75 % от паспорта", 75.0, false, TellTales.MPH)
    }
}
