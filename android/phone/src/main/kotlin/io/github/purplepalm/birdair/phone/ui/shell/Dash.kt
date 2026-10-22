package io.github.purplepalm.birdair.phone.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.phone.Band
import io.github.purplepalm.birdair.phone.ChartWindow
import io.github.purplepalm.birdair.phone.DashText
import io.github.purplepalm.birdair.phone.DashView
import io.github.purplepalm.birdair.phone.Downsample
import io.github.purplepalm.birdair.phone.Envelope
import io.github.purplepalm.birdair.phone.Flow
import io.github.purplepalm.birdair.phone.Fmt
import io.github.purplepalm.birdair.phone.LastRide
import io.github.purplepalm.birdair.phone.Mode
import io.github.purplepalm.birdair.phone.Power
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.SetupStep
import io.github.purplepalm.birdair.phone.TripStats
import io.github.purplepalm.birdair.phone.Units
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.Big
import io.github.purplepalm.birdair.phone.ui.LampGlyph
import io.github.purplepalm.birdair.phone.ui.LevelBar
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.Plate
import io.github.purplepalm.birdair.phone.ui.PlateTone
import io.github.purplepalm.birdair.phone.ui.PowerBar
import io.github.purplepalm.birdair.phone.ui.Tnum
import io.github.purplepalm.birdair.phone.ui.WellCard
import io.github.purplepalm.birdair.phone.ui.blinkAlpha
import io.github.purplepalm.birdair.phone.ui.lampColor
import io.github.purplepalm.birdair.phone.ui.lampDescription
import io.github.purplepalm.birdair.phone.ui.mix
import io.github.purplepalm.birdair.phone.ui.rememberBlink
import io.github.purplepalm.birdair.phone.ui.rememberBulbCheck
import io.github.purplepalm.birdair.phone.ui.rememberSpring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor

/** The dash: the view of the moment, under the top bar, above the tiles and actions. */
@Composable
fun DashHost(view: DashView, setup: SetupStep, modifier: Modifier = Modifier) {
    when (view) {
        DashView.RIDE -> DashRide(silent = false, modifier)
        DashView.SILENT -> DashRide(silent = true, modifier)
        DashView.PARKED -> DashParked(modifier)
        DashView.IDLE -> DashIdle(setup, modifier)
    }
}

// -- riding (and silent: the same places with dashes)

/** Compact steps when the stack does not fit (3-button navigation, a big font): speed max, power bar, lamp cells. */
private data class RideSizes(val speedMax: Float, val bar: Dp, val cell: Dp)

private val RIDE_SIZES = listOf(RideSizes(128f, 56.dp, 50.dp), RideSizes(96f, 56.dp, 50.dp), RideSizes(96f, 44.dp, 50.dp), RideSizes(96f, 44.dp, 48.dp))

/**
 * RIDE: the warning band (fixed height), the speed sized to the width, the mode, the power with its bar, the battery and
 * the range, the 12 lamps 2×6 — no scrolling at 360×800. SILENT: the same with dashes and «жду данные от BMS».
 */
