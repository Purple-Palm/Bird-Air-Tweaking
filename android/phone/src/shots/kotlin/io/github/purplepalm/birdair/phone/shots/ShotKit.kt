package io.github.purplepalm.birdair.phone.shots

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.phone.ChartWindow
import io.github.purplepalm.birdair.phone.EndCause
import io.github.purplepalm.birdair.phone.Found
import io.github.purplepalm.birdair.phone.Installed
import io.github.purplepalm.birdair.phone.LocalLog
import io.github.purplepalm.birdair.phone.LogStatus
import io.github.purplepalm.birdair.phone.Perms
import io.github.purplepalm.birdair.phone.ScanState
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.TestLogs
import io.github.purplepalm.birdair.phone.UiSettings
import io.github.purplepalm.birdair.phone.UpdateInfo
import io.github.purplepalm.birdair.phone.UpdateState
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.ViewPin
import io.github.purplepalm.birdair.phone.WatchStatus
import io.github.purplepalm.birdair.phone.WatchVersion
import io.github.purplepalm.birdair.phone.ControlState
import io.github.purplepalm.birdair.phone.LinkStatus
import io.github.purplepalm.birdair.phone.Marker
import io.github.purplepalm.birdair.phone.WatchFaceState
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.BirdTheme
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.MainScreen
import io.github.purplepalm.birdair.phone.ui.SystemState
import io.github.purplepalm.birdair.phone.ui.UiActions
import io.github.purplepalm.birdair.phone.ui.shell.LocalShellInsets
import io.github.purplepalm.birdair.phone.ui.shell.NavStart
import io.github.purplepalm.birdair.phone.ui.shell.ShellInsets
import kotlinx.coroutines.flow.update
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.TimeZone

/** The screen's actions in a shot: nothing happens, every call is kept (the hold and guard tests read them). */
class FakeUi : UiActions {
    val calls = ArrayList<String>()
    override fun requestPermissions() { calls += "requestPermissions" }
    override fun openAppSettings() { calls += "openAppSettings" }
    override fun enableBluetooth() { calls += "enableBluetooth" }
    override fun connect() { calls += "connect" }
    override fun disconnect() { calls += "disconnect" }
    override fun scan(all: Boolean) { calls += "scan" }
    override fun closeScan() { calls += "closeScan"; ScooterRepo.scan.update { it.copy(shown = false) } }
    override fun choose(f: Found) { calls += "choose" }
    override fun saveLog() { calls += "saveLog" }
    override fun shareLog() { calls += "shareLog" }
    override fun sendLog() { calls += "sendLog" }
    override fun sendAll() { calls += "sendAll" }
    override fun shareFile(f: LocalLog) { calls += "shareFile" }
    override fun refreshLogs() { calls += "refreshLogs" }
    override fun openOnWatch() { calls += "openOnWatch" }
    override fun requestIgnoreBattery() { calls += "requestIgnoreBattery" }
    override fun updateStep() { calls += "updateStep" }
    override fun allowInstall() { calls += "allowInstall" }
}

/** The two phones of the prototype: 390×844 and the S21's 360×800 (xhdpi: 780×1688 and 720×1600 px). */
object Size {
    const val P390 = "w390dp-h844dp-xhdpi"
    const val S21 = "w360dp-h800dp-xhdpi"
}

