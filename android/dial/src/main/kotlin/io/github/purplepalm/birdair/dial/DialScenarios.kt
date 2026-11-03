package io.github.purplepalm.birdair.dial

import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.PowerState
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import kotlin.math.floor
import kotlin.math.sin

/**
 * One demo timeline (the galleries' scenarios): [states] at their [DialState.nowMs], every [DialScenarios.STEP_MS] from 0
 * to [lengthMs] and at each event; [changeMs] the change the shots look at ([DialScenarios.FRAMES_MS] after it).
 */
class DialScenario(val key: String, val title: String, val lengthMs: Long, val changeMs: Long, val states: List<DialState>) {
    /** The state on screen at [ms]: the last one at or before it (the first before the start). */
    fun at(ms: Long): DialState {
        var lo = 0
        var hi = states.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (states[mid].nowMs <= ms) lo = mid else hi = mid - 1
        }
        return states[lo]
    }
}

/**
 * The 11 demo scenarios of the galleries, as [DialState] timelines in mph (FLY), the lamps made by [TellTales.lamp] from
 * its own texts, the health live, the cues as the watch would play them (a tap at 15,5 mph, the rumble past 17,5 mph, a
 * buzz when a lamp rises to amber or red, 5 s apart). The phone's previews and the watch's shots draw them.
 */
object DialScenarios {
    val KEYS = listOf("norm", "warm", "amber", "critical", "bms", "many", "limiter", "overspeed", "bulb", "ambient", "charging")
    /** What the phone's face previews play, one after the other. */
    val PREVIEW = listOf("norm", "amber", "critical", "overspeed")
    /** The shots' frames, ms after [DialScenario.changeMs]. */
    val FRAMES_MS = listOf(0L, 150L, 350L, 600L, 1200L)
    const val STEP_MS = 100L

    val ALL: List<DialScenario> by lazy { KEYS.map { build(it) } }

    operator fun get(key: String): DialScenario = ALL.first { it.key == key }

    private const val U = TellTales.MPH
    private fun lamp(id: String, level: LampLevel, text: String, value: Double? = null, rising: Boolean = false) =
        TellTales.lamp(id, level, text, value, rising, U)

    private val SERVICE = lamp(TellTales.SERVICE, LampLevel.AMBER, "Ёмкость 75 % от паспорта", 75.0)

    /** What the scooter does at a moment: mph, W (fast), %, the range in mi, V, the hotter sensor's °C. */
    private class Tele(val mph: Double, val watts: Double, val pct: Double, val rangeMi: Double, val volts: Double, val tempC: Double)

    private fun wob(t: Long, a: Double, per: Double) = a * sin(t / per)
    private fun ease(x: Double): Double = x.coerceIn(0.0, 1.0).let { if (it < 0.5) 4 * it * it * it else 1 - Math.pow(-2 * it + 2, 3.0) / 2 }

    private class Script(
        val key: String,
        val title: String,
        val lengthMs: Long,
        val changeMs: Long,
        val clock: String,
        val tele: (Long) -> Tele,
        /** (ms, lamp): lit or changed at ms; a lamp with level OFF goes out. */
        val events: List<Pair<Long, Lamp>> = emptyList(),
        val steady: List<Lamp> = listOf(SERVICE),
        /** All but [steady] go out here. */
        val offMs: Long? = null,
        /** Lamps that follow the telemetry (the overspeed lamp from 17,6 mph). */
        val dynamic: (Tele) -> List<Lamp> = { emptyList() },
        val bulb: LongRange? = null,
        val ambient: Boolean = false,
    )

