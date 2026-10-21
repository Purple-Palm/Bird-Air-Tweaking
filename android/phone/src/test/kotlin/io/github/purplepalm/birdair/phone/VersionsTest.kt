package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test fun versionJson() {
        val v = Versions.parse("""{"versionCode": 42, "versionName": "0.42", "sha": "1a2b3c4d5e6f"}""")!!
        assertEquals(UpdateInfo(42, "0.42", "1a2b3c4d5e6f"), v)
        assertEquals("v42 (0.42, 1a2b3c4)", v.label())
        assertEquals(UpdateInfo(7, "", ""), Versions.parse("""{"versionCode":"7","extra":true}"""))
        assertNull(Versions.parse("""{"versionName":"x"}"""))
        assertNull(Versions.parse("""{"versionCode":0}"""))
        assertNull(Versions.parse("<html>Not Found</html>"))
        assertNull(Versions.parse(""))
        assertNull(Versions.parse(null))
        assertEquals("v3", UpdateInfo(3, "", "").label())
        assertTrue(Versions.VERSION_URL.endsWith("/releases/download/android-latest/version.json"))
        assertTrue(Versions.PHONE_APK_URL.endsWith("/releases/download/android-latest/bird-air-phone.apk"))
    }

    @Test fun newerByVersionCodeOnly() {
        val r = UpdateInfo(42, "0.42", "abc")
        assertTrue(Versions.isNewer(r, 41))
        assertTrue("a local build (code 1) sees the release", Versions.isNewer(r, 1))
        assertFalse(Versions.isNewer(r, 42))
        assertFalse(Versions.isNewer(r, 43))
        assertFalse(Versions.isNewer(null, 1))
    }

    @Test fun checkOnStartAndDaily() {
        val h = 3600_000L
        assertTrue("never checked", Versions.checkDue(0, 100 * h, atStart = false))
        assertTrue("at start, last check an hour ago", Versions.checkDue(99 * h, 100 * h, atStart = true))
        assertFalse("restarted a minute later", Versions.checkDue(100 * h - 60_000, 100 * h, atStart = true))
        assertFalse("running, checked 23 h ago", Versions.checkDue(77 * h, 100 * h, atStart = false))
        assertTrue("running, a day passed", Versions.checkDue(76 * h, 100 * h, atStart = false))
        assertTrue("clock went back", Versions.checkDue(101 * h, 100 * h, atStart = false))
    }

    @Test fun watchVersionFromHello() {
        assertNull("an old watch build says hello with nothing", Versions.watch(ByteArray(0)))
        assertNull(Versions.watch(null))
        assertEquals(WatchVersion(42, "0.42"), Versions.watch("""{"versionCode":42,"versionName":"0.42"}""".toByteArray()))
        assertEquals(WatchVersion(5, null), Versions.watch("""{"vc":5}""".toByteArray()))
        assertEquals(WatchVersion(9, "dev"), Versions.watch("versionCode=9\nversionName=dev".toByteArray()))
        assertNull(Versions.watch("""{"other":1}""".toByteArray()))
        assertNull(Versions.watch("hello".toByteArray()))
        // the hint
        assertNull(Versions.watchHint(WatchVersion(42, null), true, 42))
        val older = Versions.watchHint(WatchVersion(40, null), true, 42)!!
        assertTrue(older, older.startsWith("На часах v40, на телефоне v42 — на часах: Подробно → Обновить часы"))
        assertFalse("no extra apps", older.contains("Bugjaeger"))
        val unknown = Versions.watchHint(null, true, 42)!!
        assertTrue(unknown.contains("старая сборка") && unknown.contains("вручную") && unknown.contains(Versions.WATCH_UPDATE))
        assertNull("no hello yet: nothing to say", Versions.watchHint(null, false, 42))
        assertNull("a local phone build compares with nothing", Versions.watchHint(null, true, 1))
    }

    @Test fun theWatchAppsOwnHello() {
        // what wear/Flags.kt HelloInfo.encode() sends (a CI build: -PversionName=<run>-<sha>)
        val v = Versions.watch("""{"app":"wear","versionCode":112,"versionName":"12-abc1234"}""".toByteArray(Charsets.UTF_8))
        assertEquals(WatchVersion(112, "12-abc1234"), v)
        assertNull(Versions.watchHint(v, true, 112))
        assertTrue(Versions.watchHint(v, true, 113)!!.contains("v112"))
    }

    @Test fun warningsStayOnThePhoneWhileTheWatchAppBuzzes() {
        val now = 1_000_000L
        val v = WatchVersion(112, "12-abc1234")
        assertTrue("hello 30 s ago, with a version", Versions.watchBuzzes(WatchStatus(helloAt = now - 30_000, version = v), now))
        assertFalse("an old watch build (empty hello) does not buzz", Versions.watchBuzzes(WatchStatus(helloAt = now - 1_000, version = null), now))
        assertFalse("watch app closed: no hello for a minute", Versions.watchBuzzes(WatchStatus(helloAt = now - 60_000, version = v), now))
        assertFalse("never said hello", Versions.watchBuzzes(WatchStatus(), now))
    }

    @Test fun displayFlagsGoToTheWatch() {
        val was = ScooterRepo.settings.value
        try {
            ScooterRepo.settings.value = UiSettings(fastPower = true, smooth = false)
            val t = ScooterRepo.forWatch(Telemetry(powerW = 100.0, powerFastW = 140.0, extras = mapOf("Поле" to 1.0)))
            assertEquals(1.0, t.extras[ScooterRepo.EXTRA_FAST]!!, 0.0)
            assertEquals(0.0, t.extras[ScooterRepo.EXTRA_SMOOTH]!!, 0.0)
            assertEquals(1.0, t.extras["Поле"]!!, 0.0)
            val back = Wire.decode(Wire.encode(t))!!
            assertEquals(t.extras, back.extras)
            assertEquals(140.0, back.powerFastW!!, 0.0)
        } finally { ScooterRepo.settings.value = was }
    }
}
