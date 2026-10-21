package io.github.purplepalm.birdair.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.dial.DialFace
import io.github.purplepalm.birdair.dial.FaceChoice
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.WatchFaceCard
import io.github.purplepalm.birdair.phone.ui.BirdTheme
import io.github.purplepalm.birdair.phone.ui.MainScreen
import io.github.purplepalm.birdair.phone.ui.SystemState
import io.github.purplepalm.birdair.phone.ui.UiActions
import kotlinx.coroutines.flow.update
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * PNGs of the main screen on a tall 360 dp phone, the whole scroll at once, for looking at the design without a
 * phone: ./gradlew :phone:testDebugUnitTest -Pshots → phone/build/shots/. The data: the two-up ride of 19.10 replayed
 * through the real pipeline up to [UNTIL_MS] (the battery near its end: warnings, range, charts).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h5600dp-xhdpi")
class Shots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ui = object : UiActions {
        override fun requestPermissions() {}
        override fun openAppSettings() {}
        override fun enableBluetooth() {}
        override fun connect() {}
        override fun disconnect() {}
        override fun scan(all: Boolean) {}
        override fun closeScan() {}
        override fun choose(f: Found) {}
        override fun saveLog() {}
        override fun shareLog() {}
        override fun sendLog() {}
        override fun sendAll() {}
        override fun shareFile(f: LocalLog) {}
        override fun refreshLogs() {}
        override fun openOnWatch() {}
        override fun requestIgnoreBattery() {}
        override fun checkUpdates() {}
        override fun startUpdate() {}
        override fun allowInstall() {}
    }

    private fun replay(log: String, untilMs: Long, window: String) {
        val app = rule.activity.applicationContext
        ScooterRepo.init(app)
        ScooterRepo.tracker.setDecoder(TestLogs.decoder())
        ScooterRepo.tracker.odometerState = null   // each shot replays the log from its start
        ScooterRepo.update.value = UpdateState()   // midRide's «Есть обновление» stays its own
        ScooterRepo.updateUi { it.copy(chartWindow = window, fastPower = true, smooth = true) }
        ScooterRepo.status.update { it.copy(running = true, link = Link.CONNECTED, deviceName = "scooter", address = "AA:BB:CC:DD:EE:FF") }
        ScooterRepo.setNote(if (log == LOG) "вдвоём, FLY, до отсечки" else "один, FLY, до отсечки, до нуля")
        val s = ScooterRepo.newSession("scooter", "AA:BB:CC:DD:EE:FF")
        ScooterRepo.setLink(Link.CONNECTED)
        var at = 0L
        var marked = false
        for (e in TestLogs.rx(log, found = true)) {
            if (e.t > untilMs) break
            if (e.t > at) { ShadowSystemClock.advanceBy(Duration.ofMillis(e.t - at)); at = e.t }
            if (e.found) { ScooterRepo.restartStreams(); continue }
            s.recorder.rx(e.s, e.u, e.b, s.now())
            s.rx.incrementAndGet()
            ScooterRepo.pipeline.onRx(e.s, e.u, e.b, s.now())
            ScooterRepo.pipeline.publishNow()
            if (!marked && e.t >= untilMs - 150_000) { ScooterRepo.mark("горка"); marked = true }
        }
    }

    private fun shot(name: String, untilMs: Long, window: String, touchAt: Float? = null, tapLamp: String? = null, log: String = LOG, extra: () -> Unit = {}) {
        replay(log, untilMs, window)
        extra()
        rule.setContent { BirdTheme(true) { MainScreen(ui, SystemState()) } }
        settle()
        if (touchAt != null) {
            rule.onNodeWithContentDescription("График: Мощность, Вт").performTouchInput { down(percentOffset(touchAt, 0.5f)); up() }
            settle()
        }
        if (tapLamp != null) {
            rule.onNodeWithContentDescription("Лампа «$tapLamp»", substring = true).performClick()
            settle()
        }
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File(System.getProperty("shotDir") ?: "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The chart frames are built on Dispatchers.Default, which Compose's idling does not track: give them real time. */
    private fun settle() {
        repeat(15) {
            Thread.sleep(80)
            rule.mainClock.advanceTimeBy(100)
            rule.waitForIdle()
        }
    }

    @Test fun nearTheEnd() = shot("near-the-end", UNTIL_MS, ChartWindow.ALL)
    /** «Приборка» with the temperature lamp tapped: what it says and when it lights. */
    @Test fun clusterTapped() = shot("cluster-tapped", UNTIL_MS, ChartWindow.ALL, tapLamp = "Температура батареи")
    @Test fun lastFiveMinutes() = shot("five-minutes", UNTIL_MS, "300", touchAt = 0.62f)
    @Test fun midRide() = shot("mid-ride", 4_700_000L, "60", touchAt = 0.8f) {
        ScooterRepo.update.value = UpdateState(latest = UpdateInfo(57, "0.57", "1a2b3c4d5e6f"), checkedAt = System.currentTimeMillis())
        ScooterRepo.watch.update { it.copy(nodes = listOf("Galaxy Watch Ultra"), checked = true, helloAt = android.os.SystemClock.elapsedRealtime(), version = WatchVersion(50, "0.50")) }
        ScooterRepo.unsent.value = listOf(LocalLog("20261019-174739-log.json.gz", 1_061_787, System.currentTimeMillis(), current = true), LocalLog("20261016-205717-log.json.gz", 1_300_730, System.currentTimeMillis() - 3 * 86_400_000L))
    }

    /** 20.10, 1.1 s after the BMS's last frame: «BMS отключает батарею» (b5 0x02), the charge lamp tapped. */
    @Test fun bmsCut() = shot("cluster-bms-cut", 1_813_500L, ChartWindow.ALL, tapLamp = "Заряд", log = TestLogs.TO_ZERO)
    /** 20.10, 1826 s: the controller's 0x26 («Нет связи с BMS») and the dash answering with no frames, the link lamp tapped. */
    @Test fun bmsSilent() = shot("cluster-bms-silent", 1_826_000L, "300", tapLamp = "Связь", log = TestLogs.TO_ZERO)

    /** «Циферблат часов» opened: the five faces as live previews, the watch still on the old one. Cropped to the card. */
    @Test fun watchFaces() {
        ScooterRepo.init(rule.activity.applicationContext)
        ScooterRepo.face.value = WatchFaceState(
            phone = FaceChoice(DialFace.STACK, 2_000, FaceChoice.PHONE), watch = FaceChoice(DialFace.CLASSIC, 1_000, FaceChoice.WATCH),
        )
        rule.setContent { BirdTheme(true) { Column(Modifier.background(LocalBal.current.bg).padding(16.dp)) { WatchFaceCard() } } }
        rule.onNodeWithText("Выбрать").performClick()
        settle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        // down to the card's last row (the screen is 5600 dp tall)
        val bg = bmp.getPixel(2, bmp.height - 2)
        val row = IntArray(bmp.width)
        var h = bmp.height
        while (h > 1) { bmp.getPixels(row, 0, bmp.width, 0, h - 1, bmp.width, 1); if (row.any { it != bg }) break; h-- }
        val dir = File(System.getProperty("shotDir") ?: "build/shots").apply { mkdirs() }
        File(dir, "watch-faces.png").outputStream().use { Bitmap.createBitmap(bmp, 0, 0, bmp.width, minOf(bmp.height, h + 32)).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        const val LOG = "logs/20261019-174739-log-app.json.gz"
        /** Both warnings critical (the advisor: «перегрета» from 5158 s, «Сейчас отключится» from 5384 s; the cutoff at 5487 s). */
        const val UNTIL_MS = 5_420_000L
    }
}
