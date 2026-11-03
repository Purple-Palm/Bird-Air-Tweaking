package io.github.purplepalm.birdair.phone.shots

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Looper
import androidx.core.content.FileProvider
import io.github.purplepalm.birdair.phone.BuildConfig
import io.github.purplepalm.birdair.phone.Installed
import io.github.purplepalm.birdair.phone.MainActivity
import io.github.purplepalm.birdair.phone.Prefs
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.ScooterService
import io.github.purplepalm.birdair.phone.UpdPhase
import io.github.purplepalm.birdair.phone.UpdatedReceiver
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.Versions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.io.File

/**
 * «Установить» on the real MainActivity, parked with a recording running and v109 kept: one system installer however
 * many taps come while the recording is saved («Сохраняю лог…») or before the installer covers the screen — two would
 * stack, and coming back from either would undo the other's attempt; back from it (cancelled), the next tap is a new
 * attempt, and the update it puts in brings the link back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = Size.S21)
class InstallShots {
    private lateinit var app: Application
    private lateinit var c: ActivityController<MainActivity>
    private val started = ArrayList<Intent>()

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        // FileProvider keeps the paths of the first test's filesDir
        (FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>).clear()
        // no start-up check: the release stays the scene's
        Prefs(app).updateCheckedAt = System.currentTimeMillis()
        val apk = File(File(app.filesDir, "update").apply { mkdirs() }, Versions.PHONE_APK).apply { writeBytes(ByteArray(4096)) }
        val pm = shadowOf(app.packageManager)
        pm.setPackageArchiveInfo(apk.path, PackageInfo().apply { packageName = app.packageName; longVersionCode = 109 })
        pm.setCanRequestPackageInstalls(true)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        Scenes.base(app)
        Scenes.parked(); Scenes.update(Scenes.Upd.READY)
        ScooterRepo.prefs.address = Scenes.ADDRESS
        ScooterRepo.prefs.resumeAfterUpdateAt = 0L
        assertTrue("a recording runs", ScooterRepo.status.value.running)
        assertFalse("parked", ScooterRepo.moving.value)
        // the spinners animate forever: frames only when a test asks
        ShadowChoreographer.setPaused(true)
        c = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(UpdPhase.READY, Updater.phase())
        drain()
    }

    @After fun tearDown() {
        ShadowChoreographer.setPaused(false)
        Updater.installed = Installed(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA)
    }

    private fun drain() { while (true) started += shadowOf(c.get()).nextStartedActivity ?: break }

    /** The main thread's work as it comes due, the save's IO thread meanwhile; frames and delays stay where they are. */
    private fun pump(ms: Long = 30_000, until: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val end = System.currentTimeMillis() + ms
        while (!until() && System.currentTimeMillis() < end) {
            if (!looper.isIdle) looper.runOneTask() else Thread.sleep(2)
            drain()
        }
    }

    private fun installers() = started.filter { it.action == Intent.ACTION_VIEW && it.type == "application/vnd.android.package-archive" }
    private fun at() = ScooterRepo.prefs.resumeAfterUpdateAt ?: -1L

    /** MY_PACKAGE_REPLACED as the update went in: does it start the service to connect again? */
    private fun updateBringsTheLinkBack(): Boolean {
        while (shadowOf(app).nextStartedService != null) { }
        UpdatedReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        return shadowOf(app).nextStartedService?.action == ScooterService.ACTION_CONNECT
    }

    @Test fun repeatedTapsOpenOneInstaller() {
        val a = c.get()
        a.updateStep()
        assertTrue("the save runs: «Сохраняю лог…»", ScooterRepo.update.value.saving)
        val first = at()
        assertTrue("the attempt is written", first > 0)
        // a glove's double tap, then once more: «nothing happened»
        a.updateStep(); a.updateStep()
        pump { installers().isNotEmpty() }
        assertEquals(1, installers().size)
        assertFalse("saved", ScooterRepo.update.value.saving)
        // the installer opened, this activity not paused yet
        a.updateStep()
        pump(300) { false }
        assertEquals("still one installer", 1, installers().size)
        assertEquals("the first tap's attempt stands", first, at())
        assertTrue("the update brings the link back", updateBringsTheLinkBack())
        assertEquals("once", 0L, at())
    }

    /**
     * The installer left up 16 min (the phone in its mount, a call) while the recording runs on in the service, then
     * «Обновить»: the service's tick moved the attempt along, so the update still brings the link back.
     */
    @Test fun aLateConfirmStillBringsTheLinkBack() {
        c.get().updateStep()
        pump { installers().isNotEmpty() }
        val svc = Robolectric.buildService(ScooterService::class.java,
            Intent(app, ScooterService::class.java).setAction(ScooterService.ACTION_CONNECT).putExtra(ScooterService.EXTRA_ADDRESS, Scenes.ADDRESS))
            .create().startCommand(0, 1)
        try {
            assertTrue(ScooterRepo.status.value.running)
            ScooterRepo.prefs.resumeAfterUpdateAt = System.currentTimeMillis() - 16 * 60_000L
            // the service's periodic tick, 30 s on
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ScooterService.PERIODIC_MS + 1_000))
            assertTrue("moved along: ${System.currentTimeMillis() - at()} ms old", System.currentTimeMillis() - at() < 60_000)
            assertTrue("the update brings the link back", updateBringsTheLinkBack())
        } finally {
            svc.destroy()
        }
    }

    @Test fun cancelledThenTappedAgain() {
        val a = c.get()
        a.updateStep()
        pump { installers().isNotEmpty() }
        // the installer cancelled («Отмена», a touch outside): back here with this process alive
        c.pause(); c.resume()
        assertEquals("nothing went in: nothing to bring back", 0L, at())
        pump(300) { false }
        assertEquals("nothing opens by itself", 1, installers().size)
        a.updateStep()
        assertTrue("a new attempt", at() > 0)
        pump { installers().size >= 2 }
        assertEquals(2, installers().size)
        assertTrue("the update brings the link back", updateBringsTheLinkBack())
    }
}
