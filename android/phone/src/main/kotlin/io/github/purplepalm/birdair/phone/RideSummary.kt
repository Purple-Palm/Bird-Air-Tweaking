package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Why a recording ended: «Отключить», the link given up on, the system ending the service. */
enum class EndCause { OWNER, LOST, SYSTEM }

/** The plate under the last ride: «BMS отключила батарею» and its numbers; [crit] red. */
data class RideEnd(val title: String, val detail: String, val crit: Boolean)

/**
 * The last recording as the idle dash shows it (kept in Prefs, it survives the process): [startMs]…[endMs] wall clock,
 * [km] by the app odometer, [movingS] seconds above 1 km/h, net energy and its Wh/km, why it ended, and the battery
 * at the end ([pct] to 0.1 % when known, [volts], [mah] of [fullMah]).
 */
data class LastRide(
    val startMs: Long, val endMs: Long, val km: Double?, val movingS: Long, val whNet: Double, val whPerKm: Double?,
    val end: RideEnd?, val pct: Double?, val volts: Double?, val mah: Double?, val fullMah: Double?,
) {
    fun encode(): String = JsonObject(buildMap {
        put("start", JsonPrimitive(startMs)); put("end", JsonPrimitive(endMs))
        put("km", num(km)); put("movingS", JsonPrimitive(movingS)); put("wh", JsonPrimitive(whNet)); put("whKm", num(whPerKm))
        put("pct", num(pct)); put("v", num(volts)); put("mah", num(mah)); put("full", num(fullMah))
        if (end != null) put("why", JsonObject(mapOf("title" to JsonPrimitive(end.title), "detail" to JsonPrimitive(end.detail), "crit" to JsonPrimitive(end.crit))))
    }).toString()

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private fun num(v: Double?): JsonElement = if (v == null || !v.isFinite()) JsonNull else JsonPrimitive(v)
        private fun d(o: JsonObject, k: String): Double? = (o[k] as? JsonPrimitive)?.doubleOrNull
        private fun l(o: JsonObject, k: String): Long? = (o[k] as? JsonPrimitive)?.longOrNull

        fun decode(s: String?): LastRide? = try {
            val o = s?.let { JSON.parseToJsonElement(it) as? JsonObject }
            if (o == null) null else {
                val why = o["why"] as? JsonObject
                val end = why?.let {
                    val t = (it["title"] as? JsonPrimitive)?.contentOrNull
                    if (t == null) null else RideEnd(t, (it["detail"] as? JsonPrimitive)?.contentOrNull ?: "", (it["crit"] as? JsonPrimitive)?.booleanOrNull ?: false)
                }
                val start = l(o, "start")
                val stop = l(o, "end")
                if (start == null || stop == null) null
                else LastRide(start, stop, d(o, "km"), l(o, "movingS") ?: 0, d(o, "wh") ?: 0.0, d(o, "whKm"), end, d(o, "pct"), d(o, "v"), d(o, "mah"), d(o, "full"))
            }
        } catch (e: Exception) { null }
    }
}

/** Why the ride ended, in words. */
object RideEnds {
    /**
     * The BMS's own events in the journal first («BMS отключает батарею», «Нет связи с BMS»): «BMS отключила батарею»,
     * «22:12:31 · 31,2 В под нагрузкой, 5 % · на руле ошибка 12» (the time and voltage of the BMS's flag, [pctAtCut] the
     * charge then); else [EndCause.LOST] «Связь пропала» with [reason], [EndCause.SYSTEM] the system; «Отключить» with
     * nothing to say: null (no plate). [journal]: newest first, as [ScooterRepo.journal].
     */
    fun of(cause: EndCause, reason: String?, journal: List<LampEvent>, last: Telemetry, pctAtCut: Double? = null): RideEnd? {
        val bms = journal.filter { it.id == TellTales.BATTERY && it.lamp?.text?.let { t -> LampText.bms(t) } == true }
        if (bms.isNotEmpty()) {
            val cut = bms.lastOrNull { it.lamp!!.text.startsWith(LampText.BMS_CUT) } ?: bms.last()
            val gone = bms.any { it.lamp!!.text.startsWith(LampText.NO_BMS) }
            val v = cut.lamp?.takeIf { it.text.startsWith(LampText.BMS_CUT) }?.value
            val volts = v?.let { Fmt.n(it, 1) + " В под нагрузкой" + (pctAtCut?.let { p -> ", " + Fmt.n(p) + " %" } ?: "") }
                ?: pctAtCut?.let { Fmt.n(it) + " %" }
            val detail = listOfNotNull(Fmt.clock(cut.atMs), volts, if (gone) "на руле ошибка 12" else null).joinToString(" · ")
            return RideEnd(BMS_OFF, detail, crit = true)
        }
        return when (cause) {
            EndCause.LOST -> RideEnd("Связь пропала", reason ?: "", crit = false)
            EndCause.SYSTEM -> RideEnd("Фоновую работу остановила система", "Android закрыл службу — проверь «Работа в фоне» в Настройках", crit = false)
            EndCause.OWNER -> null
        }
    }

    const val BMS_OFF = "BMS отключила батарею"
}

/** The ride so far: from «Подключить», [km] by the app odometer, [movingS] above 1 km/h, net Wh and Wh/km. */
data class TripNow(val startMs: Long, val km: Double?, val movingS: Long, val whNet: Double, val whPerKm: Double?)

object TripStats {
    /** Seconds the chart history shows moving at [minKmh] or more; gaps over [gapMs] do not count. */
    fun movingSeconds(x: Samples, minKmh: Double = 1.0, gapMs: Long = Downsample.GAP_MS): Long {
        var ms = 0L
        for (i in 0 until x.size - 1) {
            val dt = x.t[i + 1] - x.t[i]
            val s = x.s[i]
            if (dt in 1..gapMs && !s.isNaN() && s >= minKmh) ms += dt
        }
        return ms / 1000
    }

    /** km = app odometer now − at the session's first frame; Wh/km like the trip card ([Trip.whPerKm]). */
    fun now(s: Session?, t: Telemetry, x: Samples): TripNow = now(s, t, movingSeconds(x))

    /** The same with the moving time counted as the ride goes ([ScooterRepo.movingSeconds]). */
    fun now(s: Session?, t: Telemetry, movingS: Long): TripNow {
        val a = s?.appOdoStartKm
        val o = t.appOdometerKm
        val km = if (a != null && o != null) (o - a).coerceAtLeast(0.0) else null
        return TripNow(s?.startedAtMs ?: 0L, km, movingS, t.whOut - t.whIn, Trip.whPerKm(t.whOut, t.whIn, a, o))
    }
}
