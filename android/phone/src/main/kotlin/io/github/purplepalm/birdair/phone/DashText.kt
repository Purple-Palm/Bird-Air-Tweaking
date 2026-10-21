package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales

/** The dash's warning band: [id] the lamp a tap opens (null: none), [level] its colour, two lines; [crit] red. */
data class Band(val id: String?, val level: LampLevel?, val title: String, val hint: String?, val crit: Boolean)

/** The dash's words. Pure: the screen passes the state in. */
object DashText {
    /**
     * The fixed-height band at the top of the dash: the bulb check; the link («Переподключаюсь…», «Руль молчит»); else the
     * top lamp ([TellTales.top], the quiet ones never) as «Сейчас отключится · 31,5 В» with a short hint («сбавь газ, ищи
     * где встать»); nothing lit: «Предупреждений нет».
     */
    fun band(lamps: List<Lamp>, units: String, bulb: Boolean, running: Boolean, link: Link, streaming: Boolean): Band {
        if (bulb) return Band(null, null, "Проверка ламп…", null, false)
        if (running && link == Link.RECONNECTING) return Band(TellTales.LINK, LampLevel.AMBER, "Переподключаюсь…", "связь с самокатом прервалась — пробую снова", false)
        if (running && link == Link.CONNECTED && !streaming) return Band(TellTales.LINK, LampLevel.AMBER, TellTales.T_SILENT, SILENT_HINT, false)
        val top = TellTales.top(lamps) ?: return Band(null, null, "Предупреждений нет", null, false)
        val title = top.text.ifEmpty { LampText.short(top) } + (LampText.value(top, units)?.let { " · $it" } ?: "")
        return Band(top.id, top.level, title, hint(top), top.level == LampLevel.RED)
    }

    const val SILENT_HINT = "жду кадры после запроса +SVGT=?…"

    private val HINTS = mapOf(
        "${TellTales.BATTERY}:${LampLevel.RED}" to "сбавь газ, ищи где встать",
        "${TellTales.TEMP}:${LampLevel.AMBER}" to "дойдёт до 55 °C — дай остыть",
        "${TellTales.LINK}:${LampLevel.AMBER}" to SILENT_HINT,
        "${TellTales.BATTERY}:${LampLevel.AMBER}" to "заряд на исходе",
        "${TellTales.TRACTION}:${LampLevel.AMBER}" to "контроллер урезает ток",
    )

    /** The band's second line: what the BMS said, else the hint of the lamp and colour, else its state («горит оранжевым»). */
    fun hint(l: Lamp): String = when {
        l.text.startsWith(LampText.BMS_CUT) -> "через секунды мотор пропадёт — остановись"
        l.text.startsWith(LampText.NO_BMS) -> "мотор не потянет, на руле — ошибка 12"
        l.text.startsWith(TellTales.T_DASH_NO_BMS) -> "кадров BMS нет дольше 5 с"
        else -> HINTS["${l.id}:${l.level}"] ?: LampText.state(l.level).lowercase()
    }

    /** «≈ 0,2 км до отключения», in the owner's unit only; null without an estimate. */
    fun range(r: RangeEstimate?, units: String): String? =
        r?.let { "≈ " + (if (units == Units.MPH) Fmt.n(it.mi, 1) + " mi" else Fmt.n(it.km, 1) + " км") + " до отключения" }

    /** «7,8 А · 31,41 В» under the bar; [fast]: the fast current, like the bar. */
    fun ampsVolts(t: Telemetry, fast: Boolean): String = Fmt.n(Power.amps(t, fast), 1) + " А · " + Fmt.n(t.voltage, 2) + " В"

    /** The status pill: [StatusText.pill] made short for the top bar; [narrow] (360 dp): «Связь…» for a reconnect. */
    fun pill(link: Link, streaming: Boolean, scanning: Boolean, narrow: Boolean): String = when {
        link == Link.CONNECTED && !streaming -> "Жду данные"
        link == Link.RECONNECTING && narrow -> "Связь…"
        else -> StatusText.pill(link, streaming, scanning)
    }

    /** «Поездка · с 21:42 · 98 Вт·ч» while it goes on ([endMs] null), «Поездка · 21:42–22:22» once it ended. */
    fun tripTitle(startMs: Long, endMs: Long?, whNet: Double?): String =
        if (endMs == null) "Поездка · с " + Fmt.clock(startMs, seconds = false) + (whNet?.let { " · " + Fmt.n(it) + " Вт·ч" } ?: "")
        else "Поездка · " + Fmt.clock(startMs, seconds = false) + "–" + Fmt.clock(endMs, seconds = false)

    /** «22 мин», «1 ч 05 мин». */
    fun minutes(s: Long): String = if (s >= 3600) "${s / 3600} ч ${"%02d".format((s / 60) % 60)} мин" else "${s / 60} мин"

    enum class LogsKind { UNSENT, SENDING, SENT }

    /** The logs tile: [n] logs, [k] of them sent so far (SENDING), the commit (SENT). */
    data class LogsTile(val kind: LogsKind, val n: Int, val k: Int, val commit: String?)

    /** Sending now; else unsent logs; else, for [SENT_SHOW_MS] after a bulk send that ended with a commit, «На GitHub»; else none. */
    fun logsTile(unsent: List<LocalLog>, log: LogStatus, nowMs: Long): LogsTile? = when {
        log.bulkBusy -> LogsTile(LogsKind.SENDING, maxOf(log.bulkTotal, 1), log.bulkDone, null)
        unsent.isNotEmpty() -> LogsTile(LogsKind.UNSENT, unsent.size, 0, null)
        log.bulkCommit != null && log.bulkAt > 0 && nowMs - log.bulkAt in 0 until SENT_SHOW_MS -> LogsTile(LogsKind.SENT, log.bulkTotal, log.bulkTotal, log.bulkCommit)
        else -> null
    }

    const val SENT_SHOW_MS = 5 * 60_000L

    /** «3 лога», «1 лог», «5 логов». */
    fun logs(n: Int): String = "$n " + plural(n, "лог", "лога", "логов")

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m100 = n % 100
        val m10 = n % 10
        return when {
            m100 in 11..14 -> many
            m10 == 1 -> one
            m10 in 2..4 -> few
            else -> many
        }
    }
}
