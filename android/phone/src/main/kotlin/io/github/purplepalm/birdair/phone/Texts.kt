package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.Telemetry
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** Numbers the Russian way, like the page's nf(): decimal comma, no-break space between thousands from 10 000. */
object Fmt {
    const val NBSP = ' '
    const val MINUS = '−'

    fun n(v: Double?, digits: Int = 0): String {
        if (v == null || !v.isFinite()) return "—"
        val bd = BigDecimal(v).setScale(digits, RoundingMode.HALF_UP)
        val neg = bd.signum() < 0
        val plain = bd.abs().toPlainString()
        val dot = plain.indexOf('.')
        val int = if (dot < 0) plain else plain.substring(0, dot)
        val frac = if (dot < 0) "" else plain.substring(dot + 1)
        val sb = StringBuilder()
        if (neg) sb.append(MINUS)
        if (int.length >= 5) {
            val head = int.length % 3
            for ((i, c) in int.withIndex()) {
                if (i > 0 && (i - head) % 3 == 0) sb.append(NBSP)
                sb.append(c)
            }
        } else sb.append(int)
        if (frac.isNotEmpty()) sb.append(',').append(frac)
        return sb.toString()
    }

    /** "320 Вт", "1,25 кВт" from 10 kW on. */
    fun watts(w: Double?): String = when {
        w == null || !w.isFinite() -> "—"
        abs(w) >= 10_000 -> n(w / 1000, 1) + " кВт"
        else -> n(w) + " Вт"
    }

    /** The power bar's scale: "±300 Вт", "±1,2 кВт". */
    fun scale(peakW: Double): String = "±" + if (peakW >= 1000) n(peakW / 1000, 1) + " кВт" else n(peakW) + " Вт"
}

/** Display units; the tracker always keeps km/h and km. */
object Units {
    const val KMH = "kmh"
    const val MPH = "mph"
    const val MILE = 1.609344

    fun norm(u: String?): String = if (u == MPH) MPH else KMH
    fun speed(kmh: Double?, units: String): Double? = kmh?.let { if (units == MPH) it / MILE else it }
    fun dist(km: Double?, units: String): Double? = km?.let { if (units == MPH) it / MILE else it }
    fun speedLabel(units: String) = if (units == MPH) "mph" else "км/ч"
    fun distLabel(units: String) = if (units == MPH) "mi" else "км"
}

/** Where the energy goes now: the page's flowState(). Current/power: + from the battery, − into it. */
enum class Flow(val label: String) {
    NONE("Нет данных"), IDLE("Стоит"), DRIVE("Тяга"), REGEN("Рекуперация"), CHARGE("Зарядка"), INTO("В батарею");

    val intoBattery: Boolean get() = this == REGEN || this == CHARGE || this == INTO

    companion object {
        fun of(currentA: Double?, powerW: Double?, speedKmh: Double?): Flow {
            if (currentA == null && powerW == null && speedKmh == null) return NONE
            val into = if (currentA != null) currentA < -0.15 else (powerW != null && powerW < -5)
            if (into) return when {
                speedKmh != null && speedKmh > 1 -> REGEN
                speedKmh != null -> CHARGE
                else -> INTO
            }
            val moving = (speedKmh != null && speedKmh > 1) || (currentA != null && currentA > 0.6) || (powerW != null && powerW > 20)
            return if (moving) DRIVE else IDLE
        }

        fun of(t: Telemetry): Flow = of(t.currentA, t.powerW, t.speedKmh)
    }
}

/** The dash's three modes (frame 3A 17, byte 9). */
enum class Mode(val n: Int, val title: String, val limitMph: Double) {
    TAKEOFF(1, "TAKEOFF", 9.0), GLIDE(2, "GLIDE", 12.5), FLY(3, "FLY", 16.0);

    fun limit(units: String): String =
        if (units == Units.MPH) Fmt.n(limitMph, if (limitMph % 1.0 == 0.0) 0 else 1) + " mph" else Fmt.n(limitMph * Units.MILE, 1) + " км/ч"

    companion object {
        fun of(n: Int?): Mode? = entries.firstOrNull { it.n == n }
    }
}

/** The ongoing notification and the connection pill. */
object StatusText {
    /** «25,6 км/ч · 75 % · тяга 320 Вт», «нет связи — переподключаюсь»… */
    fun notification(t: Telemetry): String = when (t.link) {
        Link.IDLE -> "не подключено"
        Link.SCANNING -> "ищу самокат…"
        Link.CONNECTING -> "подключаюсь…"
        Link.RECONNECTING -> "нет связи — переподключаюсь"
        Link.CONNECTED -> if (!t.streaming) "подключено — жду данные от руля" else {
            val parts = ArrayList<String>()
            Units.speed(t.speedKmh, t.units)?.let { parts.add(Fmt.n(it, 1) + " " + Units.speedLabel(t.units)) }
            t.batteryPct?.let { parts.add(Fmt.n(it) + " %") }
            val p = t.powerW
            when (Flow.of(t)) {
                Flow.DRIVE -> parts.add("тяга " + Fmt.watts(p?.let { abs(it) } ?: 0.0))
                Flow.REGEN -> parts.add("рекуперация " + Fmt.watts(p?.let { abs(it) } ?: 0.0))
                Flow.CHARGE, Flow.INTO -> parts.add("в батарею " + Fmt.watts(p?.let { abs(it) } ?: 0.0))
                Flow.IDLE -> parts.add("стоит")
                Flow.NONE -> {}
            }
            if (parts.isEmpty()) "подключено — данные идут" else parts.joinToString(" · ")
        }
    }

    fun title(t: Telemetry): String = Mode.of(t.mode)?.let { "Bird Air · " + it.title } ?: "Bird Air"

    /** The connection pill on the main screen. */
    fun pill(link: Link, streaming: Boolean, scanning: Boolean): String = when {
        scanning && link == Link.IDLE -> "Ищу самокат…"
        link == Link.IDLE -> "Не подключено"
        link == Link.SCANNING -> "Ищу самокат…"
        link == Link.CONNECTING -> "Подключаюсь…"
        link == Link.RECONNECTING -> "Переподключаюсь…"
        streaming -> "На связи"
        else -> "Подключено — жду данные"
    }
}

/** Session energy per distance: net Wh (out − regen) over the odometer since the session started. */
object Trip {
    fun whPerKm(whOut: Double, whIn: Double, odoStartKm: Double?, odoNowKm: Double?): Double? {
        if (odoStartKm == null || odoNowKm == null) return null
        val d = odoNowKm - odoStartKm
        return if (d >= 0.05) (whOut - whIn) / d else null
    }
}
