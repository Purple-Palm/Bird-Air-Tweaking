package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales

/** The words of the «Приборка» page: the dial's line, the battery card, the journal's header. */
object LampsText {
    /** The dial's line and the lamp level it is coloured by (null: muted). */
    data class Dial(val text: String, val level: LampLevel?)

    /**
     * «Проверка ламп…» while every lamp is lit; without a recording ([live] false) «Нет живых данных — подключись к
     * самокату»; else the top lamp as the watch dial has it, «⛔ Сейчас отключится · 31,5 В под нагрузкой», or
     * «Предупреждений нет».
     */
    fun dial(lamps: List<Lamp>, units: String, bulb: Boolean, live: Boolean): Dial = when {
        bulb -> Dial("Проверка ламп…", null)
        !live -> Dial("Нет живых данных — подключись к самокату", null)
        else -> TellTales.top(lamps)?.let { Dial(LampText.symbol(it.level) + " " + LampText.full(it, units), it.level) } ?: Dial("Предупреждений нет", null)
    }

    /** What the grid lights: everything in the bulb check, nothing without a recording (the last lamps are not live). */
    fun shown(lamps: List<Lamp>, bulb: Boolean, live: Boolean): List<Lamp> = when {
        bulb -> TellTales.bulbCheck()
        live -> lamps
        else -> emptyList()
    }

    /** «Ток 7,8 А (BMS 7,6 А) · мотор тянет» (the BMS's own current beside the fast one); null: no current yet. */
    fun current(t: Telemetry, fast: Boolean): String? {
        val a = Power.amps(t, fast) ?: return null
        val bms = if (fast && t.currentFastA != null && t.currentA != null) " (BMS " + Fmt.n(t.currentA, 1) + " А)" else ""
        return "Ток " + Fmt.n(a, 1) + " А" + bms + if (t.motorOn == true) " · мотор тянет" else ""
    }

    /** «906 / 3936 мА·ч»; null without the BMS's numbers. */
    fun mah(mah: Double?, full: Double?): String? = if (mah == null) null else Fmt.n(mah) + " / " + Fmt.n(full) + " мА·ч"

    /** Under the range: what it is based on and the two temperatures, «По расходу за последние 2 км: … · температуры 48 / 50 °C». */
    fun basis(r: RangeEstimate?, temp1C: Double?, temp2C: Double?): String {
        val temps = if (temp1C != null || temp2C != null) "температуры " + Fmt.n(temp1C) + " / " + Fmt.n(temp2C) + " °C" else null
        val b = r?.let { WarnText.basis(it) }
        return listOfNotNull(b, temps).joinToString(" · ").replaceFirstChar { it.uppercaseChar() }
    }

    /** «Батарея (последнее)»: «54 / 3936 мА·ч · после отключения BMS; ёмкость 76 % паспорта». */
    fun last(r: LastRide, ratedMah: Double = TellTales.Params().ratedMah): String {
        val parts = ArrayList<String>()
        mah(r.mah, r.fullMah)?.let { parts.add(it) }
        val tail = listOfNotNull(
            if (r.end?.title == RideEnds.BMS_OFF) "после отключения BMS" else null,
            r.fullMah?.takeIf { it > 0 && ratedMah > 0 }?.let { "ёмкость " + Fmt.n(100 * it / ratedMah) + " % паспорта" },
        ).joinToString("; ")
        if (tail.isNotEmpty()) parts.add(tail)
        return parts.joinToString(" · ")
    }

    /** «ЖУРНАЛ ЛАМП (12)», with the day of the last recording when none runs: «ЖУРНАЛ ЛАМП (12) · 20.10». */
    fun journalTitle(n: Int, dayMs: Long?): String =
        "ЖУРНАЛ ЛАМП ($n)" + (dayMs?.let { " · " + Fmt.dayTime(it).substringBefore(' ') } ?: "")

    /** The journal shows this many rows until «Весь журнал · N». */
    const val JOURNAL_ROWS = 6
}
