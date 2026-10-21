package io.github.purplepalm.birdair.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.phone.ChartWindow
import io.github.purplepalm.birdair.phone.Downsample
import io.github.purplepalm.birdair.phone.EnergySummary
import io.github.purplepalm.birdair.phone.Envelope
import io.github.purplepalm.birdair.phone.Fmt
import io.github.purplepalm.birdair.phone.Marker
import io.github.purplepalm.birdair.phone.Samples
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.Ticks
import io.github.purplepalm.birdair.phone.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One drawing of the charts: the window, the three series downsampled to the plot width, the samples for the readout. */
private class ChartFrame(
    val start: Long, val end: Long, val rel: Boolean, val startedAtMs: Long,
    val power: Envelope, val speed: Envelope, val volt: Envelope,
    val x: Samples, val summary: EnergySummary, val markers: List<Marker>,
) {
    val empty: Boolean get() = power.empty && speed.empty && volt.empty

    companion object {
        fun build(window: String, columns: Int, fast: Boolean, markers: List<Marker>): ChartFrame {
            val s = ScooterRepo.session
            val ring = ScooterRepo.samples
            val running = s != null && ScooterRepo.status.value.running
            val r = ChartWindow.range(window, if (running) s!!.now() else null, ring.firstT, ring.lastT)
            val x = ring.copy(r[0])
            val cols = maxOf(1, columns)
            return ChartFrame(
                r[0], r[1], running && window != ChartWindow.ALL, s?.startedAtMs ?: 0L,
                Downsample.minMax(x.t, x.p(fast), r[0], r[1], cols),
                Downsample.minMax(x.t, x.s, r[0], r[1], cols),
                Downsample.minMax(x.t, x.v, r[0], r[1], cols),
                x, EnergySummary.of(x, fast, r[0], r[1]), markers.filter { it.t in r[0]..r[1] },
            )
        }
    }
}

private val PAD_L = 46.dp
private val PAD_R = 8.dp
private val PAD_T = 14.dp
private val PAD_B = 20.dp

/**
 * «Графики», like the page's: power over time (traction above zero in --draw, regen below in --flow, zero line),
 * speed and voltage under it on the same time axis; «1 мин / 5 мин / 15 мин / вся поездка»; touch and drag for the
 * values at a moment (the readout stays until «×»); the window's energy. Redrawn at most 5 times a second
 * ([ScooterRepo.CHART_EVERY_MS]), off the main thread up to the drawing.
 */
@Composable
fun ChartsCard(units: String, fast: Boolean, window: String) {
    val c = LocalBal.current
    val rev by ScooterRepo.chartRev.collectAsStateWithLifecycle()
    val markers by ScooterRepo.markers.collectAsStateWithLifecycle()
    var widthPx by remember { mutableIntStateOf(0) }
    var hoverT by remember { mutableStateOf<Long?>(null) }
    val padPx = with(androidx.compose.ui.platform.LocalDensity.current) { (PAD_L + PAD_R).toPx() }
    val frame by produceState<ChartFrame?>(null, rev, window, widthPx, fast, markers) {
        if (widthPx <= 0) return@produceState
        value = withContext(Dispatchers.Default) { ChartFrame.build(window, (widthPx - padPx).roundToInt(), fast, markers) }
    }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val k = if (units == Units.MPH) (1 / Units.MILE).toFloat() else 1f
    Panel("Графики") {
        WindowTabs(window) { w -> hoverT = null; ScooterRepo.updateUi { it.copy(chartWindow = w) } }
        val f = frame
        Readout(f, hoverT, units, fast) { hoverT = null }
        val onHover: (Long?) -> Unit = { hoverT = it }
        Box(Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
            SeriesCanvas(f, 170, measurer, signed = true, color = c.draw, colorNeg = c.flow, minSpan = 100.0, timeAxis = true,
                pick = { it.power }, hoverT = hoverT, onHover = onHover, label = "Мощность, Вт", fast = fast, which = 0)
        }
        SeriesCanvas(f, 96, measurer, signed = false, color = c.ink2, colorNeg = null, minSpan = 5.0, timeAxis = false, zeroBase = true, k = k,
            pick = { it.speed }, hoverT = hoverT, onHover = onHover, label = "Скорость, " + Units.speedLabel(units), fast = fast, which = 1)
        SeriesCanvas(f, 112, measurer, signed = false, color = c.ink2, colorNeg = null, minSpan = 1.0, timeAxis = true,
            pick = { it.volt }, hoverT = hoverT, onHover = onHover, label = "Напряжение, В", fast = fast, which = 2)
        Summary(f, window, units)
    }
}

