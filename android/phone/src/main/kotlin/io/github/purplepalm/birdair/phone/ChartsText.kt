package io.github.purplepalm.birdair.phone

import kotlin.math.abs
import kotlin.math.roundToLong

/** The words of the «Графики» page: the readout under the finger, the window's energy, the trip. */
object ChartsText {
    /** The readout row: «22:12:06 · 254 Вт тяга» (time and watts bold), under it «8,1 А · 31,41 В · 22,5 км/ч». */
    data class Readout(val time: String, val watts: String, val word: String, val into: Boolean, val line2: String)

    const val HINT = "Коснись графика и веди пальцем — покажу значения в тот момент."
    const val GESTURES = "Веди пальцем по графику вбок — значения; вверх-вниз — прокрутка."

    /**
     * Sample [i] of [x]: wall time from [wallStartMs] (the session's start), the power with «тяга» / «в батарею», the
     * current (P / U when the sample has none), the voltage, the speed in [units]; «—» for what is not known.
     */
    fun readout(x: Samples, i: Int, wallStartMs: Long, fast: Boolean, units: String): Readout {
        val p = x.p(fast)[i].toDouble().takeIf { !it.isNaN() }
        val v = x.v[i].toDouble().takeIf { !it.isNaN() }
        val a = x.i(fast)[i].toDouble().takeIf { !it.isNaN() } ?: if (p != null && v != null && v > 1) p / v else null
        val s = x.s[i].toDouble().takeIf { !it.isNaN() }
        val word = when { p == null -> ""; p > 0.5 -> "тяга"; p < -0.5 -> "в батарею"; else -> "" }
        return Readout(
            Fmt.clock(wallStartMs + x.t[i]),
            (if (p == null) "—" else Fmt.n(abs(p))) + " Вт", word, p != null && p < -0.5,
            Fmt.n(a, 1) + " А · " + Fmt.n(v, 2) + " В · " + Fmt.n(Units.speed(s, units), 1) + " " + Units.speedLabel(units),
        )
    }

    /** «ЭНЕРГИЯ ЗА ОКНО · 5 МИН», «ЭНЕРГИЯ ЗА ОКНО · ВСЯ ПОЕЗДКА». */
    fun energyTitle(window: String): String =
        "ЭНЕРГИЯ ЗА ОКНО · " + (ChartWindow.OPTIONS.firstOrNull { it.first == window }?.second ?: "5 мин").uppercase()

    /** The prototype's durS: «5 мин 0 с», «1 ч 12 мин». */
    fun duration(seconds: Double): String {
        val s = seconds.coerceAtLeast(0.0).roundToLong()
        val h = s / 3600
        val m = s / 60 % 60
        return if (h > 0) "$h ч $m мин" else "$m мин ${s % 60} с"
    }

    /** Under the energy: «Данные за 5 мин 0 с · 1,64 км по одометру приложения.» and how to read the charts. */
    fun energyNote(e: EnergySummary, units: String): String {
        if (e.seconds <= 0) return GESTURES
        val km = e.km?.let { " · " + Fmt.n(Units.dist(it, units), 2) + " " + Units.distLabel(units) + " по одометру приложения" } ?: ""
        return "Данные за " + duration(e.seconds) + km + ". " + GESTURES
    }

    /** Wh/km in the chosen unit (Wh/mi for mph). */
    fun perDist(whPerKm: Double?, units: String): Double? = whPerKm?.let { if (units == Units.MPH) it * Units.MILE else it }

    /** The trip's energy line: «Из батареи 104,7 Вт·ч, рекуперация 6,4 Вт·ч (6,1 %). Одометр руля теряет пробег …». */
    fun tripNote(whOut: Double, whIn: Double): String =
        "Из батареи " + Fmt.n(whOut, 1) + " Вт·ч, рекуперация " + Fmt.n(whIn, 1) + " Вт·ч" +
            (if (whOut > 0.05) " (" + Fmt.n(100 * whIn / whOut, 1) + " %)" else "") +
            ". Одометр руля теряет пробег при выключении — приложение считает своё (исправить — в «Настройках»)."
}
