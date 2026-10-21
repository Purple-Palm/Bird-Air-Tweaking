package io.github.purplepalm.birdair.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.purplepalm.birdair.core.Link
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

    private fun replay(untilMs: Long, window: String) {
        val app = rule.activity.applicationContext
        ScooterRepo.init(app)
        ScooterRepo.tracker.setDecoder(TestLogs.decoder())
        ScooterRepo.tracker.odometerState = null   // each shot replays the log from its start
        ScooterRepo.updateUi { it.copy(chartWindow = window, fastPower = true, smooth = true) }
        ScooterRepo.status.update { it.copy(running = true, link = Link.CONNECTED, deviceName = "scooter", address = "AA:BB:CC:DD:EE:FF") }
        ScooterRepo.setNote("вдвоём, FLY, до отсечки")
        val s = ScooterRepo.newSession("scooter", "AA:BB:CC:DD:EE:FF")
        ScooterRepo.setLink(Link.CONNECTED)
        var at = 0L
        var marked = false
        for (e in TestLogs.rx(LOG)) {
            if (e.t > untilMs) break
            if (e.t > at) { ShadowSystemClock.advanceBy(Duration.ofMillis(e.t - at)); at = e.t }
            s.recorder.rx(e.s, e.u, e.b, s.now())
            s.rx.incrementAndGet()
            ScooterRepo.pipeline.onRx(e.s, e.u, e.b, s.now())
            ScooterRepo.pipeline.publishNow()
            if (!marked && e.t >= untilMs - 150_000) { ScooterRepo.mark("горка"); marked = true }
        }
    }

    private fun shot(name: String, untilMs: Long, window: String, touchAt: Float? = null, tapLamp: String? = null, extra: () -> Unit = {}) {
        replay(untilMs, window)
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

    companion object {
        const val LOG = "logs/20261019-174739-log-app.json.gz"
        /** Both warnings critical (the advisor: «перегрета» from 5158 s, «Сейчас отключится» from 5384 s; the cutoff at 5487 s). */
        const val UNTIL_MS = 5_420_000L
    }
}