@Composable
fun DashRide(silent: Boolean, modifier: Modifier = Modifier) {
    val t by ScooterRepo.telemetry.collectAsStateWithLifecycle()
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val s by ScooterRepo.settings.collectAsStateWithLifecycle()
    val bulb = rememberBulbCheck()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val avail = maxHeight - 12.dp
        val width = maxWidth - 32.dp
        val measurer = rememberTextMeasurer()
        val w100 = remember(measurer, density) { measurer.measure("88,8", speedStyle(100.sp)).size.width.toFloat() }
        val k = density.fontScale
        // the speed from the width (TextMeasurer on «88,8»), then the rest of the stack in dp
        fun fitSp(max: Float): Float {
            val px = with(density) { (width - 92.dp - 8.dp).toPx() }
            return floor(px * 100f / w100).coerceIn(72f, max)
        }
        fun need(z: RideSizes): Float = 60f + fitSp(z.speedMax) * k * 0.92f + 2f + (56f * k + 6f + z.bar.value + 5f + 17f * k) + 46f * k + (2 * z.cell.value + 6f) + 4 * 8f
        val z = RIDE_SIZES.firstOrNull { need(it) <= avail.value } ?: RIDE_SIZES.last()
        val fits = need(z) <= avail.value
        val scroll = rememberScrollState()
        Column(
            Modifier.fillMaxWidth()
                .then(if (fits) Modifier.height(maxHeight) else Modifier.verticalScroll(scroll).heightIn(min = maxHeight))
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalArrangement = if (fits) Arrangement.SpaceBetween else Arrangement.spacedBy(8.dp),
        ) {
            val band = if (silent && !bulb) Band(TellTales.LINK, LampLevel.AMBER, if (st.link == io.github.purplepalm.birdair.core.Link.RECONNECTING) "Переподключаюсь…" else TellTales.T_SILENT,
                if (st.link == io.github.purplepalm.birdair.core.Link.RECONNECTING) "связь с самокатом прервалась — пробую снова" else DashText.SILENT_HINT, false)
            else DashText.band(t.lamps, s.units, bulb, st.running, st.link, t.streaming)
            BandView(band, height = 60.dp)
            SpeedRow(t, s.units, silent, fitSp(z.speedMax))
            PowerBlock(t, s.fastPower, s.smooth, silent, z.bar)
            BatteryRow(t, s.units, silent)
            DashCluster(if (bulb) TellTales.bulbCheck() else t.lamps, s.units, steady = bulb, cell = z.cell)
        }
    }
}

private fun speedStyle(size: TextUnit) = Big.copy(fontSize = size, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.023).em)

/** The speed, as large as the width lets it be (at most 128 sp): [sizeSp] from [DashRide]'s measure of «88,8». */
@Composable
fun SpeedNumber(text: String, muted: Boolean, sizeSp: Float, modifier: Modifier = Modifier) {
    val c = LocalBal.current
    Text(
        text, color = if (muted) c.muted else c.ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        style = speedStyle(sizeSp.sp).copy(lineHeight = (sizeSp * 0.92f).sp, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)),
        modifier = modifier,
    )
}

