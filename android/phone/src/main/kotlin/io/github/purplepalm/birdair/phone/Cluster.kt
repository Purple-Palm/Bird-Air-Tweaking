package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.BatteryAdvisor
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampChange
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.MotionFrame
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.core.WarningLevel
import java.util.Locale

/** The tell-tales in words: the «Приборка» card, its journal, the log marks and the notification. */
object LampText {
    /** The lamp's text without the advice after « — » («Перегрев — сбавь газ» → «Перегрев»; «Полная — тормози мягко»,
     *  the dial's short form, → «Батарея полная»). */
    fun short(l: Lamp): String = l.text.substringBefore(" — ").let { WHOLE[it] ?: it }.ifEmpty { TellTales.NAMES[l.id] ?: l.id }
    private val WHOLE = mapOf("Полная" to "Батарея полная")

    /** What the value is, when the text does not say it already: «49 °C», «15,8 А», «28,9 км/ч»; null: nothing to add. */
    fun value(l: Lamp, units: String = Units.KMH): String? {
        val v = l.value ?: return null
        if (l.text.any { it.isDigit() }) return null
        return when (l.id) {
            TellTales.TEMP, TellTales.COLD -> Fmt.n(v) + " °C"
            TellTales.BATTERY, TellTales.OVERVOLT -> Fmt.n(v, 1) + " В"
            TellTales.TRACTION -> if (l.text.contains("просадка")) Fmt.n(v, 1) + " В" else Fmt.n(v, 1) + " А"
            TellTales.ECO -> Fmt.n(v) + " Вт"
            TellTales.CHARGING -> Fmt.n(v) + " °C"                     // «ждёт остывания»: the hotter sensor
            TellTales.OVERSPEED -> Fmt.n(Units.speed(v, units), 1) + " " + Units.speedLabel(units)
            else -> null
        }
    }

    /** «Батарея горячая 49 °C», «Перегрев 56 °C ▲» (▲: climbing fast, when the text does not say so), «ECO 180 Вт»,
     *  «Нет связи с BMS (код 0x26)». */
    fun display(l: Lamp, units: String = Units.KMH): String =
        short(l) + (value(l, units)?.let { " $it" } ?: "") + (if (l.rising && l.id == TellTales.TEMP && !l.text.contains("греется")) " ▲" else "") +
            if (l.id == TellTales.BATTERY && l.text.startsWith(NO_BMS)) " (код 0x" + String.format(Locale.ROOT, "%02X", MotionFrame.CODE_NO_BMS) + ")" else ""

    /** The charge lamp lit for the BMS itself: its under-voltage flag or the controller's «no BMS» code. */
    fun bms(text: String): Boolean = text.startsWith(NO_BMS) || text.startsWith(BMS_CUT)

    /** The core's texts ([TellTales.lamps]) that the phone treats apart. */
    const val NO_BMS = "Нет связи с BMS"
    const val BMS_CUT = "BMS отключает батарею"

    /** The same with the advice («Перегрев — сбавь газ 56 °C»): the notification, the dial's line on the card. */
    fun full(l: Lamp, units: String = Units.KMH): String =
        l.text.ifEmpty { short(l) } + (value(l, units)?.let { " · $it" + if (l.id == TellTales.BATTERY) " под нагрузкой" else "" } ?: "")

    /** A lamp that lit or changed colour, as a log mark: «⚠ Батарея горячая 49 °C», «⛔ Перегрев 56 °C». */
    fun mark(l: Lamp): String = symbol(l.level) + " " + display(l)

    /** One that went off: «✓ Батарея горячая — погасла»; [label] is [offLabel] of the lamp as it was last marked. */
    fun offMark(label: String): String = "✓ $label — погасла"

    /** How a lamp is named once it is off: its text when that has no numbers, else its name («Заряд», «Перенапряжение»). */
    fun offLabel(l: Lamp): String = short(l).takeIf { s -> s.none { it.isDigit() } } ?: (TellTales.NAMES[l.id] ?: l.id)