/** «1 мин / 5 мин / 15 мин / вся поездка»: one row, the last one wider so its label fits on a phone. */
@Composable
private fun WindowTabs(selected: String, onPick: (String) -> Unit) {
    val c = LocalBal.current
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().height(40.dp).border(1.dp, c.base, shape).clip(shape)) {
        ChartWindow.OPTIONS.forEachIndexed { i, (key, label) ->
            val on = key == selected
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.base))
            Box(
                Modifier
                    .weight(if (key == ChartWindow.ALL) 1.7f else 1f)
                    .fillMaxHeight()
                    .background(if (on) c.flow.copy(alpha = 0.18f) else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab) { onPick(key) },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = c.ink, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1) }
        }
    }
}

@Composable
private fun Readout(f: ChartFrame?, hoverT: Long?, units: String, fast: Boolean, onClose: () -> Unit) {
    val c = LocalBal.current
    if (f == null || hoverT == null) {
        Text(if (f == null || f.empty) "" else "Коснись графика и веди пальцем — покажу значения в тот момент.", color = c.muted, fontSize = 12.sp)
        return
    }
    val i = f.x.nearest(hoverT)
    if (i < 0) return
    val p = f.x.p(fast)[i]
    val cur = f.x.i(fast)[i]
    val v = f.x.v[i]
    val s = f.x.s[i]
    fun num(x: Float, d: Int) = if (x.isNaN()) "—" else Fmt.n(x.toDouble(), d)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(if (!p.isNaN() && p < 0) c.flow else c.draw, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (p.isNaN()) "—" else Fmt.n(abs(p).toDouble()) + " Вт " + if (p < 0) "в батарею" else "из батареи") + " · " + Fmt.clock(f.startedAtMs + f.x.t[i]),
                color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = Tnum,
            )
            Text(
                "ток " + num(cur, 1) + " А · " + num(v, 1) + " В · " + (if (s.isNaN()) "—" else Fmt.n(Units.speed(s.toDouble(), units), 1)) + " " + Units.speedLabel(units),
                color = c.ink2, fontSize = 13.sp, style = Tnum,
            )
        }
        TextButton(onClick = onClose) { Text("×", fontSize = 18.sp) }
    }
}