@Composable
private fun SpeedRow(t: Telemetry, units: String, silent: Boolean, sizeSp: Float) {
    val c = LocalBal.current
    val nav = LocalNav.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
        val v = if (silent) null else Units.speed(t.speedKmh, units)
        SpeedNumber(Fmt.n(v, 1), muted = silent || !t.streaming, sizeSp = sizeSp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(92.dp).fillMaxHeight().padding(top = 2.dp, bottom = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween) {
            ModeChip(Mode.of(t.mode), units, onClick = { nav.sheet(SheetKind.CONTROL) })
            Text(Units.speedLabel(units), color = c.ink2, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** The mode: its name and limit in a frame of its colour; a tap opens «Управление». [small]: the parked card's. */
@Composable
fun ModeChip(m: Mode?, units: String, onClick: () -> Unit, small: Boolean = false) {
    val c = LocalBal.current
    val color = when (m) { Mode.TAKEOFF -> c.modeTake; Mode.GLIDE -> c.modeGlide; Mode.FLY -> c.modeFly; null -> c.base }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.then(if (small) Modifier.widthIn(min = 76.dp).height(48.dp) else Modifier.width(92.dp).height(58.dp))
            .clip(shape).border(2.dp, color, shape)
            .clickable(role = Role.Button, onClickLabel = "управление", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Режим " + (m?.title ?: "неизвестен") + (m?.let { ", " + it.limit(units) } ?: "") + " — управление" }
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(m?.title ?: "—", color = c.ink, fontSize = if (small) 16.sp else 20.sp, lineHeight = if (small) 18.sp else 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1)
        if (m != null) Text(m.limit(units), color = c.ink2, fontSize = if (small) 11.sp else 12.sp, lineHeight = if (small) 14.sp else 16.sp, style = Tnum, maxLines = 1)
    }
}

/** Power: watts by the flow's colour, «Тяга» and the scale, THE bar, «← рекуперация · 7,8 А · 31,41 В · тяга →». */
@Composable
fun PowerBlock(t: Telemetry, fast: Boolean, smooth: Boolean, silent: Boolean, bar: Dp = 56.dp) {
    val c = LocalBal.current
    val p = if (silent) null else Power.watts(t, fast)
    val live = !silent && t.streaming && p != null
    val flow = Flow.of(t, fast)
    val color = flowColor(p, live, flow)
    val frac = rememberSpring(if (live) (p!! / t.peakW).toFloat().coerceIn(-1f, 1f) else 0f, smooth)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text(if (p == null) "—" else Fmt.n(abs(p)), color = if (p == null) c.muted else color, fontSize = 56.sp, lineHeight = 56.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-1).sp, style = Big, maxLines = 1)
                Text(" Вт", color = c.ink2, fontSize = 20.sp, modifier = Modifier.padding(bottom = 6.dp), maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (silent) Flow.NONE.label else flow.label, color = if (live) color else c.muted, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (silent) "жду кадры" else "шкала " + Fmt.scale(t.peakW), color = c.muted, fontSize = 12.sp, style = Tnum, maxLines = 1)
            }
        }
        Spacer(Modifier.height(6.dp))
        PowerBar(frac, live, height = bar)
        Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("← рекуперация", color = c.flow, fontSize = 12.sp, maxLines = 1)
            Text(if (silent) "—" else DashText.ampsVolts(t, fast), color = c.ink2, fontSize = 13.sp, style = Tnum, maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            Text("тяга →", color = c.draw, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** The page's power colours: regen / charging blue, traction orange, coasting ink, nothing live muted. */
@Composable
fun flowColor(p: Double?, live: Boolean, flow: Flow): Color {
    val c = LocalBal.current
    return when {
        p == null || !live || flow == Flow.IDLE -> c.muted
        flow.intoBattery -> c.flow
        flow == Flow.DRIVE && abs(p) >= 5 -> c.draw
        else -> c.ink
    }
}

/** «6,6 %» big, the level bar, «≈ 0,2 км до отключения» (red under 1 km). */
@Composable
fun BatteryRow(t: Telemetry, units: String, silent: Boolean) {
    val c = LocalBal.current
    val pct = if (silent) null else t.batteryPctFine ?: t.batteryPct
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Fmt.n(pct, if (t.batteryPctFine != null) 1 else 0), color = if (silent) c.muted else c.ink, fontSize = 44.sp, lineHeight = 46.sp,
                fontWeight = FontWeight.SemiBold, style = Big, maxLines = 1)
            Text(" %", color = c.ink2, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            LevelBar(pct)
            val r = t.range
            val text = if (silent) "жду данные от BMS" else DashText.range(r, units) ?: ""
            Text(text, color = if (!silent && r != null && r.km < 1.0) c.critInk else c.ink, fontSize = 16.sp, lineHeight = 20.sp, style = Tnum, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** The fixed-height warning band; a tap opens the lamp's sheet («Предупреждений нет»: the Приборка page). */
@Composable
fun BandView(b: Band, height: Dp) {
    val c = LocalBal.current
    val nav = LocalNav.current
    val quiet = b.level == null
    val warnCol = b.level?.let { if (b.crit) c.crit else c.warn } ?: c.muted
    val shape = RoundedCornerShape(12.dp)
    val bg = when { quiet -> c.well; b.crit -> mix(c.bg, c.crit, 0.22f); else -> mix(c.bg, c.warn, 0.16f) }
    val border = when { quiet -> BorderStroke(1.dp, c.hair); b.crit -> BorderStroke(2.dp, c.crit); else -> BorderStroke(1.dp, c.warn) }
    val bulb = b.title == "Проверка ламп…"
    val blink = rememberBlink()
    Row(
        Modifier.fillMaxWidth().height(height).clip(shape).background(bg).border(border, shape)
            .then(if (bulb) Modifier else Modifier.clickable(role = Role.Button) { val id = b.id; if (id != null) nav.lamp(id) else nav.open(Page.LAMPS) })
            .semantics(mergeDescendants = true) { contentDescription = b.title + (b.hint?.let { " — $it" } ?: "") }
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).graphicsLayer { alpha = blinkAlpha(blink.value, b.crit) }.background(if (quiet && !bulb && b.title == "Предупреждений нет") c.good else warnCol, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, color = if (quiet) c.muted else c.ink, fontSize = if (quiet) 15.sp else 16.sp, lineHeight = 21.sp,
                fontWeight = if (quiet) FontWeight.Medium else FontWeight.SemiBold, style = Tnum, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (b.hint != null) Text(b.hint, color = c.ink2, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp))
        }
        if (!bulb) Icon(AppIcons.Chev, contentDescription = null, tint = c.muted, modifier = Modifier.size(24.dp))
    }
}

/** The 12 lamps, 2 rows of 6 in [TellTales.IDS] order; each cell is its own target and opens the lamp's sheet. */
@Composable
fun DashCluster(lamps: List<Lamp>, units: String, steady: Boolean, cell: Dp = 50.dp) {
    val c = LocalBal.current
    val nav = LocalNav.current
    val by = lamps.associateBy { it.id }
    val blink = rememberBlink()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in TellTales.IDS.chunked(6)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (id in row) {
                val l = by[id]?.takeIf { it.level != LampLevel.OFF }
                val col = lampColor(l?.level ?: LampLevel.OFF, c.dark)
                val shape = RoundedCornerShape(10.dp)
                Box(
                    Modifier.weight(1f).height(cell).clip(shape)
                        .background(if (l != null) mix(c.well, col, 0.10f) else c.well)
                        .border(1.dp, if (l != null) col.copy(alpha = 0.55f) else c.hair, shape)
                        .clickable(role = Role.Button) { nav.lamp(id) }
                        .semantics(mergeDescendants = true) { contentDescription = lampDescription(id, l, units) },
                    contentAlignment = Alignment.Center,
                ) { LampGlyph(id, l, blink, steady) }
            }
        }
    }
}

// -- parked

/**
 * PARKED: a compact speed / mode / watts card with a short bar, the battery in detail, the lamps, the trip so far with
 * its power sparkline (a tap opens Графики). Scrolls when it does not fit.
 */
@Composable
fun DashParked(modifier: Modifier = Modifier) {
    val c = LocalBal.current
    val nav = LocalNav.current
    val t by ScooterRepo.telemetry.collectAsStateWithLifecycle()
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val s by ScooterRepo.settings.collectAsStateWithLifecycle()
    val rev by ScooterRepo.chartRev.collectAsStateWithLifecycle()
    val bulb = rememberBulbCheck()
    val trip = remember(rev) { TripStats.now(ScooterRepo.session, ScooterRepo.telemetry.value, ScooterRepo.movingSeconds()) }
    FillColumn(modifier) {
        BandView(DashText.band(t.lamps, s.units, bulb, st.running, st.link, t.streaming), height = 54.dp)
        WellCard(onDash = true, padding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)) {
            val p = Power.watts(t, s.fastPower)
            val live = t.streaming && p != null
            val flow = Flow.of(t, s.fastPower)
            val color = flowColor(p, live, flow)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.n(Units.speed(t.speedKmh, s.units), 1), color = if (t.streaming) c.ink else c.muted, fontSize = 42.sp, lineHeight = 44.sp,
                        fontWeight = FontWeight.SemiBold, style = Big, maxLines = 1)
                    Text(" " + Units.speedLabel(s.units), color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 4.dp), maxLines = 1)
                }
                Spacer(Modifier.width(10.dp))
                ModeChip(Mode.of(t.mode), s.units, onClick = { nav.sheet(SheetKind.CONTROL) }, small = true)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.widthIn(min = 78.dp), horizontalAlignment = Alignment.End) {
                    Text(flow.label, color = if (live) color else c.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (p == null) "—" else Fmt.n(abs(p)), color = if (live) color else c.muted, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, style = Tnum, maxLines = 1)
                        Text(" Вт", color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
            }
            val frac = rememberSpring(if (live) (p!! / t.peakW).toFloat().coerceIn(-1f, 1f) else 0f, s.smooth)
            Spacer(Modifier.height(6.dp))
            PowerBar(frac, live, height = 28.dp)
        }
        WellCard(onDash = true, padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
            val pct = t.batteryPctFine ?: t.batteryPct
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.n(pct, if (t.batteryPctFine != null) 1 else 0), color = c.ink, fontSize = 38.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, style = Big)
                    Text(" %", color = c.ink2, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 3.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    LevelBar(pct)
                    val r = t.range
                    Text(DashText.range(r, s.units) ?: "", color = if (r != null && r.km < 1.0) c.critInk else c.ink, fontSize = 15.sp, style = Tnum, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                }
            }
            Text(
                listOfNotNull(Fmt.n(t.voltage, 2) + " В", t.remainingMah?.let { Fmt.n(it) + " / " + Fmt.n(t.fullMah) + " мА·ч" },
                    if (t.temp1C != null || t.temp2C != null) Fmt.n(t.temp1C) + " / " + Fmt.n(t.temp2C) + " °C" else null).joinToString(" · "),
                color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp, style = Tnum, modifier = Modifier.padding(top = 4.dp),
            )
        }
        DashCluster(if (bulb) TellTales.bulbCheck() else t.lamps, s.units, steady = bulb, cell = 48.dp)
        TripCard(
            title = DashText.tripTitle(trip.startMs, null, trip.whNet), km = trip.km, movingS = trip.movingS, whPerKm = trip.whPerKm, units = s.units,
            sparkMs = 900_000L, end = null, fill = true,
        ) { nav.open(Page.CHARTS) }
    }
}