    fun symbol(level: LampLevel): String = when (level) {
        LampLevel.RED -> "⛔"
        LampLevel.AMBER, LampLevel.YELLOW -> "⚠"
        LampLevel.GREEN, LampLevel.BLUE -> "●"
        LampLevel.OFF -> "✓"
    }

    /** The journal's line: «Температура батареи: Батарея горячая 49 °C · оранжевая», «Мало тяги: просадка 31,7 В ·
     *  оранжевая» (the name once), «Мало тяги — погасла». */
    fun journal(e: LampEvent, units: String = Units.KMH): String {
        val name = TellTales.NAMES[e.id] ?: e.id
        val l = e.lamp
        if (l == null || e.to == LampLevel.OFF) return "$name — погасла"
        val d = display(l, units)
        return (if (d.startsWith(name, ignoreCase = true)) d else "$name: $d") + " · " + (TellTales.LEVEL_NAMES[e.to] ?: "")
    }

    /** «Горит оранжевым», «Не горит». */
    fun state(level: LampLevel): String = when (level) {
        LampLevel.OFF -> "Не горит"
        LampLevel.GREEN -> "Горит зелёным"
        LampLevel.BLUE -> "Горит синим"
        LampLevel.YELLOW -> "Горит жёлтым"
        LampLevel.AMBER -> "Горит оранжевым"
        LampLevel.RED -> "Горит красным, мигает"
    }

    /** [TellTales.HELP] with a capital letter: «Жёлтая — батарея ≥ 40 °C …». */
    fun help(id: String): String = (TellTales.HELP[id] ?: "").replaceFirstChar { it.uppercaseChar() }

    /** What to do, for the notification. */
    fun hint(l: Lamp): String = when (l.id) {
        TellTales.TEMP -> if (l.level == LampLevel.RED) "Дай батарее остыть: сбавь скорость или остановись. 15.10 защита батареи отключила самокат на 57 °C."
            else "Батарея горячая. Если поднимется до 55 °C — остановись остыть."
        TellTales.BATTERY -> when {
            l.text.startsWith(NO_BMS) -> "Контроллер не видит BMS (код 0x26): батарея отключена, мотор не потянет. На руле, видимо, ошибка 12."
            l.text.startsWith(BMS_CUT) -> "BMS отключает батарею по низкому напряжению: через секунды мотор пропадёт. Остановись; дальше — зарядка."
            l.level == LampLevel.RED -> "Под нагрузкой у отсечки: контроллер вот-вот отключит мотор. Сбавь газ, ищи где встать."
            else -> "Батарея садится к отсечке: внизу заряда контроллер урезает ток, отключиться может раньше 0 %."
        }
        TellTales.TRACTION -> "Ток у предела контроллера (~16 А) или батарея проседает: мощности больше не будет."
        TellTales.CONSUMPTION -> "Расход больше 20 Вт·ч/км за последние 2 км: запас хода тает быстрее."
        TellTales.OVERVOLT -> if (l.level == LampLevel.RED) "Батарея полная: защита BMS отключила рекуперацию, мотор не тормозит. Тормози ручным тормозом."
            else "Батарея полная, рекуперация поднимает её выше 42,2 В: тормози мягче или ручным тормозом."
        TellTales.CHARGING -> if (l.level == LampLevel.AMBER) "Зарядка подключена, но батарея горячая: BMS не пускает ток, пока не остынет до ≈37 / 39 °C. Если не пошла — дай остыть 10–20 мин и переткни."
            else help(l.id)
        else -> help(l.id)
    }
}

/** One lamp transition in the «Приборка» journal; [atMs] wall clock (epoch ms), [lamp] the new state (null: off). */
data class LampEvent(val atMs: Long, val id: String, val from: LampLevel, val to: LampLevel, val lamp: Lamp?)

/** The last [capacity] lamp transitions, newest first. Not thread-safe: the publisher is the one writer. */
class LampJournal(val capacity: Int = CAPACITY) {
    private val buf = arrayOfNulls<LampEvent>(capacity)
    private var head = 0
    var size = 0
        private set

