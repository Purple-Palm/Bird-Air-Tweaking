package io.github.purplepalm.birdair.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The updater's one state machine: the phase, the button's words, the kept APK, when the installer may open. */
class UpdateFlowTest {
    private val v108 = UpdateInfo(108, "8-a41290d", "a41290d")
    private val v109 = UpdateInfo(109, "9-c3f19d2", "c3f19d2", phoneSize = 19_293_798)

    @Test fun phaseOfEveryState() {
        fun p(u: UpdateState) = UpdateFlow.phase(u, installed = 108)
        assertEquals("never checked", UpdPhase.NONE, p(UpdateState()))
        assertEquals("the release is this build", UpdPhase.NONE, p(UpdateState(latest = v108)))
        assertEquals("an older release", UpdPhase.NONE, p(UpdateState(latest = UpdateInfo(107, "", ""))))
        assertEquals(UpdPhase.CHECKING, p(UpdateState(checking = true)))
        assertEquals(UpdPhase.AVAILABLE, p(UpdateState(latest = v109)))
        assertEquals("checking again while one is available", UpdPhase.CHECKING, p(UpdateState(latest = v109, checking = true)))
        assertEquals(UpdPhase.DOWNLOADING, p(UpdateState(latest = v109, downloading = true)))
        assertEquals(UpdPhase.READY, p(UpdateState(latest = v109, readyCode = 109)))
        assertEquals("ready beats a background check", UpdPhase.READY, p(UpdateState(latest = v109, readyCode = 109, checking = true)))
        assertEquals("a kept APK older than the release: download again", UpdPhase.AVAILABLE, p(UpdateState(latest = UpdateInfo(110, "", ""), readyCode = 109)))
        assertEquals("a kept APK that is installed already", UpdPhase.NONE, p(UpdateState(latest = v108, readyCode = 108)))
        assertEquals("kept, release not read yet (a new process)", UpdPhase.READY, p(UpdateState(readyCode = 109)))
    }

    @Test fun keptApk() {
        assertTrue(UpdateFlow.keep(109, 108, v109))
        assertTrue("version.json not read yet", UpdateFlow.keep(109, 108, null))
        assertTrue("newer than the release (CI is slow to publish)", UpdateFlow.keep(110, 108, v109))
        assertFalse("installed", UpdateFlow.keep(108, 108, v109))
        assertFalse("after the update (MY_PACKAGE_REPLACED)", UpdateFlow.keep(109, 109, v109))
        assertFalse("older than the release", UpdateFlow.keep(109, 108, UpdateInfo(110, "", "")))
        assertFalse("not an APK of this app", UpdateFlow.keep(null, 108, v109))
    }

    @Test fun theInstallerOpensItselfOnlyAfterATapAndOffTheRide() {
        assertTrue(UpdateFlow.openWhenDone(byTap = true, running = false))
        assertFalse("recording: never by itself", UpdateFlow.openWhenDone(byTap = true, running = true))
        assertFalse("the daily check's download", UpdateFlow.openWhenDone(byTap = false, running = false))
        assertFalse(UpdateFlow.openWhenDone(byTap = false, running = true))
    }

    @Test fun neverWhileMoving() {
        assertEquals("Ты едешь — установка недоступна", UpdateFlow.installBlock(moving = true))
        assertNull(UpdateFlow.installBlock(moving = false))
    }

    @Test fun oneButtonOneLabelPerState() {
        val dl = UpdateState(latest = v109, downloading = true, gotBytes = 19_293_798L * 63 / 100, totalBytes = 19_293_798)
        assertEquals("Проверить обновления", UpdateFlow.button(UpdPhase.NONE, v108, UpdateState()))
        assertEquals("Проверяю…", UpdateFlow.button(UpdPhase.CHECKING, null, UpdateState(checking = true)))
        assertEquals("Обновить до v109", UpdateFlow.button(UpdPhase.AVAILABLE, v109, UpdateState(latest = v109)))
        assertEquals("Скачиваю 63 %", UpdateFlow.button(UpdPhase.DOWNLOADING, v109, dl))
        assertEquals("Скачиваю…", UpdateFlow.button(UpdPhase.DOWNLOADING, UpdateInfo(109, "", ""), UpdateState(downloading = true)))
        assertEquals("Установить v109", UpdateFlow.button(UpdPhase.READY, v109, UpdateState(latest = v109, readyCode = 109)))
        // the size from version.json when the server does not send one
        assertEquals(0.5f, UpdateFlow.fraction(UpdateState(latest = v109, gotBytes = 19_293_798 / 2))!!, 0.001f)
        assertEquals("18,4 МБ", UpdateFlow.mb(19_293_798))
        assertNull(UpdateFlow.mb(null))
    }
}