/** The prototype's frame: a fake 32 dp status bar (time, Bluetooth, signal, «71 %», battery) and an 18 dp gesture bar (or 48 dp of 3 buttons). */
@Composable
fun ShotFrame(clock: String, threeButtons: Boolean = false, fontScale: Float? = null, content: @Composable () -> Unit) {
    val c = LocalBal.current
    val d = LocalDensity.current
    val bottom = if (threeButtons) 48.dp else 18.dp
    CompositionLocalProvider(
        LocalShellInsets provides ShellInsets(32.dp, bottom),
        LocalDensity provides (if (fontScale != null) Density(d.density, fontScale) else d),
    ) {
        Box(Modifier.fillMaxSize().background(c.bg)) {
            content()
            Row(Modifier.fillMaxWidth().height(32.dp).padding(start = 28.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(clock, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Bt, null, tint = c.ink, modifier = Modifier.size(24.dp))
                    Icon(AppIcons.Sig, null, tint = c.ink, modifier = Modifier.size(24.dp))
                    Text("71 %", color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Icon(AppIcons.Bat, null, tint = c.ink, modifier = Modifier.size(24.dp))
                }
            }
            Box(Modifier.align(Alignment.TopCenter).offset(y = 10.dp).size(12.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.Black))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom), contentAlignment = Alignment.Center) {
                if (!threeButtons) Box(Modifier.width(108.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.ink.copy(alpha = 0.5f)))
                else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Back, null, tint = c.ink2, modifier = Modifier.size(22.dp))
                    Box(Modifier.size(18.dp).clip(CircleShape).background(c.ink2.copy(alpha = 0.6f)))
                    Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(c.ink2.copy(alpha = 0.6f)))
                }
            }
        }
    }
}

/**
 * The prototype's data, the same in every shot: the 20.10 ride «до отсечки, до нуля» replayed through the real pipeline
 * (its own markers too), build v108 installed, v109 in the release, the watch on v106, three unsent logs.
 */
object Scenes {
    const val LOG = TestLogs.TO_ZERO
    const val RIDE_MS = 1_800_000L
    const val PARKED_MS = 1_470_000L
    val INSTALLED = Installed(108, "8-a41290d", "a41290d")
    const val APK = 19_293_798L
    val RELEASE = UpdateInfo(109, "9-c3f19d2", "c3f19d2e41b7a90c", phoneSize = APK, notes = "циферблаты часов — выбор на телефоне и на часах; названия ламп.")
    const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    private val ZONE = TimeZone.getTimeZone("America/Los_Angeles")

    enum class Upd { NONE, AVAILABLE, DOWNLOADING, READY }
    enum class Logs { NONE, UNSENT, SENDING, SENT }

    /** Every shot starts from here: the repo is process-wide, so all of it is set again. */
    fun base(ctx: android.content.Context) {
        TimeZone.setDefault(ZONE)
        ScooterRepo.init(ctx)
        Updater.installed = INSTALLED
        ScooterRepo.tracker.setDecoder(TestLogs.decoder())
        ScooterRepo.tracker.odometerState = null
        ScooterRepo.settings.value = UiSettings(chartWindow = "300", fastPower = true, smooth = true, keepScreenOn = false, batteryHintHidden = true)
        ScooterRepo.status.value = LinkStatus(deviceName = "scooter", address = ADDRESS)
        ScooterRepo.scan.value = ScanState()
        ScooterRepo.control.value = ControlState()
        ScooterRepo.update.value = UpdateState()
        ScooterRepo.lastRide.value = null
        ScooterRepo.viewPin.value = ViewPin.AUTO
        ScooterRepo.bulbUntil.value = 0L
        ScooterRepo.unsent.value = emptyList()
        ScooterRepo.log.value = LogStatus()
        ScooterRepo.gh.value = ScooterRepo.gh.value.copy(auto = true)
        ScooterRepo.hasToken.value = true
        ScooterRepo.events.value = emptyList()
        ScooterRepo.face.value = WatchFaceState()
        watch()
    }

    fun watch(old: Boolean = true) {
        ScooterRepo.watch.value = WatchStatus(nodes = listOf("Galaxy Watch Ultra"), checked = true, helloAt = SystemClock.elapsedRealtime(),
            version = if (old) WatchVersion(106, "6-b71d0e3") else WatchVersion(108, "8-a41290d"))
    }

