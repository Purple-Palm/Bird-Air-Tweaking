package io.github.purplepalm.birdair.dial

import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * «Растущие лампы»'s layout ([GrowLayout]): the mock's selftest (every combination of up to 4 loud lamps × their levels,
 * with the mock's texts and the widths its browser measured) gives the mock's own numbers; a sample of the mock's layouts
 * comes out the same to 0,01 px; and with the phone's real texts (long titles, km/h and mph, rows missing) the invariants
 * hold the same way.
 */
class FaceGrowLayoutTest {
    private val nb = TellTales.NBSP

    // ---------------- the mock's own data (grow.html → src/test/resources/grow-golden.json; GROW_GOLDEN: the full dump)

    private val golden: JsonObject by lazy {
        val full = System.getenv("GROW_GOLDEN")?.let { File(it) }?.takeIf { it.isFile }
        val text = full?.readText() ?: javaClass.getResource("/grow-golden.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject
    }
    private val widths: Map<String, Double> by lazy { golden["widths"]!!.jsonObject.mapValues { it.value.jsonPrimitive.double } }
    private fun key(text: String, px: Double, weight: Int) = "$weight ${if (px % 1.0 == 0.0) px.toInt().toString() else px.toString()}px|$text"
    private val mockMeasure = GrowMeasure { t, px, w -> widths[key(t, px, w)] ?: error("no width for ${key(t, px, w)}") }

    private val mockPlates = mapOf(
        "temp" to "55${nb}°C", "battery" to "31,5${nb}В", "traction" to "Тяга", "consumption" to "Расход", "overvolt" to "Полная",
        "link" to "Связь", "overspeed" to "29${nb}км/ч", "charging" to "1,7${nb}А", "cold" to "3${nb}°C",
    )
    private val levels = mapOf(
        "temp" to listOf("yellow", "amber", "red"), "battery" to listOf("amber", "red"), "traction" to listOf("amber"),
        "consumption" to listOf("amber"), "overvolt" to listOf("amber", "red"), "link" to listOf("amber"), "overspeed" to listOf("blue"),
        "charging" to listOf("green", "amber"), "cold" to listOf("blue"),
    )

    private fun level(s: String) = LampLevel.valueOf(s.uppercase())

    /** The mock's selftest input for [set] (id → level): the service lamp stands, the line is «Текст лампы <top>». */
    private fun mockInput(set: Map<String, LampLevel>): GrowLayout.Input {
        val texts = golden["texts"]!!.jsonObject
        fun w(t: String, px: Double, weight: Int) = mockMeasure.width(t, px, weight)
        val det = texts["det"]!!.jsonArray.map { it.jsonPrimitive.content }
        val lamps = listOf(GrowLayout.In(TellTales.SERVICE, LampLevel.AMBER, listOf("Сервис"))) +
            set.map { (id, lv) -> GrowLayout.In(id, lv, listOf(mockPlates.getValue(id))) }
        val top = GrowLayout.order(lamps).first()
        return GrowLayout.Input(
            lamps,
            GrowLayout.Need(
                range = w(texts["range"]!!.jsonPrimitive.content, 26.0, 500), det = w(det[0], 19.0, 400), detShort = w(det[1], 19.0, 400),
                msg = w("Текст лампы " + top.id, 20.0, 600), pow = w(texts["pow"]!!.jsonPrimitive.content, 20.0, 400),
                clock = w(texts["clock"]!!.jsonPrimitive.content, 20.0, 400),
            ),
        )
    }

    private fun combos(ids: List<String>, max: Int): List<List<String>> {
        val out = ArrayList<List<String>>()
        fun rec(start: Int, cur: MutableList<String>) {
            if (cur.isNotEmpty()) out += cur.toList()
            if (cur.size == max) return
            for (i in start until ids.size) { cur += ids[i]; rec(i + 1, cur); cur.removeAt(cur.size - 1) }
        }
        rec(0, ArrayList())
        return out
    }

    private fun <T> sets(ids: List<String>, levelsOf: (String) -> List<T>): List<Map<String, T>> =
        ids.fold(listOf(emptyMap())) { acc, id -> acc.flatMap { a -> levelsOf(id).map { lv -> a + (id to lv) } } }

    /** __dial.selftest() of grow.html: 1032 layouts, the main lamp never moved, 787 minis, 147 pips, no problem. */
    @Test fun theMocksSelftest() {
        var n = 0; var mainMoved = 0; var minis = 0; var pips = 0; var maxShift = 0.0; var minMain = 1e9; var minMsg = 1.0; var minDet = 1.0
        val bad = ArrayList<String>()
        for (ids in combos(levels.keys.toList(), 4)) for (set in sets(ids) { levels.getValue(it).map(::level) }) {
            n++
            val input = mockInput(set)
            val p = GrowLayout.plan(input, mockMeasure)
            bad += GrowLayout.problems(input, p).map { "$set $it" }
            val main = p.badges.first()
            if (abs(abs(main.a) - main.a0) > 0.01) mainMoved++
            if (main.plate == null) bad += "$set main has no plate"
            minMain = minOf(minMain, main.d)
            for (b in p.badges) maxShift = maxOf(maxShift, abs(abs(b.a) - b.a0))
            p.rows.msg?.let { minMsg = minOf(minMsg, it.s) }
            minDet = minOf(minDet, p.rows.det.s)
            for (s in p.small) if (s.kind == GrowLayout.Kind.MINI) { if (s.pip) pips++ else minis++ }
        }
        assertEquals(emptyList(), bad.take(12))
        assertEquals(1032, n)
        assertEquals(0, mainMoved)
        assertEquals(787, minis)
        assertEquals(147, pips)
        assertEquals(12.0, maxShift, 1e-9)
        assertEquals(104.0, minMain, 1e-9)
        assertEquals(0.9, minMsg, 0.005)
        assertEquals(0.84, minDet, 0.005)
    }

    /** The mock's layouts themselves (a sample; GROW_GOLDEN=<dump.json> compares all 1032): the same to 0,01 px. */
    @Test fun theMocksLayouts() {
        val layouts = golden["layouts"]!!.jsonArray
        assertTrue(layouts.size >= 60)
        for (el in layouts) {
            val o = el.jsonObject
            val set = o["set"]!!.jsonObject.mapValues { level(it.value.jsonPrimitive.content) }
            val p = GrowLayout.plan(mockInput(set), mockMeasure)
            val tag = set.toString()
            val bs = o["badges"]!!.jsonArray.map { it.jsonObject }
            assertEquals(bs.map { it.str("id") }, p.badges.map { it.id }, tag)
            for ((b, k) in p.badges.zip(bs)) {
                assertEquals(k.num("rank").toInt(), b.rank, tag)
                near(k.num("a"), b.a, "$tag ${b.id} a"); near(k.num("D"), b.d, "$tag ${b.id} D")
                near(k.num("x"), b.x, "$tag ${b.id} x"); near(k.num("y"), b.y, "$tag ${b.id} y")
                val kp = k["plate"] as? JsonObject
                assertEquals(kp != null, b.plate != null, "$tag ${b.id} plate")
                if (kp != null) {
                    val pl = b.plate!!
                    near(kp.num("px"), pl.px, tag); near(kp.num("w"), pl.w, tag); near(kp.num("h"), pl.h, tag)
                    near(kp.num("cx"), pl.cx, tag); near(kp.num("y0"), pl.y0, tag)
                    assertEquals(kp.str("pos") == "below", pl.below, tag)
                }
            }
            for (k in o["small"]!!.jsonArray.map { it.jsonObject }) {
                val id = k.str("id")
                val s = assertNotNull(p.small(id), "$tag $id")
                assertEquals(k.str("kind"), s.kind.name.lowercase(), "$tag $id")
                assertEquals((k["pip"] as? JsonPrimitive)?.booleanOrNull == true, s.pip, "$tag $id pip")
                val x = (k["x"] as? JsonPrimitive)?.doubleOrNull
                assertEquals(x == null, s.spot == null, "$tag $id hidden")
                if (x != null) { near(x, s.spot!!.x, "$tag $id x"); near(k.num("y"), s.spot!!.y, "$tag $id y") }
                if (s.kind == GrowLayout.Kind.MINI) near(k.num("D"), s.d, "$tag $id D")
            }
            val r = o["rows"]!!.jsonObject
            fun row(name: String, f: GrowLayout.Fit?) { val k = r[name]!!.jsonObject; near(k.num("cx"), f!!.cx, "$tag $name cx"); near(k.num("s"), f.s, "$tag $name s") }
            row("clock", p.rows.clock); row("bat", p.rows.bat); row("range", p.rows.range); row("det", p.rows.det); row("msg", p.rows.msg); row("pow", p.rows.pow)
            assertEquals(r["clock"]!!.jsonObject.num("a"), p.rows.clockA, tag)
            assertEquals(r["detShort"]!!.jsonPrimitive.booleanOrNull, p.rows.detShort, tag)
            assertEquals(r["msg"]!!.jsonObject.num("y"), p.rows.msgY, tag)
            assertEquals(r["pow"]!!.jsonObject.num("y"), p.rows.powY, tag)
        }
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.num(k: String) = this[k]!!.jsonPrimitive.double
    private fun near(want: Double, got: Double, what: String) = assertTrue(abs(want - got) <= 0.011, "$what: $want ≠ $got")

    // ---------------- the phone's real texts

    /** Roboto's advances, roughly (em): enough to test the layout with texts it has never seen. */
    private val model = GrowMeasure { text, px, weight ->
        var em = 0.0
        for (c in text) em += when {
            c.isDigit() -> 0.56
            c == ' ' || c == ' ' -> 0.25
            c == ' ' -> 0.2
            c in ",.:;·'!|" -> 0.26
            c in "°" -> 0.38
            c in "%" -> 0.73
            c in "—" -> 1.0
            c in "−≈+-" -> 0.56
            c in "ШЩЖМЮФЫ" -> 0.9
            c in "шщжмюфы" -> 0.76
            c.isUpperCase() -> 0.64
            c in "mw" -> 0.82
            c in "ilj" -> 0.24
            else -> 0.53
        }
        em * 0.93 * px * (1 + (weight - 400) / 3000.0)
    }

    /** The model against the mock's browser (Roboto / Liberation Sans): within 12 % on its 51 texts. */
    @Test fun theWidthModel() {
        for ((k, w) in widths) {
            val (font, text) = k.split('|', limit = 2)
            val (weight, px) = font.split(' ')
            val got = model.width(text, px.removeSuffix("px").toDouble(), weight.toInt())
            assertTrue(abs(got - w) / w < 0.12, "$k: $w ≠ $got")
        }
    }

    /** Each lamp's real lamps (TellTales texts and values): every level it can have, the longest texts. */
    private fun real(units: String): Map<String, List<Lamp>> {
        fun l(id: String, lv: LampLevel, text: String, v: Double? = null, rising: Boolean = false) = TellTales.lamp(id, lv, text, v, rising, units)
        return mapOf(
            TellTales.TEMP to listOf(
                l(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_WARM_RISING, 42.0, true), l(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT_RISING, 51.0, true),
                l(TellTales.TEMP, LampLevel.RED, TellTales.T_OVERHEAT, 57.0),
            ),
            TellTales.BATTERY to listOf(
                l(TellTales.BATTERY, LampLevel.AMBER, "Заряд 24,3 %", 24.3), l(TellTales.BATTERY, LampLevel.AMBER, TellTales.T_LOW, 32.4),
                l(TellTales.BATTERY, LampLevel.RED, TellTales.T_CUTTING, 31.249), l(TellTales.BATTERY, LampLevel.RED, TellTales.T_BMS_CUT, 31.2),
                l(TellTales.BATTERY, LampLevel.RED, TellTales.T_NO_BMS),
            ),
            TellTales.TRACTION to listOf(l(TellTales.TRACTION, LampLevel.AMBER, TellTales.T_LIMIT, 15.8), l(TellTales.TRACTION, LampLevel.AMBER, TellTales.T_SAG, 31.6)),
            TellTales.CONSUMPTION to listOf(l(TellTales.CONSUMPTION, LampLevel.AMBER, "Расход 124 Вт·ч/км", 124.0)),
            TellTales.OVERVOLT to listOf(
                l(TellTales.OVERVOLT, LampLevel.AMBER, TellTales.T_FULL, 42.4), l(TellTales.OVERVOLT, LampLevel.RED, TellTales.T_OV_CUT, 44.0),
                l(TellTales.OVERVOLT, LampLevel.AMBER, "Перенапряжение 42,6 В", 42.6),
            ),
            TellTales.LINK to listOf(
                l(TellTales.LINK, LampLevel.AMBER, TellTales.T_DASH_NO_BMS), l(TellTales.LINK, LampLevel.AMBER, TellTales.T_LINK_LOST),
                l(TellTales.LINK, LampLevel.AMBER, "Код контроллера 0x21", 33.0),
            ),
            TellTales.OVERSPEED to listOf(l(TellTales.OVERSPEED, LampLevel.BLUE, TellTales.T_OVERSPEED, 31.4)),
            TellTales.CHARGING to listOf(l(TellTales.CHARGING, LampLevel.GREEN, "Зарядка 1,7 А", 1.7), l(TellTales.CHARGING, LampLevel.AMBER, TellTales.T_CHARGE_WAIT, 47.0)),
            TellTales.COLD to listOf(l(TellTales.COLD, LampLevel.BLUE, TellTales.T_COLD, -3.0)),
        )
    }

    private fun realInput(lamps: List<Lamp>, units: String, rows: Int): GrowLayout.Input {
        val m = model
        val mph = units == TellTales.MPH
        val quiet = listOf(
            TellTales.lamp(TellTales.SERVICE, LampLevel.AMBER, "Ёмкость 75 % от паспорта", 75.0, units = units),
            TellTales.lamp(TellTales.WEAK, LampLevel.YELLOW, "Батарея устала: 0,38 Ом", 0.38, units = units),
        )
        val all = lamps + quiet
        val top = TellTales.top(all)
        val det = "41,9${nb}В · 57${nb}°C · TAKEOFF"
        return GrowLayout.Input(
            all.map { GrowLayout.of(it) },
            GrowLayout.Need(
                range = if (rows and 1 != 0) m.width("≈${nb}12,4${nb}${if (mph) "mi" else "км"}", 26.0, 500) else 0.0,
                det = if (rows and 2 != 0) m.width(det, 19.0, 400) else 0.0,
                detShort = if (rows and 2 != 0) m.width(det.replace("${nb}°C", "°"), 19.0, 400) else 0.0,
                msg = top?.let { m.width(it.text, 20.0, 600) } ?: 0.0,
                pow = m.width("1${nb}234${nb}Вт${nb}рекуп.", 20.0, 400),
                clock = m.width("21:10", 20.0, 400),
                status = if (rows and 4 != 0) listOf(m.width("нет данных", 17.0, 500), m.width("с телефона", 17.0, 500)) else emptyList(),
            ),
        )
    }

    /** Every combination of up to 4 loud lamps with every text and level they have, the quiet ones lit, in km/h and mph, rows missing. */
    @Test fun realTextsKeepTheInvariants() {
        var n = 0
        val bad = ArrayList<String>()
        for (units in listOf(TellTales.KMH, TellTales.MPH)) {
            val real = real(units)
            for (ids in combos(real.keys.toList(), 4)) for (set in sets(ids) { real.getValue(it) }) {
                val lamps = TellTales.IDS.mapNotNull { set[it] }
                val rows = n % 8
                val input = realInput(lamps, units, rows)
                val p = GrowLayout.plan(input, model)
                n++
                for (e in GrowLayout.problems(input, p)) bad += "$units ${lamps.map { it.id + ":" + it.level }} $e"
                // the main lamp: its home, a tablet; the tablets inside the screen, never under the rows
                val main = p.badges.first()
                if (main.plate == null) bad += "${lamps.map { it.id }} main has no plate"
                for (b in p.badges) {
                    assertTrue(hypot(b.x - GrowLayout.C, b.y - GrowLayout.C) + b.d / 2 <= GrowLayout.RMAX + 0.5)
                    b.plate?.let { pl -> assertTrue(pl.index in GrowLayout.of(set.getValue(b.id)).plates.indices) }
                }
                for (s in p.small) if (s.kind == GrowLayout.Kind.QUIET) assertTrue(s.id in TellTales.QUIET)
            }
        }
        assertEquals(emptyList(), bad.take(12), "${bad.size} of $n")
        assertTrue(n > 8_000, "$n")
    }

    /** A tablet: as the mock, the number where it is the news, else a word; then the rest; only what fits. */
    @Test fun tablets() {
        fun plates(id: String, lv: LampLevel, text: String, v: Double? = null) = GrowLayout.of(TellTales.lamp(id, lv, text, v)).plates
        assertEquals(listOf("49${nb}°C", "Батарея горячая", "Темп."), plates(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 49.0))
        assertEquals(listOf("31,5${nb}В", "Сейчас отключится", "Заряд"), plates(TellTales.BATTERY, LampLevel.RED, TellTales.T_CUTTING, 31.5))
        assertEquals(listOf("BMS", "31,2${nb}В", "BMS отключает"), plates(TellTales.BATTERY, LampLevel.RED, TellTales.T_BMS_CUT, 31.2))
        assertEquals(listOf("BMS", "Нет связи с BMS"), plates(TellTales.BATTERY, LampLevel.RED, TellTales.T_NO_BMS))
        assertEquals(listOf("Тяга", "15,8${nb}А", "16${nb}А", "Мало тяги"), plates(TellTales.TRACTION, LampLevel.AMBER, TellTales.T_LIMIT, 15.8))
        assertEquals(listOf("Расход", "24${nb}Вт·ч/км", "Высокий расход"), plates(TellTales.CONSUMPTION, LampLevel.AMBER, "Расход 24 Вт·ч/км", 24.0))
        assertEquals(listOf("Полная", "42,4${nb}В"), plates(TellTales.OVERVOLT, LampLevel.AMBER, TellTales.T_FULL, 42.4))
        assertEquals(listOf("Защита", "44,0${nb}В", "Перенапряжение"), plates(TellTales.OVERVOLT, LampLevel.RED, TellTales.T_OV_CUT, 44.0))
        assertEquals(listOf("Связь"), plates(TellTales.LINK, LampLevel.AMBER, TellTales.T_LINK_LOST))
        assertEquals(listOf("BMS", "BMS молчит"), plates(TellTales.LINK, LampLevel.AMBER, TellTales.T_DASH_NO_BMS))
        assertEquals(listOf("0x21", "Код контроллера", "Код"), plates(TellTales.LINK, LampLevel.AMBER, "Код контроллера 0x21", 33.0))
        assertEquals(listOf("1,7${nb}А", "2${nb}А", "Зарядка"), plates(TellTales.CHARGING, LampLevel.GREEN, "Зарядка 1,7 А", 1.7))
        assertEquals("31${nb}км/ч", GrowLayout.whole("31,4${nb}км/ч"))
        assertEquals("20${nb}mph", GrowLayout.whole("19,5${nb}mph"))
        assertEquals(null, GrowLayout.whole("31,5${nb}В"))                        // volts keep their tenth
        // «Выше предела» in mph: the main lamp at −100° wears the speed (whole if «19,5 mph» does not fit)
        val over = TellTales.lamp(TellTales.OVERSPEED, LampLevel.BLUE, TellTales.T_OVERSPEED, 31.4, units = TellTales.MPH)
        assertEquals(listOf("19,5${nb}mph", "20${nb}mph", "Выше предела", "Быстро"), GrowLayout.of(over).plates)
        val input = realInput(listOf(over), TellTales.MPH, 3)
        val p = GrowLayout.plan(input, model)
        val b = p.badges.single()
        assertEquals(TellTales.OVERSPEED, b.id)
        assertTrue(b.plate!!.index <= 1, "${b.plate}")
        assertEquals(-100.0, b.a, 1e-9)
        assertEquals(104.0, b.d, 1e-9)
        // nothing lit: no badge, all 12 on the ring, the clock shown, the power under the details
        val calm = GrowLayout.plan(realInput(emptyList(), TellTales.KMH, 3), model)
        assertTrue(calm.badges.isEmpty())
        assertEquals(12, calm.small.size)
        assertEquals(1.0, calm.rows.clockA)
        assertEquals(330.0, calm.rows.powY)
        assertEquals(null, calm.rows.msg)
    }
}