    fun add(e: LampEvent) {
        buf[head] = e
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** Newest first. */
    fun list(): List<LampEvent> = List(size) { k -> buf[(head - 1 - k + capacity) % capacity]!! }

    fun clear() { buf.fill(null); head = 0; size = 0 }

    companion object {
        const val CAPACITY = 200
    }
}

/**
 * Which lamp changes go into the log as marks ([LampText.mark] / [LampText.offMark]), so a lamp that flickers does
 * not bury the ride under marks:
 *  - a lamp stepping up to YELLOW, AMBER or RED is marked at once (a red never waits);
 *  - anything else (going off, stepping down, GREEN / BLUE states) at most once per lamp per [gapMs]: what is left
 *    is marked when the gap is over, if the lamp is still not as last marked (on and off again within it: nothing);
 *  - ECO, overspeed, link and traction ([noisy]) only that way, steps up included; ECO only once it has been on
 *    [ecoMinMs] (traction two-up at the current limit relights every few seconds: 15.10 30 marks, now 20);
 *  - the BMS's and the controller's own conditions ([EVENTS]) at once when the lamp shows one, at its level or not and
 *    noisy or not (20.10 the charge lamp was red with «Сейчас отключится» when the BMS cut the pack, the link lamp
 *    amber with «Руль молчит» when the dash answered with the BMS silent); the same one of a lamp again only after
 *    [gapMs].
 * Times are the session clock (the log's). Not thread-safe: the publisher is the one caller.
 */
class LampMarks(val gapMs: Long = GAP_MS, val ecoMinMs: Long = ECO_MIN_MS, val noisy: Set<String> = NOISY) {
    /** A mark to write: lamp [id] is now [level] (OFF: went off); [text] goes into the log; [event]: one of [EVENTS]. */
    data class Mark(val id: String, val level: LampLevel, val text: String, val event: Boolean = false)

    private class St {
        var marked = LampLevel.OFF
        var markedAt = Long.MIN_VALUE / 2
        var label = ""
        var since: Long? = null
        var event: String? = null                   // the [EVENTS] text shown at the last update
        val eventAt = HashMap<String, Long>()       // … and when each was marked
    }

    private val st = HashMap<String, St>()

    /** The marks to write for the lamps of a snapshot at [nowMs], in [TellTales.IDS] order. */
    fun update(lamps: List<Lamp>, nowMs: Long): List<Mark> {
        val by = lamps.associateBy { it.id }
        var out: ArrayList<Mark>? = null
        for (id in TellTales.IDS) {
            val s = st.getOrPut(id) { St() }
            val l = by[id]?.takeIf { it.level != LampLevel.OFF }
            val cur = l?.level ?: LampLevel.OFF
            if (cur == LampLevel.OFF) s.since = null else if (s.since == null) s.since = nowMs
            val ev = l?.let { event(it) }
            val newEvent = ev != null && ev != s.event && nowMs - (s.eventAt[ev] ?: Long.MIN_VALUE / 2) >= gapMs
            s.event = ev
            if (!newEvent) {
                if (cur == s.marked) continue
                if (id == TellTales.ECO && l != null && nowMs - (s.since ?: nowMs) < ecoMinMs) continue
                val up = id !in noisy && cur.ordinal > s.marked.ordinal && cur.ordinal >= LampLevel.YELLOW.ordinal
                if (!up && nowMs - s.markedAt < gapMs) continue
            } else s.eventAt[ev!!] = nowMs
            (out ?: ArrayList<Mark>().also { out = it }).add(Mark(id, cur, if (l == null) LampText.offMark(s.label) else LampText.mark(l), newEvent))
            s.marked = cur
            s.markedAt = nowMs
            if (l != null) s.label = LampText.offLabel(l)
        }
        return out ?: emptyList()
    }

    /** A new recording. */
    fun reset() = st.clear()

