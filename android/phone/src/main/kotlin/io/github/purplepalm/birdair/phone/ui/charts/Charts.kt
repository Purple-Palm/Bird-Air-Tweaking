package io.github.purplepalm.birdair.phone.ui.charts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.phone.ChartAxis
import io.github.purplepalm.birdair.phone.ChartWindow
import io.github.purplepalm.birdair.phone.ChartsText
import io.github.purplepalm.birdair.phone.Downsample
import io.github.purplepalm.birdair.phone.EnergySummary
import io.github.purplepalm.birdair.phone.Envelope
import io.github.purplepalm.birdair.phone.Fmt
import io.github.purplepalm.birdair.phone.Marker
import io.github.purplepalm.birdair.phone.MarkerLabels
import io.github.purplepalm.birdair.phone.Samples
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.Ticks
import io.github.purplepalm.birdair.phone.Units
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.IconButton48
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.SectionHeader
import io.github.purplepalm.birdair.phone.ui.Tnum
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

/** The three charts: their height, whether the time axis is under them, the room under the plot. */
private enum class Series(val heightDp: Int, val timeAxis: Boolean, val padBottomDp: Int) {
    POWER(170, true, 20), SPEED(96, false, 6), VOLT(112, true, 20)
}

/**
 * «Графики» (the prototype's tabCharts): the readout row at a fixed height, power over time (traction above zero in
 * --draw, regen below in --flow), speed and voltage under it on the same time axis, the markers as lines (labelled on
 * the power chart); touch and drag sideways for the values at a moment, on all three (the readout stays until «×» or
 * Back, a new window drops it); then the window's energy. Redrawn at most 5 times a second ([ScooterRepo.CHART_EVERY_MS]),
 * off the main thread up to the drawing.
 */
@Composable
fun ChartsBlock(units: String, fast: Boolean, window: String) {
    val rev by ScooterRepo.chartRev.collectAsStateWithLifecycle()
    val markers by ScooterRepo.markers.collectAsStateWithLifecycle()
    var widthPx by remember { mutableIntStateOf(0) }
    var hoverT by remember(window) { mutableStateOf<Long?>(null) }
    BackHandler(hoverT != null) { hoverT = null }
    val padPx = with(LocalDensity.current) { (PAD_L + PAD_R).toPx() }
    val frame by produceState<ChartFrame?>(null, rev, window, widthPx, fast, markers) {
        if (widthPx <= 0) return@produceState
        value = withContext(Dispatchers.Default) { ChartFrame.build(window, (widthPx - padPx).roundToInt(), fast, markers) }
    }
    val f = frame
    // a moment that slid out of a following window goes with it
    LaunchedEffect(f?.start) { val h = hoverT; if (f != null && h != null && h < f.start) hoverT = null }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val k = if (units == Units.MPH) (1 / Units.MILE).toFloat() else 1f
    val i = if (f != null && hoverT != null) f.x.nearest(hoverT!!) else -1
    ReadoutRow(if (f != null && i >= 0) ChartsText.readout(f.x, i, f.startedAtMs, fast, units) else null) { hoverT = null }
    val onHover: (Long?) -> Unit = { hoverT = it }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).onSizeChanged { widthPx = it.width }) {
        SeriesCanvas(f, Series.POWER, measurer, "Мощность, Вт", 1f, fast, hoverT, onHover)
        SeriesCanvas(f, Series.SPEED, measurer, "Скорость, " + Units.speedLabel(units), k, fast, hoverT, onHover, Modifier.padding(top = 4.dp))
        SeriesCanvas(f, Series.VOLT, measurer, "Напряжение, В", 1f, fast, hoverT, onHover, Modifier.padding(top = 4.dp))
    }
    Energy(f?.summary ?: EnergySummary.EMPTY, window, units)
}