    /** The log through the pipeline up to [untilMs], the link up, its markers set at their times; [moreMs]: the clock goes on with no frames. */
    fun replay(untilMs: Long?, moreMs: Long = 0) {
        val until = untilMs ?: Long.MAX_VALUE
        ScooterRepo.setNote("до отсечки, до нуля")
        ScooterRepo.status.update { it.copy(running = true, link = Link.CONNECTED) }
        val s = ScooterRepo.newSession("scooter", ADDRESS, wallMs = TestLogs.startedAt(LOG))
        ScooterRepo.setLink(Link.CONNECTED)
        val marks = ArrayDeque(TestLogs.marks(LOG))
        var at = 0L
        for (e in TestLogs.rx(LOG, found = true)) {
            if (e.t > until) break
            if (e.t > at) { ShadowSystemClock.advanceBy(Duration.ofMillis(e.t - at)); at = e.t }
            while (marks.isNotEmpty() && marks.first().first <= e.t) ScooterRepo.mark(marks.removeFirst().second)
            if (e.found) { ScooterRepo.restartStreams(); continue }
            s.recorder.rx(e.s, e.u, e.b, s.now())
            s.rx.incrementAndGet()
            ScooterRepo.pipeline.onRx(e.s, e.u, e.b, s.now())
            ScooterRepo.pipeline.publishNow()
        }
        if (untilMs != null && untilMs > at) { ShadowSystemClock.advanceBy(Duration.ofMillis(untilMs - at)); at = untilMs }
        ScooterRepo.pipeline.publishNow()
        var left = moreMs
        while (left > 0) { val step = minOf(left, 1000L); ShadowSystemClock.advanceBy(Duration.ofMillis(step)); left -= step; ScooterRepo.pipeline.publishNow() }
    }

    /** The whole recording, then «Отключить». */
    fun idle() {
        replay(null)
        ScooterRepo.ended(EndCause.OWNER)
        val last = ScooterRepo.telemetry.value.copy(link = Link.IDLE, streaming = false)
        ScooterRepo.status.update { it.copy(running = false, link = Link.IDLE, message = "Отключено", err = false) }
        // not halt(): that sticks to the process-wide pipeline until a service runs it, and the next shot replays through it
        ScooterRepo.pipeline.set(last)
    }

    fun ride() = replay(RIDE_MS)
    fun parked() = replay(PARKED_MS)
    /** Connected, the dash silent for 10 s after 1470 s. */
    fun silent() = replay(PARKED_MS, moreMs = 10_000)

    fun update(u: Upd) {
        val checked = System.currentTimeMillis() - 3 * 3600_000L
        ScooterRepo.update.value = when (u) {
            Upd.NONE -> UpdateState(latest = UpdateInfo(108, "8-a41290d", "a41290d"), checkedAt = checked)
            Upd.AVAILABLE -> UpdateState(latest = RELEASE, checkedAt = checked)
            Upd.DOWNLOADING -> UpdateState(latest = RELEASE, checkedAt = checked, downloading = true, gotBytes = APK * 63 / 100, totalBytes = APK, progress = 0.63f)
            Upd.READY -> UpdateState(latest = RELEASE, checkedAt = checked, readyCode = 109)
        }
    }

    private val UNSENT = listOf(
        Triple("20261020-214221-do-otsechki-do-nulya", 473_088L, true),
        Triple("20261020-195512-app", 120_832L, false),
        Triple("20261020-181544-zaryadka", 1_153_434L, false),
    )

    fun logs(l: Logs) {
        val day = TestLogs.startedAt(LOG)
        ScooterRepo.unsent.value = if (l == Logs.UNSENT || l == Logs.SENDING) UNSENT.mapIndexed { i, (n, size, cur) ->
            LocalLog("$n.json.gz", size, day - i * 3_000_000L, current = cur && ScooterRepo.status.value.running)
        } else emptyList()
        ScooterRepo.log.update {
            when (l) {
                Logs.SENDING -> it.copy(bulkBusy = true, bulkDone = 1, bulkTotal = 3)
                Logs.SENT -> it.copy(bulkBusy = false, bulkDone = 3, bulkTotal = 3, bulkCommit = "7c1e2a9", bulkUrl = "https://github.com/Purple-Palm/Bird-Air-Tweaking/commit/7c1e2a9", bulkAt = System.currentTimeMillis())
                else -> it.copy(bulkBusy = false, bulkCommit = null, bulkAt = 0)
            }
        }
    }