/**
 * A column of the dash that scrolls when it does not fit; when it does, its last child takes the height that is left
 * (the parked trip card's sparkline grows).
 */
@Composable
private fun FillColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val h = maxHeight
        val scroll = rememberScrollState()
        val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
        val pad = with(LocalDensity.current) { 12.dp.roundToPx() }
        Layout(content, Modifier.fillMaxWidth().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) { ms, cs ->
            val loose = cs.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
            val heads = ms.dropLast(1).map { it.measure(loose) }
            val used = heads.sumOf { it.height } + gap * (ms.size - 1)
            val left = (h.roundToPx() - pad) - used
            val last = ms.lastOrNull()?.let { m ->
                val hh = maxOf(m.minIntrinsicHeight(cs.maxWidth), left).coerceAtLeast(0)
                m.measure(loose.copy(minHeight = hh, maxHeight = hh))
            }
            val all = heads + listOfNotNull(last)
            val total = all.sumOf { it.height } + gap * (all.size - 1).coerceAtLeast(0)
            layout(cs.maxWidth, total) {
                var y = 0
                for (p in all) { p.place(0, y); y += p.height + gap }
            }
        }
    }
}

/** «Поездка · с 21:42 · 98 Вт·ч»: Проехал / В пути / Расход поездки, the power sparkline; the whole card opens Графики. */
@Composable
fun TripCard(
    title: String, km: Double?, movingS: Long, whPerKm: Double?, units: String, sparkMs: Long?, end: io.github.purplepalm.birdair.phone.RideEnd?,
    fill: Boolean = false, onClick: () -> Unit,
) {
    val c = LocalBal.current
    WellCard(onDash = true, onClick = onClick, padding = PaddingValues(start = 14.dp, end = 14.dp, top = if (fill) 8.dp else 14.dp, bottom = if (fill) 6.dp else 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = if (fill) 2.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = Tnum, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("Графики", color = c.flow, fontSize = 13.sp)
            Icon(AppIcons.Chev, contentDescription = null, tint = c.ink2, modifier = Modifier.size(24.dp))
        }
        TripMetrics(km, movingS, whPerKm, units)
        // parked: the sparkline takes what is left of the screen (the card is measured to it, [FillColumn])
        Sparkline(sparkMs, Modifier.fillMaxWidth().padding(top = if (fill) 6.dp else 10.dp).then(if (fill) Modifier.weight(1f).heightIn(min = 24.dp) else Modifier.height(40.dp)))
        if (end != null) Plate(if (end.crit) PlateTone.CRIT else PlateTone.WARN, "Конец: " + end.title, Modifier.padding(top = 10.dp), text = end.detail)
    }
}