@Composable
private fun SeriesCanvas(
    f: ChartFrame?, heightDp: Int, measurer: TextMeasurer, signed: Boolean, color: Color, colorNeg: Color?, minSpan: Double,
    timeAxis: Boolean, pick: (ChartFrame) -> Envelope, hoverT: Long?, onHover: (Long?) -> Unit, label: String, fast: Boolean,
    which: Int, zeroBase: Boolean = false, k: Float = 1f,
) {
    val c = LocalBal.current
    val cur by rememberUpdatedState(f)
    val tick = TextStyle(color = c.muted, fontSize = 10.sp, fontFeatureSettings = "tnum")
    val title = TextStyle(color = c.ink2, fontSize = 11.sp)
    val markStyle = TextStyle(color = c.ink2, fontSize = 10.sp)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .semantics { contentDescription = "График: $label" }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun at(x: Float) {
                        val fr = cur ?: return
                        val left = PAD_L.toPx()
                        val w = size.width - left - PAD_R.toPx()
                        if (w <= 0) return
                        onHover(fr.start + ((x - left) / w * (fr.end - fr.start)).toLong().coerceIn(0, fr.end - fr.start))
                    }
                    at(down.position.x)
                    var horizontal = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val d = ch.position - down.position
                        if (!horizontal && abs(d.x) > slop && abs(d.x) > abs(d.y)) horizontal = true
                        if (horizontal) { ch.consume(); at(ch.position.x) } else if (abs(d.y) > slop) break   // the page scrolls
                    }
                }
            },
    ) {
        val plot = Rect(PAD_L.toPx(), PAD_T.toPx(), size.width - PAD_R.toPx(), size.height - (if (timeAxis) PAD_B else 4.dp).toPx())
        drawText(measurer, label, Offset(plot.left, 0f), title)
        val fr = f
        if (fr == null || plot.width <= 0 || plot.height <= 0) return@Canvas
        val env = pick(fr)
        // y domain
        var lo = if (env.min.isNaN()) 0.0 else env.min.toDouble() * k
        var hi = if (env.max.isNaN()) 1.0 else env.max.toDouble() * k
        if (signed) { lo = minOf(0.0, lo); hi = maxOf(0.0, hi); if (hi - lo < minSpan) hi = lo + minSpan }
        else if (zeroBase) { lo = 0.0; if (hi < minSpan) hi = minSpan }
        else if (hi - lo < minSpan) { val m = (hi + lo) / 2; lo = m - minSpan / 2; hi = m + minSpan / 2 }
        val (ticks, step) = Ticks.ticks(lo, hi, if (heightDp >= 150) 4 else 3)
        val y0 = ticks.first()
        val y1 = ticks.last()
        val span = maxOf(1L, fr.end - fr.start).toFloat()
        fun X(t: Long) = plot.left + (t - fr.start) / span * plot.width
        fun Y(v: Double) = (plot.top + (y1 - v) / (y1 - y0) * plot.height).toFloat()
        // grid and y labels
        val dec = Ticks.decimals(step)
        for (tk in ticks) {
            val y = Y(tk)
            drawLine(if (signed && tk == 0.0) c.base else c.hair, Offset(plot.left, y), Offset(plot.right, y), 1.dp.toPx())
            val txt = measurer.measure(Fmt.n(tk, dec), tick)
            drawText(txt, topLeft = Offset(plot.left - 6.dp.toPx() - txt.size.width, y - txt.size.height / 2f))
        }
        if (timeAxis) timeLabels(fr, plot, measurer, tick, ::X)
        // markers
        for (m in fr.markers) {
            val x = X(m.t)
            drawLine(c.ink2.copy(alpha = 0.35f), Offset(x, plot.top), Offset(x, plot.bottom), 1.dp.toPx())
            if (which == 0) {
                val txt = measurer.measure(m.label.take(18), markStyle)
                val lx = if (x + 3.dp.toPx() + txt.size.width > plot.right) x - 3.dp.toPx() - txt.size.width else x + 3.dp.toPx()
                drawText(txt, topLeft = Offset(lx, plot.top))
            }
        }
        if (env.empty) {
            if (which == 0) {
                val txt = measurer.measure(if (ScooterRepo.session == null) "Подключи самокат — здесь пойдёт мощность" else "Нет данных в этом окне", TextStyle(color = c.muted, fontSize = 13.sp))
                drawText(txt, topLeft = Offset(plot.center.x - txt.size.width / 2f, plot.center.y - txt.size.height / 2f))
            }
            return@Canvas
        }
        val base = if (signed) Y(0.0) else plot.bottom
        clipRect(plot.left, 0f, plot.right, size.height) {
            for (run in env.runs) {
                val line = Path()
                for (j in 0 until run.size) {
                    val x = X(run.t[j])
                    val y = Y(run.y[j].toDouble() * k)
                    if (j == 0) line.moveTo(x, y) else line.lineTo(x, y)
                }
                if (run.size == 1) line.lineTo(X(run.t[0]) + 1f, Y(run.y[0].toDouble() * k))
                val area = Path().apply {
                    addPath(line)
                    lineTo(X(run.t[run.size - 1]), base)
                    lineTo(X(run.t[0]), base)
                    close()
                }
                fun paint(top: Float, bottom: Float, col: Color) = clipRect(plot.left, top, plot.right, bottom) {
                    drawPath(area, col.copy(alpha = 0.12f))
                    drawPath(line, col, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                if (signed && colorNeg != null) { paint(0f, base, color); paint(base, size.height, colorNeg) } else paint(0f, size.height, color)
            }
        }
        // crosshair
        val ht = hoverT
        if (ht != null) {
            val i = fr.x.nearest(ht)
            if (i >= 0) {
                val tx = fr.x.t[i]
                val x = X(tx)
                drawLine(c.ink2.copy(alpha = 0.7f), Offset(x, plot.top), Offset(x, plot.bottom), 1.dp.toPx())
                val yv = when (which) { 0 -> fr.x.p(fast)[i]; 1 -> fr.x.s[i]; else -> fr.x.v[i] }
                if (!yv.isNaN()) {
                    val y = Y(yv.toDouble() * k)
                    drawCircle(c.surface, 6.dp.toPx(), Offset(x, y))
                    drawCircle(if (signed && colorNeg != null && yv < 0) colorNeg else color, 4.dp.toPx(), Offset(x, y))
                }
            }
        }
    }
}

/** «−1 мин … сейчас» while the window follows the ride, clock times otherwise (the page's drawSeries). */
private fun DrawScope.timeLabels(fr: ChartFrame, plot: Rect, measurer: TextMeasurer, style: TextStyle, X: (Long) -> Float) {
    val spanS = (fr.end - fr.start) / 1000.0
    val y = plot.bottom + 4.dp.toPx()
    if (fr.rel) {
        val st = ChartWindow.relStepS(spanS)
        val gap = 6.dp.toPx()
        var leftmost = Float.MAX_VALUE   // labels go right to left; one that would touch its right neighbour is skipped
        var s = 0
        while (s <= spanS + 0.001) {
            val x = X(fr.end - s * 1000L)
            val lab = if (s == 0) "сейчас" else if (spanS <= 90) "−$s с" else "−${s / 60} мин"
            val txt = measurer.measure(lab, style)
            val lx = if (s == 0) minOf(x, plot.right) - txt.size.width else x - txt.size.width / 2f
            if (lx >= plot.left - 4.dp.toPx() && lx + txt.size.width <= leftmost - gap) {
                drawText(txt, topLeft = Offset(lx, y))
                leftmost = lx
            }
            s += st
        }
    } else {
        val st = ChartWindow.clockStepS(spanS)
        val startS = (fr.startedAtMs + fr.start) / 1000.0
        val endS = (fr.startedAtMs + fr.end) / 1000.0
        var s = ceil(startS / st) * st
        while (s <= endS) {
            val wall = (s * 1000).toLong()
            val x = X(wall - fr.startedAtMs)
            if (x >= plot.left + 14.dp.toPx() && x <= plot.right - 14.dp.toPx()) {
                val txt = measurer.measure(Fmt.clock(wall, seconds = st < 60), style)
                drawText(txt, topLeft = Offset(x - txt.size.width / 2f, y))
            }
            s += st
        }
    }
}

/** The window's energy: «Потрачено / Возвращено / Расход / Средняя». */
@Composable
private fun Summary(f: ChartFrame?, window: String, units: String) {
    val c = LocalBal.current
    val e = f?.summary ?: EnergySummary.EMPTY
    val d = Units.distLabel(units)
    val perKm = e.whPerKm?.let { if (units == Units.MPH) it * Units.MILE else it }
    Text(if (window == ChartWindow.ALL) "Энергия за поездку" else "Энергия за окно", color = c.ink2, fontSize = 14.sp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Metric("Потрачено", Fmt.n(e.outWh, 1), "Вт·ч", Modifier.weight(1f))
            Metric("Возвращено", Fmt.n(e.inWh, 1), "Вт·ч", Modifier.weight(1f))
        }
        Row {
            Metric("Расход", Fmt.n(perKm, 1), "Вт·ч/$d", Modifier.weight(1f))
            Metric("Средняя мощность", Fmt.n(e.avgW), "Вт", Modifier.weight(1f))
        }
    }
    if (e.seconds > 0) Msg("Данные за " + duration(e.seconds) + (e.km?.let { " · " + Fmt.n(Units.dist(it, units), 2) + " " + d } ?: "") + " · по графику мощности")
}

private fun duration(s: Double): String {
    val m = (s / 60).toInt()
    return if (m >= 60) "${m / 60} ч ${m % 60} мин" else if (m >= 1) "$m мин ${(s % 60).toInt()} с" else "${s.toInt()} с"
}