/** Under the finger: «22:12:06 · 254 Вт тяга» and «8,1 А · 31,41 В · 22,5 км/ч», «×»; before a touch, how to get it. */
@Composable
private fun ReadoutRow(r: ChartsText.Readout?, onClose: () -> Unit) {
    val c = LocalBal.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(shape).background(c.well).border(1.dp, c.hair, shape).padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (r == null) {
            Text(ChartsText.HINT, color = c.ink2, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            return@Row
        }
        Column(Modifier.weight(1f)) {
            val bold = SpanStyle(color = c.ink, fontWeight = FontWeight.SemiBold)
            Text(
                buildAnnotatedString {
                    withStyle(bold) { append(r.time) }
                    append(" · ")
                    withStyle(bold) { append(r.watts) }
                    if (r.word.isNotEmpty()) append(" " + r.word)
                },
                color = c.ink2, fontSize = 13.sp, lineHeight = 17.sp, style = Tnum, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(r.line2, color = c.ink2, fontSize = 13.sp, lineHeight = 17.sp, style = Tnum, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton48(AppIcons.Close, "Убрать", onClose)
    }
}

@Composable
private fun SeriesCanvas(
    f: ChartFrame?, series: Series, measurer: TextMeasurer, label: String, k: Float, fast: Boolean, hoverT: Long?,
    onHover: (Long?) -> Unit, modifier: Modifier = Modifier,
) {
    val c = LocalBal.current
    // the gesture outlives compositions: it reads the frame and the callback of the latest one
    val cur by rememberUpdatedState(f)
    val hover by rememberUpdatedState(onHover)
    val tick = TextStyle(color = c.muted, fontSize = 10.sp, fontFeatureSettings = "tnum")
    val title = TextStyle(color = c.ink2, fontSize = 11.sp)
    val markStyle = TextStyle(color = c.ink2, fontSize = 10.sp)
    val power = series == Series.POWER
    Canvas(
        modifier
            .fillMaxWidth()
            .height(series.heightDp.dp)
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
                        hover(fr.start + ((x - left) / w * (fr.end - fr.start)).toLong().coerceIn(0, fr.end - fr.start))
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
        val plot = Rect(PAD_L.toPx(), PAD_T.toPx(), size.width - PAD_R.toPx(), size.height - series.padBottomDp.dp.toPx())
        // the measurer's cache keys on layout only, not colour: every text is drawn in its colour (a theme flip)
        drawText(measurer.measure(label, title), title.color, Offset(plot.left + 4.dp.toPx(), 0f))
        val fr = f
        if (fr == null || plot.width <= 0 || plot.height <= 0) return@Canvas
        val env = when (series) { Series.POWER -> fr.power; Series.SPEED -> fr.speed; Series.VOLT -> fr.volt }
        val mn = env.min.toDouble() * k
        val mx = env.max.toDouble() * k
        val (ticks, step) = when (series) { Series.POWER -> ChartAxis.power(mn, mx); Series.SPEED -> ChartAxis.speed(mx); Series.VOLT -> ChartAxis.volts(mn, mx) }
        val y0 = ticks.first()
        val y1 = ticks.last()
        val span = maxOf(1L, fr.end - fr.start).toFloat()
        fun X(t: Long) = plot.left + (t - fr.start) / span * plot.width
        fun Y(v: Double) = (plot.top + (y1 - v) / (y1 - y0) * plot.height).toFloat()
        // grid and y labels
        val dec = Ticks.decimals(step)
        for (tk in ticks) {
            val y = Y(tk)
            drawLine(c.hair, Offset(plot.left, y), Offset(plot.right, y), 1.dp.toPx())
            val txt = measurer.measure(Fmt.n(tk, dec), tick)
            drawText(txt, tick.color, Offset(plot.left - 6.dp.toPx() - txt.size.width, y - txt.size.height / 2f))
        }
        if (series.timeAxis) timeLabels(fr, plot, measurer, tick, ::X)
        // markers: a line on each chart, the label on the power chart (never over another label)
        val xs = FloatArray(fr.markers.size) { X(fr.markers[it].t) }
        for (x in xs) drawLine(c.ink2.copy(alpha = 0.35f), Offset(x, plot.top), Offset(x, plot.bottom), 1.dp.toPx())
        if (power && xs.isNotEmpty()) {
            val txts = fr.markers.map { measurer.measure(MarkerLabels.text(it.label), markStyle) }
            val left = MarkerLabels.place(xs, FloatArray(txts.size) { txts[it].size.width.toFloat() }, plot.right, 3.dp.toPx(), 6.dp.toPx())
            for (j in txts.indices) if (!left[j].isNaN()) drawText(txts[j], markStyle.color, Offset(left[j], plot.top + 2.dp.toPx()))
        }
        if (env.empty) {
            if (power) {
                val txt = measurer.measure(if (ScooterRepo.session == null) "Подключи самокат — здесь пойдёт мощность" else "Нет данных в этом окне", TextStyle(color = c.muted, fontSize = 13.sp))
                drawText(txt, c.muted, Offset(plot.center.x - txt.size.width / 2f, plot.center.y - txt.size.height / 2f))
            }
            return@Canvas
        }
        val base = if (power) Y(0.0) else plot.bottom
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
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
                fun paint(top: Float, bottom: Float, col: Color, fill: Float) = clipRect(plot.left, top, plot.right, bottom) {
                    drawPath(area, col.copy(alpha = fill))
                    drawPath(line, col, style = stroke)
                }
                if (power) { paint(0f, base, c.draw, 0.14f); paint(base, size.height, c.flow, 0.14f) } else paint(0f, size.height, c.ink2, 0.12f)
            }
        }
        if (power) drawLine(c.base, Offset(plot.left, base), Offset(plot.right, base), 1.dp.toPx())
        // crosshair, the same moment on all three
        val ht = hoverT
        if (ht != null && ht in fr.start..fr.end) {
            val i = fr.x.nearest(ht)
            if (i >= 0) {
                val x = X(fr.x.t[i])
                drawLine(c.ink, Offset(x, plot.top), Offset(x, plot.bottom), 1.dp.toPx())
                val yv = when (series) { Series.POWER -> fr.x.p(fast)[i]; Series.SPEED -> fr.x.s[i]; Series.VOLT -> fr.x.v[i] }
                if (!yv.isNaN()) {
                    val y = Y(yv.toDouble() * k)
                    drawCircle(c.surface, 5.5.dp.toPx(), Offset(x, y))
                    drawCircle(if (power) (if (yv < 0) c.flow else c.draw) else c.ink, 3.5.dp.toPx(), Offset(x, y))
                }
            }
        }
    }
}

/** «−5 мин … −1 мин сейчас» while the window follows the ride, clock times otherwise (the prototype's drawChart). */
private fun DrawScope.timeLabels(fr: ChartFrame, plot: Rect, measurer: TextMeasurer, style: TextStyle, X: (Long) -> Float) {
    val spanS = (fr.end - fr.start) / 1000.0
    val y = plot.bottom + 4.dp.toPx()
    if (fr.rel) {
        val st = ChartWindow.relStepS(spanS)
        val gap = 3.dp.toPx()
        val minX = plot.left + 12.dp.toPx()
        var leftmost = Float.MAX_VALUE   // labels go right to left; one that would touch its right neighbour is skipped
        var s = 0
        while (s <= spanS + 0.001) {
            val lab = if (s == 0) "сейчас" else if (spanS <= 90) "−$s с" else "−${s / 60} мин"
            val txt = measurer.measure(lab, style)
            val lx = if (s == 0) plot.right - txt.size.width else maxOf(X(fr.end - s * 1000L), minX) - txt.size.width / 2f
            if (lx + txt.size.width <= leftmost - gap) {
                drawText(txt, style.color, Offset(lx, y))
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
                drawText(txt, style.color, Offset(x - txt.size.width / 2f, y))
            }
            s += st
        }
    }
}

/** «ЭНЕРГИЯ ЗА ОКНО · 5 МИН»: Потрачено / Возвращено / Расход за окно / Средняя мощность, what the window holds. */
@Composable
private fun Energy(e: EnergySummary, window: String, units: String) {
    val c = LocalBal.current
    val d = Units.distLabel(units)
    SectionHeader(ChartsText.energyTitle(window))
    Row(Modifier.fillMaxWidth()) {
        Met("Потрачено", Fmt.n(e.outWh, 1), "Вт·ч", Modifier.weight(1f))
        Spacer(Modifier.width(14.dp))
        Met("Возвращено", Fmt.n(e.inWh, 1), "Вт·ч", Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Met("Расход за окно", Fmt.n(ChartsText.perDist(e.whPerKm, units), 1), "Вт·ч/$d", Modifier.weight(1f))
        Spacer(Modifier.width(14.dp))
        Met("Средняя мощность", Fmt.n(e.avgW), "Вт", Modifier.weight(1f))
    }
    Text(ChartsText.energyNote(e, units), color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp, style = Tnum, modifier = Modifier.padding(top = 10.dp))
}

/** A figure of the page's .met grid: the label (12 sp, muted), the value (22 sp) and its unit on one baseline. */
@Composable
internal fun Met(label: String, value: String, unit: String, modifier: Modifier = Modifier, color: Color? = null) {
    val c = LocalBal.current
    Column(modifier) {
        Text(label, color = c.muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 1.dp))
        Row {
            Text(value, color = color ?: c.ink, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, style = Tnum, maxLines = 1,
                modifier = Modifier.alignByBaseline())
            if (unit.isNotEmpty()) Text(unit, color = c.ink2, fontSize = 13.sp, maxLines = 1, modifier = Modifier.alignByBaseline().padding(start = 3.dp))
        }
    }
}