    companion object {
        const val GAP_MS = 30_000L
        const val ECO_MIN_MS = 30_000L
        val NOISY = setOf(TellTales.ECO, TellTales.OVERSPEED, TellTales.LINK, TellTales.TRACTION)

        /** The core's texts ([TellTales.lamps]) for what the BMS and the controller say themselves, by lamp. */
        val EVENTS: Map<String, List<String>> = mapOf(
            TellTales.BATTERY to listOf(LampText.BMS_CUT, LampText.NO_BMS),
            TellTales.LINK to listOf("Руль на связи, BMS молчит"),
            TellTales.OVERVOLT to listOf("Защита от перенапряжения"),
            TellTales.CHARGING to listOf("Зарядка ждёт остывания"),
        )

        /** The [EVENTS] text lamp [l] shows, or null. */
        fun event(l: Lamp): String? = EVENTS[l.id]?.firstOrNull { l.text.startsWith(it) }
    }
}

/** One line of the «Предупреждения» notification, by lamp [id]: AMBER or RED. */
data class AlertItem(val id: String, val level: LampLevel, val title: String, val line: String, val hint: String)

/**
 * When the «Предупреждения» notification buzzes: the cluster's RED and AMBER lamps and the battery advisor's warnings
 * (battery_hot is the temperature lamp's subject, battery_low the charge lamp's: one item each, the stronger wins).
 *  - RED: when it lights or steps up to red, at most once per lamp per [redCooldownMs];
 *  - AMBER: once per ride (and not again within [redCooldownMs] after a quick restart);
 *  - going down never buzzes, the text changes silently; nothing left — the notification goes.
 * The title is the item that buzzed ([Decision.head]), not the first in the list: 20.10 «Заряд на исходе» buzzed with
 * «Батарея горячая» (lit and buzzed before, first in [TellTales.IDS]) as the title. It stays the title while it is on
 * and nothing stronger is; then the strongest item is.
 * Not on it: [TellTales.QUIET] lamps (standing conditions, ECO) and the link (the «Поездка» notification says it).
 * Pure logic (the clock is passed in); not thread-safe — the publisher is the one caller.
 */
class RideAlerts(val redCooldownMs: Long = COOLDOWN_MS) {
    enum class Action { NONE, ALERT, UPDATE, CANCEL }

    /** [rang]: the items that made it buzz (ALERT only), strongest first; [head]: the notification's title item. */
    class Decision(val action: Action, val items: List<AlertItem>, val rang: List<AlertItem> = emptyList(), val head: AlertItem? = items.firstOrNull())

    private var prev: Map<String, LampLevel> = emptyMap()
    private val redAt = HashMap<String, Long>()
    private val amberAt = HashMap<String, Long>()
    private val amberThisRide = HashSet<String>()
    private var headId: String? = null

    fun update(lamps: List<Lamp>, warnings: List<BatteryWarning>, nowMs: Long, units: String = Units.KMH): Decision {
        val items = items(lamps, warnings, units)
        val cur = items.associate { it.id to it.level }
        if (cur == prev) return Decision(Action.NONE, items, head = head(items))
        val was = prev
        prev = cur
        if (cur.isEmpty()) { headId = null; return Decision(Action.CANCEL, items) }
        val rang = ArrayList<AlertItem>()
        for (item in items) {
            val id = item.id
            val before = was[id]
            if (before != null && item.level.ordinal <= before.ordinal) continue
            if (item.level == LampLevel.RED) {
                val last = redAt[id]
                if (last == null || nowMs - last >= redCooldownMs) { rang.add(item); redAt[id] = nowMs }
                amberThisRide.add(id)
            } else if (id !in amberThisRide) {
                amberThisRide.add(id)
                val last = amberAt[id]
                if (last == null || nowMs - last >= redCooldownMs) { rang.add(item); amberAt[id] = nowMs }
            }
        }
        if (rang.isNotEmpty()) { headId = rang.first().id; return Decision(Action.ALERT, items, rang, rang.first()) }
        return Decision(Action.UPDATE, items, head = head(items))
    }

    /** The item that buzzed last while it is on and as strong as any, else the strongest. */
    private fun head(items: List<AlertItem>): AlertItem? {
        val top = items.firstOrNull() ?: return null
        val h = items.firstOrNull { it.id == headId }
        if (h != null && h.level == top.level) return h
        headId = top.id
        return top
    }

    /** A new ride: what is on counts as new again and AMBER may buzz once more; the cooldowns stay. */
    fun reset() { prev = emptyMap(); amberThisRide.clear(); headId = null }