@Composable
private fun TripMetrics(km: Double?, movingS: Long, whPerKm: Double?, units: String) {
    val d = Units.distLabel(units)
    val per = whPerKm?.let { if (units == Units.MPH) it * Units.MILE else it }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TripMetric("Проехал", Fmt.n(Units.dist(km, units), 1), d, Modifier.weight(0.8f))
        TripMetric("В пути", Fmt.n((movingS / 60).toDouble()), "мин", Modifier.weight(0.7f))
        TripMetric("Расход поездки", Fmt.n(per, 1), "Вт·ч/$d", Modifier.weight(1.3f))
    }
}

@Composable
private fun TripMetric(label: String, value: String, unit: String, modifier: Modifier) {
    val c = LocalBal.current
    Column(modifier) {
        Text(label, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = c.ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, style = Tnum, maxLines = 1)
            Text(" $unit", color = c.ink2, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/** The power of the last [windowMs] (null: the whole recording) from the chart history: orange above zero, blue below. */
@Composable
fun Sparkline(windowMs: Long?, modifier: Modifier = Modifier) {
    val c = LocalBal.current
    val fast = ScooterRepo.settings.collectAsStateWithLifecycle().value.fastPower
    val rev by ScooterRepo.chartRev.collectAsStateWithLifecycle()
    var widthPx by remember { mutableIntStateOf(0) }
    val env by produceState<Pair<LongArray, Envelope>?>(null, rev, widthPx, windowMs, fast) {
        if (widthPx <= 0) return@produceState
        value = withContext(Dispatchers.Default) {
            val ring = ScooterRepo.samples
            val last = ring.lastT ?: return@withContext null
            val first = ring.firstT ?: last
            val start = if (windowMs == null) first else maxOf(first, last - windowMs)
            val x = ring.copy(start)
            longArrayOf(start, last) to Downsample.minMax(x.t, x.p(fast), start, last, widthPx)
        }
    }
    Canvas(modifier.onSizeChanged { widthPx = it.width }.semantics { contentDescription = "Мощность за поездку" }) {
        val e = env ?: return@Canvas
        val (r, en) = e
        if (en.empty) return@Canvas
        val lo = minOf(0f, en.min)
        val hi = maxOf(0f, en.max).let { if (it - lo < 100f) lo + 100f else it }
        val span = maxOf(1L, r[1] - r[0]).toFloat()
        fun X(t: Long) = (t - r[0]) / span * size.width
        fun Y(v: Float) = 2f + (hi - v) / (hi - lo) * (size.height - 4f)
        val zero = Y(0f)
        drawLine(c.base, Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx())
        for (run in en.runs) {
            val line = Path()
            for (j in 0 until run.size) { val x = X(run.t[j]); val y = Y(run.y[j]); if (j == 0) line.moveTo(x, y) else line.lineTo(x, y) }
            val area = Path().apply { addPath(line); lineTo(X(run.t[run.size - 1]), zero); lineTo(X(run.t[0]), zero); close() }
            clipRect(0f, 0f, size.width, zero) { drawPath(area, c.draw.copy(alpha = 0.14f)); drawPath(line, c.draw, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            clipRect(0f, zero, size.width, size.height) { drawPath(area, c.flow.copy(alpha = 0.14f)); drawPath(line, c.flow, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}

// -- idle

/**
 * IDLE: the first-launch card one step at a time; or the scooter (and the error that stopped it, in red), the last ride
 * with why it ended, the battery as it was. Nothing chosen yet: «Самокат не выбран».
 */
@Composable
fun DashIdle(setup: SetupStep, modifier: Modifier = Modifier) {
    val c = LocalBal.current
    val nav = LocalNav.current
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val last by ScooterRepo.lastRide.collectAsStateWithLifecycle()
    val s by ScooterRepo.settings.collectAsStateWithLifecycle()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (setup != SetupStep.DONE) {
            SetupCard(setup)
            Text("Потом — «Найти самокат»: приложение запомнит его и будет подключаться само.", color = c.muted, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp))
            return@Column
        }
        if (st.address == null) {
            WellCard(onDash = true) {
                Hero(AppIcons.Scooter, c.ink2, "Самокат не выбран", "Нажми «Найти самокат» — найду его поблизости")
                Text("Приложение Bird на телефоне должно быть закрыто: руль держит одно подключение.", color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 12.dp))
            }
            return@Column
        }
        val r = last
        WellCard(onDash = true) {
            Hero(AppIcons.Scooter, c.ink2, st.deviceName ?: "Самокат", st.address + (r?.let { " · до " + Fmt.clock(it.endMs, seconds = false) } ?: ""))
            if (st.err && st.message.isNotEmpty()) Text(st.message, color = c.critInk, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
            else if (st.running) Text(st.message.ifEmpty { "Подключаюсь…" }, color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
        }
        if (r != null) {
            TripCard(
                title = DashText.tripTitle(r.startMs, r.endMs, null), km = r.km, movingS = r.movingS, whPerKm = r.whPerKm, units = s.units,
                sparkMs = null, end = r.end,
            ) { ScooterRepo.updateUi { it.copy(chartWindow = ChartWindow.ALL) }; nav.open(Page.CHARTS) }
            LastBattery(r)
        }
    }
}

/** «Батарея (последнее)»: the charge, the bar and the voltage as the recording ended. */
@Composable
fun LastBattery(r: LastRide) {
    val c = LocalBal.current
    WellCard(onDash = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Батарея (последнее)", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (r.mah != null) Text(Fmt.n(r.mah) + " / " + Fmt.n(r.fullMah) + " мА·ч", color = c.muted, fontSize = 13.sp, style = Tnum)
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.n(r.pct, 1), color = c.ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, style = Tnum, maxLines = 1)
                Text(" %", color = c.ink2, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.width(12.dp))
            LevelBar(r.pct, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(Fmt.n(r.volts, 2) + " В", color = c.ink, fontSize = 15.sp, style = Tnum, maxLines = 1)
        }
    }
}

/** An icon in a 52 dp circle, a 19 sp title and a muted line. */
@Composable
fun Hero(icon: ImageVector, tint: Color, title: String, sub: String?) {
    val c = LocalBal.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(c.well).border(1.dp, c.hair, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = c.muted, fontSize = 13.sp, style = Tnum, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** First launch: one card at a time with the three steps on top; its action is on the action row, in the thumb zone. */
@Composable
fun SetupCard(step: SetupStep) {
    val c = LocalBal.current
    val sys = LocalSys.current
    val cur = when (step) { SetupStep.PERMS, SetupStep.NOTIF -> 0; SetupStep.BLUETOOTH -> 1; else -> 2 }
    WellCard(onDash = true) {
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Разрешения", "Bluetooth", "Работа в фоне").forEachIndexed { i, n ->
                val on = i == cur
                val done = i < cur
                val shape = RoundedCornerShape(15.dp)
                Row(
                    Modifier.weight(1f).height(30.dp).clip(shape).background(if (on) c.flow.copy(alpha = 0.14f) else c.well)
                        .border(1.dp, if (on) c.flow else c.hair, shape).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                ) {
                    if (done) { Icon(AppIcons.Check, contentDescription = null, tint = c.goodInk, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(n, color = when { on -> c.ink; done -> c.goodInk; else -> c.muted }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        when (step) {
            SetupStep.PERMS -> {
                Hero(AppIcons.Key, c.flow, "Нужны разрешения", "Шаг 1 из 3 · Android спросит дважды")
                Need(AppIcons.Bt, "Устройства поблизости", "Найти самокат и держать с ним связь. На Android 11 и старше это «Местоположение».")
                Need(AppIcons.Bell, "Уведомления", "Поездка и предупреждения на экране блокировки — пока экран выключен, это и есть приборка.")
                if (sys.permsBlocked) Text("Android больше не показывает запрос: разреши в настройках приложения → Разрешения.", color = c.critInk, fontSize = 13.sp,
                    lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp))
            }
            SetupStep.NOTIF -> {
                Hero(AppIcons.Bell, c.flow, "Уведомления выключены", "Шаг 1 из 3 · можно и без них")
                Need(AppIcons.Bell, "Уведомления", "Поездка и предупреждения на экране блокировки — пока экран выключен, это и есть приборка. Связь работает и без них.")
                if (sys.permsBlocked) Text("Android больше не показывает запрос: разреши в настройках приложения → Разрешения.", color = c.critInk, fontSize = 13.sp,
                    lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp))
            }
            SetupStep.BLUETOOTH -> {
                Hero(AppIcons.Bt, c.flow, "Bluetooth выключен", "Шаг 2 из 3")
                Text("Без него телефон не увидит самокат. Включу Bluetooth и вернусь сюда.", color = c.ink2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp))
            }
            else -> {
                Hero(AppIcons.Moon, c.warnInk, "Работа в фоне", "Шаг 3 из 3" + if (sys.samsung) " · Samsung" else "")
                Text(
                    if (sys.samsung) "Samsung усыпляет приложения с выключенным экраном — связь с самокатом рвётся посреди поездки. Разреши Bird Air работать без ограничений: «Батарея → Без ограничений»."
                    else "Чтобы связь не рвалась с выключенным экраном, разреши приложению работать без ограничений батареи.",
                    color = c.ink2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Need(icon: ImageVector, title: String, text: String) {
    val c = LocalBal.current
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = c.flow, modifier = Modifier.padding(top = 1.dp).size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}