    /** What the first launch has not got yet ([step]: perms or the Samsung background step). */
    fun setupSys(perms: Boolean): SystemState =
        if (perms) SystemState(missingPerms = (Perms.scan() + Manifest.permission.POST_NOTIFICATIONS).distinct(), samsung = true, ignoringBattery = false)
        else SystemState(samsung = true, ignoringBattery = false)

    fun scanList() {
        ScooterRepo.scan.value = ScanState(scanning = false, shown = true, message = "Выбери свой самокат.",
            found = listOf(Found(ADDRESS, "scooter", -58), Found("C4:7F:51:0A:33:12", "Bird Air", -81)))
    }

    fun mark(): Marker? = ScooterRepo.markNow()

    fun allWindow() { ScooterRepo.updateUi { it.copy(chartWindow = ChartWindow.ALL) } }
}

/**
 * Renders [MainScreen] in [ShotFrame], dark then light, into build/shots/<name>[-s21]-dark|light.png at xhdpi.
 * The frame clock is manual (autoAdvance = false): performScrollTo() then spins forever, as the scroll animation gets no
 * frames. Below the fold tap with performSemanticsAction(SemanticsActions.OnClick), or turn autoAdvance on around a scroll.
 */
class Shooter(private val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>) {
    val ui = FakeUi()
    private var dark by mutableStateOf(true)
    /** The activity's state; a test changes it as the activity would (a notification tap, an install Android held back). */
    var sys by mutableStateOf(SystemState())

    val s21: Boolean get() = rule.activity.resources.configuration.screenWidthDp <= 360

    private var t0 = 0L

    fun setContent(clock: String, start: NavStart, sys: SystemState, threeButtons: Boolean = false, fontScale: Float? = null) {
        rule.mainClock.autoAdvance = false
        t0 = rule.mainClock.currentTime
        this.sys = sys
        rule.setContent { BirdTheme(dark) { ShotFrame(clock, threeButtons, fontScale) { MainScreen(ui, this.sys, start) } } }
        settle()
    }

    /** The charts and sparklines are built on Dispatchers.Default, which Compose's idling does not track: real time for them,
     *  and a little of the frame clock (under 3 s in all, so a snackbar stays up). */
    fun settle(steps: Int = 12) {
        repeat(steps) {
            Thread.sleep(60)
            rule.mainClock.advanceTimeBy(100)
            rule.waitForIdle()
        }
    }

    /** The other theme, a few frames for it to recompose and draw (~0,2 s: a text field's border colour animates 150 ms). */
    private fun flip(to: Boolean) {
        rule.runOnIdle { dark = to }
        repeat(13) { rule.mainClock.advanceTimeBy(16); rule.waitForIdle() }
    }

    /** To 0,2 s into a blink second: a red lamp is on in the picture. */
    private fun align() {
        val d = (rule.mainClock.currentTime - t0).mod(1000L)
        val to = (1200L - d).mod(1000L)
        if (to > 0) rule.mainClock.advanceTimeBy(to, ignoreFrameDuration = true)
        rule.waitForIdle()
    }

    fun capture(name: String) {
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File(System.getProperty("shotDir") ?: "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Dark, then light (the same composition: setContent goes once per test). */
    fun both(name: String, suffix: String = if (s21) "-s21" else "") {
        align()
        capture("$name$suffix-dark")
        flip(false)
        capture("$name$suffix-light")
        flip(true)
    }
}