    companion object {
        const val COOLDOWN_MS = 5 * 60_000L

        /** RED first, then AMBER, each in [TellTales.IDS] order. */
        fun items(lamps: List<Lamp>, warnings: List<BatteryWarning>, units: String = Units.KMH): List<AlertItem> {
            val out = LinkedHashMap<String, AlertItem>()
            for (l in lamps) {
                if (l.id in TellTales.QUIET || l.id == TellTales.LINK) continue
                if (l.level != LampLevel.RED && l.level != LampLevel.AMBER) continue
                out[l.id] = AlertItem(l.id, l.level, l.text.ifEmpty { LampText.short(l) }, LampText.full(l, units), LampText.hint(l))
            }
            for (w in warnings) {
                val id = when (w.id) { BatteryAdvisor.HOT -> TellTales.TEMP; BatteryAdvisor.LOW -> TellTales.BATTERY; else -> continue }
                val lvl = if (w.level == WarningLevel.CRITICAL) LampLevel.RED else LampLevel.AMBER
                val have = out[id]
                if (have == null || lvl.ordinal > have.level.ordinal) out[id] = AlertItem(id, lvl, w.text, WarnText.line(w), WarnText.hint(w))
                // the advisor's advice, unless the charge lamp is lit for the BMS itself («BMS отключает батарею», «Нет связи с BMS»)
                else if (have.level == lvl && !(id == TellTales.BATTERY && LampText.bms(have.title))) out[id] = have.copy(hint = WarnText.hint(w))
            }
            return out.values.sortedWith(compareByDescending<AlertItem> { it.level.ordinal }.thenBy { TellTales.IDS.indexOf(it.id) })
        }

        fun title(items: List<AlertItem>): String = items.firstOrNull()?.title ?: ""
        fun body(items: List<AlertItem>): String = items.joinToString("\n") { it.line }

        /** The notification's words: title and line of [head] (the item that buzzed), every line in the big text with
         *  [head]'s first, then its advice. */
        fun text(items: List<AlertItem>, head: AlertItem? = items.firstOrNull()): AlertText {
            val h = head ?: items.firstOrNull() ?: return AlertText("", "", "")
            val body = body(listOf(h) + items.filter { it.id != h.id })
            return AlertText(h.title, h.line, body + if (h.hint.isNotEmpty()) "\n" + h.hint else "")
        }
    }
}

/** What the «Предупреждения» notification says: [title], [text] (collapsed), [big] (expanded). */
data class AlertText(val title: String, val text: String, val big: String)

/**
 * What the phone does with the lamps of each published snapshot: the journal ([journal], every level change and each
 * BMS / controller condition), the log marks ([LampMarks]) and the notification decision ([RideAlerts]). Not
 * thread-safe: the publisher is the one caller.
 */
class ClusterWatch(capacity: Int = LampJournal.CAPACITY) {
    private var prev: List<Lamp> = emptyList()
    val journal = LampJournal(capacity)
    val marks = LampMarks()
    val alerts = RideAlerts()

    /** The lamp level changes of [lamps] since the last call, and a lamp that kept its level but now shows one of
     *  [LampMarks.EVENTS] («Сейчас отключится» → «BMS отключает батарею», red both), added to the journal at [wallMs]. */
    fun changes(lamps: List<Lamp>, wallMs: Long): List<LampChange> {
        val ch = TellTales.changes(prev, lamps)
        val was = prev.associateBy { it.id }
        prev = lamps
        val evs = lamps.filter { l ->
            val e = LampMarks.event(l)
            val w = was[l.id]
            e != null && w != null && w.level == l.level && LampMarks.event(w) != e
        }
        val all = if (evs.isEmpty()) ch else (ch + evs.map { LampChange(it.id, it.level, it.level, it) }).sortedBy { TellTales.IDS.indexOf(it.id) }
        for (c in all) journal.add(LampEvent(wallMs, c.id, c.from, c.to, c.lamp))
        return all
    }

    /** A new recording: the journal, the marks and «once per ride» start over (the RED cooldown stays). */
    fun reset() { prev = emptyList(); journal.clear(); marks.reset(); alerts.reset() }
}