    private fun scripts(): Map<String, Script> {
        val list = listOf(
            Script("norm", "Норма", 6000, 500, "21:10",
                { t -> Tele(14.8 + wob(t, 0.25, 900.0), 180 + wob(t, 22.0, 700.0), 56.3, 3.5, 38.1, 31.0) },
                events = listOf(500L to lamp(TellTales.ECO, LampLevel.GREEN, "ECO", 160.0)), offMs = 5300),
            Script("warm", "Тёплая ▲", 6000, 300, "21:24",
                { t -> Tele(14.1 + wob(t, 0.2, 800.0), 320 + wob(t, 30.0, 600.0), 52.7, 3.2, 37.4, 42.0) },
                events = listOf(300L to lamp(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_WARM_RISING, 42.0, rising = true)), offMs = 5300),
            Script("amber", "Две оранжевые", 6500, 1500, "21:41",
                { t -> Tele(13.2 + wob(t, 0.2, 700.0), 560 + wob(t, 20.0, 500.0), 41.2, 2.4, 35.6, 49.0) },
                events = listOf(
                    300L to lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 49.0),
                    1500L to lamp(TellTales.TRACTION, LampLevel.AMBER, TellTales.T_LIMIT, 15.8),
                ), offMs = 5700),
            Script("critical", "Критично", 7000, 1600, "21:52",
                { t -> Tele(11.0 + wob(t, 0.2, 700.0), 380 + wob(t, 25.0, 500.0), 4.1, 0.2, 31.5, 50.0) },
                events = listOf(
                    300L to lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 50.0),
                    1600L to lamp(TellTales.BATTERY, LampLevel.RED, TellTales.T_CUTTING, 31.5),
                ), offMs = 6300),
            Script("bms", "BMS", 6000, 300, "21:55",
                { t -> Tele(9.8 + wob(t, 0.2, 700.0), 260 + wob(t, 20.0, 500.0), 18.3, 0.9, 32.6, 38.0) },
                events = listOf(300L to lamp(TellTales.BATTERY, LampLevel.RED, TellTales.T_BMS_CUT, 32.6)), offMs = 5300),
            Script("many", "Много ламп", 9000, 4300, "22:03",
                { t -> Tele(13.5 + wob(t, 0.2, 700.0), -180 + wob(t, 20.0, 500.0), 97.8, 8.6, 42.4, 41.0) },
                events = listOf(
                    300L to lamp(TellTales.LINK, LampLevel.AMBER, TellTales.T_LINK_LOST),
                    1300L to lamp(TellTales.CONSUMPTION, LampLevel.AMBER, "Расход 24 Вт·ч/км", 24.0),
                    2300L to lamp(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_WARM, 41.0),
                    3300L to lamp(TellTales.WEAK, LampLevel.YELLOW, "Батарея устала: 0,38 Ом", 0.38),
                    4300L to lamp(TellTales.OVERVOLT, LampLevel.AMBER, TellTales.T_FULL, 42.4),
                ), offMs = 8300),
            Script("limiter", "Ограничитель", 4500, tapCrossing(), "21:12", ::limiter),
            Script("overspeed", "С горки", 6000, rumbleStart(), "21:18", ::downhill,
                dynamic = { x -> if (x.mph >= OVERSPEED_MPH) listOf(lamp(TellTales.OVERSPEED, LampLevel.BLUE, TellTales.T_OVERSPEED, x.mph * Fmt.MILE)) else emptyList() }),
            Script("bulb", "Проверка ламп", 3600, 200, "21:05",
                { Tele(0.0, 0.0, 64.0, 4.3, 39.2, 24.0) },
                events = listOf(1850L to SERVICE), steady = emptyList(), offMs = 3300, bulb = 200L until 200L + TellTales.BULB_CHECK_MS),
            Script("charging", "Зарядка", 6000, 300, "23:30",
                { Tele(0.0, -70.0, 61.4, 3.9, 40.6, 33.0) },
                events = listOf(300L to lamp(TellTales.CHARGING, LampLevel.GREEN, "Зарядка 1,7 А", 1.7)), offMs = 5300),
        )
        val by = list.associateBy { it.key }.toMutableMap()
        // always-on: the two ambers, static; the shots look at the second one
        val a = by.getValue("amber")
        by["ambient"] = Script("ambient", "AOD", a.lengthMs, a.changeMs, a.clock, a.tele, a.events, a.steady, a.offMs, ambient = true)
        return by
    }

    private const val TAP_MPH = CuePoints.TAP_MPH
    private const val RUMBLE_MPH = CuePoints.RUMBLE_MPH
    /** TellTales lights «быстрее ограничителя» at 1,1 × the mode's limit: FLY 17,6 mph. */
    private const val OVERSPEED_MPH = 17.6

    private fun limiter(t: Long): Tele {
        val spd = when {
            t < 300 -> 14.6
            t < 1500 -> 14.6 + ease((t - 300) / 1200.0)
            t < 3200 -> 15.6 + 0.04 * sin((t - 1500) / 270.6)
            t < 4000 -> 15.6 - ease((t - 3200) / 800.0)
            else -> 14.6
        }
        return Tele(spd, if (t < 1500) 430.0 else if (t < 3200) 300.0 else 260.0, 61.8, 4.1, 38.9, 33.0)
    }

    private fun downhill(t: Long): Tele {
        val spd = when {
            t < 1400 -> 16.9 + 1.3 * ease(t / 1400.0)
            t < 5000 -> 18.2 + 0.15 * sin((t - 1400) / 382.0)
            t < 5800 -> 18.2 - 1.3 * ease((t - 5000) / 800.0)
            else -> 16.9
        }
        val k = when { t < 1400 -> ease(t / 1400.0); t < 5000 -> 1.0; t < 5800 -> 1 - ease((t - 5000) / 800.0); else -> 0.0 }
        return Tele(spd, -150 - 100 * k, 72.5, 5.0, 39.6, 35.0)
    }

    /** The first 10-ms step at or past [mph] going up. */
    private fun crossing(tele: (Long) -> Tele, mph: Double, until: Long): Long {
        var prev = tele(0).mph
        var t = 10L
        while (t <= until) {
            val s = tele(t).mph
            if (prev < mph && s >= mph) return t
            prev = s
            t += 10
        }
        return 0
    }

    private fun tapCrossing() = crossing(::limiter, TAP_MPH, 4500)
    private fun rumbleStart() = crossing(::downhill, RUMBLE_MPH, 6000)

    private fun build(key: String): DialScenario {
        val s = scripts().getValue(key)
        val times = sortedSetOf<Long>()
        var t = 0L
        while (t <= s.lengthMs) { times += t; t += STEP_MS }
        times += s.events.map { it.first }
        s.offMs?.let { times += it }
        times += s.changeMs
        s.bulb?.let { times += it.first; times += it.last + 1 }
        val tap = if (key == "limiter") s.changeMs else null
        val b = DialBuilder()
        var seq = 0L
        var buzz: LampBuzz? = null
        var levels = emptyMap<String, LampLevel>()
        var rumbleSince: Long? = null
        val states = times.filter { it <= s.lengthMs }.map { ms ->
            val x = s.tele(ms)
            val lit = LinkedHashMap<String, Lamp>()
            for (l in s.steady) lit[l.id] = l
            for ((at, l) in s.events) if (at <= ms) { if (l.level == LampLevel.OFF) lit.remove(l.id) else lit[l.id] = l }
            if (s.offMs != null && ms >= s.offMs) lit.keys.retainAll(s.steady.map { it.id }.toSet())
            for (l in s.dynamic(x)) lit[l.id] = l
            val lamps = TellTales.IDS.mapNotNull { lit[it] }
            // a buzz when a lamp rises to amber or red (not the quiet ones or the link), 5 s apart — red at once after an amber
            val rose = lamps.filter { it.id !in TellTales.QUIET && it.id != TellTales.LINK && (it.level == LampLevel.AMBER || it.level == LampLevel.RED) &&
                (levels[it.id] ?: LampLevel.OFF) < it.level }
            val top = rose.maxByOrNull { it.level.ordinal }
            if (top != null) {
                val last = buzz
                if (last == null || ms - last.atMs >= 5_000 || (top.level == LampLevel.RED && last.level != LampLevel.RED)) buzz = LampBuzz(ms, top.level)
            }
            levels = lamps.associate { it.id to it.level }
            val rumble = x.mph > RUMBLE_MPH
            rumbleSince = if (rumble) rumbleSince ?: ms else null
            val bulbOn = s.bulb?.contains(ms) == true
            b.build(
                telemetry(ms, ++seq, x, lamps), Health.LIVE, ms,
                cues = DialCues(tapAtMs = tap?.takeIf { it <= ms }, rumble = rumble, rumbleSinceMs = rumbleSince, buzz = buzz),
                bulbAtMs = s.bulb?.first?.takeIf { it <= ms }, bulbOn = bulbOn,
                ambient = s.ambient, burnInDp = if (s.ambient) BurnIn.offset(2) else 0f to 0f, clock = s.clock,
            )
        }
        return DialScenario(s.key, s.title, s.lengthMs, s.changeMs, states)
    }

    /** The phone's snapshot for [x]: FLY, live, the fast power, a 3936 mAh pack. */
    private fun telemetry(ms: Long, seq: Long, x: Tele, lamps: List<Lamp>): Telemetry {
        val amps = x.watts / x.volts
        val km = x.rangeMi * Fmt.MILE
        return Telemetry(
            t = ms, link = Link.CONNECTED, streaming = true, units = U,
            speedKmh = x.mph * Fmt.MILE, mode = 3, batteryPct = floor(x.pct), voltage = x.volts,
            currentA = amps, powerW = x.watts, currentFastA = amps, powerFastW = x.watts, peakW = 700.0,
            odometerKm = 1460.2, tripKm = 6.3, remainingMah = 39.36 * x.pct, fullMah = 3936.0,
            temp1C = x.tempC - 2, temp2C = x.tempC, motorOn = x.watts > 20, seq = seq,
            range = RangeEstimate(km, x.rangeMi, km * 12.0, 12.0, "recent"),
            lamps = lamps, batteryPctFine = x.pct,
            powerState = when { x.mph < 1 -> null; x.watts > 20 -> PowerState.TRACTION; x.watts < -20 -> PowerState.REGEN; else -> PowerState.COAST },
        )
    }
}
