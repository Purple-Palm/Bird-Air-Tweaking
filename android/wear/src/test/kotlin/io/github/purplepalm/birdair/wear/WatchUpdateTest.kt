package io.github.purplepalm.birdair.wear

import android.content.pm.PackageInstaller
import io.github.purplepalm.birdair.dial.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WatchUpdateTest {
    /** Fmt puts a no-break space before units. */
    private fun plain(s: String?) = s?.replace('\u00A0', ' ')
    private val abcSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun versionJson() {
        val r = Release.parse("""{"versionCode": 112, "versionName": "12-1a2b3c4", "sha": "1a2b3c4d5e6f"}""")!!
        assertEquals(WatchRelease(112, "12-1a2b3c4", "1a2b3c4d5e6f"), r)
        assertNull(r.watchSha256)
        assertNull(r.watchSize)
        // what the integration step adds; the hash is kept lower-case
        val w = Release.parse("""{"versionCode":113,"watchSha256":"${abcSha.uppercase()}","watchSize":25722553}""")!!
        assertEquals(abcSha, w.watchSha256)
        assertEquals(25_722_553L, w.watchSize)
        assertEquals(WatchRelease(7), Release.parse("""{"versionCode":"7","extra":true}"""))
        assertNull("a malformed hash is no hash", Release.parse("""{"versionCode":7,"watchSha256":"abc"}""")!!.watchSha256)
        assertNull(Release.parse("""{"versionCode":7,"watchSize":0}""")!!.watchSize)
        assertNull(Release.parse("""{"versionName":"x"}"""))
        assertNull(Release.parse("""{"versionCode":0}"""))
        assertNull(Release.parse("""{"versionCode":99999999999}"""))
        assertNull(Release.parse("<html>Not Found</html>"))
        assertNull(Release.parse(""))
        assertNull(Release.parse(null))
        assertTrue(Release.VERSION_URL.endsWith("/Purple-Palm/Bird-Air-Tweaking/releases/download/android-latest/version.json"))
        assertTrue(Release.WATCH_APK_URL.endsWith("/releases/download/android-latest/bird-air-watch.apk"))
    }

    @Test fun newerByVersionCodeOnly() {
        val r = WatchRelease(106, "6-abc")
        assertTrue(Release.isNewer(r, 105))
        assertTrue("a local build (code 1) sees the release", Release.isNewer(r, 1))
        assertFalse(Release.isNewer(r, 106))
        assertFalse(Release.isNewer(r, 107))
        assertFalse(Release.isNewer(null, 1))
    }

    @Test fun checkOnStartAndDaily() {
        val h = 3600_000L
        assertTrue("never checked", Release.checkDue(0, 100 * h, atStart = false))
        assertTrue("at start, last check an hour ago", Release.checkDue(99 * h, 100 * h, atStart = true))
        assertFalse("reopened a minute later", Release.checkDue(100 * h - 60_000, 100 * h, atStart = true))
        assertFalse("running, checked 23 h ago", Release.checkDue(77 * h, 100 * h, atStart = false))
        assertTrue("running, a day passed", Release.checkDue(76 * h, 100 * h, atStart = false))
        assertTrue("clock went back", Release.checkDue(101 * h, 100 * h, atStart = false))
    }

    @Test fun sha256() {
        assertEquals(abcSha, Sha256.of("abc".byteInputStream()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.of(ByteArray(0).inputStream()))
        val f = File.createTempFile("apk", ".part")
        try {
            // more than one buffer
            f.writeBytes(ByteArray(200_000) { (it % 251).toByte() })
            assertEquals(Sha256.of(f.readBytes().inputStream()), Sha256.of(f))
        } finally { f.delete() }
        assertTrue(Sha256.matches(abcSha.uppercase(), abcSha))
        assertTrue("nothing published: nothing to compare", Sha256.matches(null, abcSha))
        assertFalse(Sha256.matches(abcSha, abcSha.replace('b', 'c')))
        assertFalse("no hash computed", Sha256.matches(abcSha, null))
    }

    @Test fun downloadChecks() {
        val r = WatchRelease(106, watchSha256 = abcSha, watchSize = 3)
        assertNull(ApkCheck.file(r, 3, abcSha))
        assertTrue(ApkCheck.file(r, 2, abcSha)!!.contains("размер"))
        assertTrue(ApkCheck.file(r, 3, "0".repeat(64))!!.contains("SHA-256"))
        assertEquals("файл пустой", ApkCheck.file(WatchRelease(106), 0, null))
        assertNull("an old version.json: size and hash unknown", ApkCheck.file(WatchRelease(106), 25_000_000, null))
        val pkg = "io.github.purplepalm.birdair"
        assertNull(ApkCheck.apk(pkg, 106, pkg, 105))
        assertEquals("это не APK", ApkCheck.apk(null, null, pkg, 105))
        assertTrue(ApkCheck.apk("com.other", 106, pkg, 105)!!.contains("не Bird Air"))
        assertTrue(ApkCheck.apk(pkg, 105, pkg, 105)!!.contains("не новее"))
    }

    @Test fun redirectsStayOnGitHubOverHttps() {
        val from = Release.WATCH_APK_URL
        val s3 = "https://objects.githubusercontent.com/github-production-release-asset-2e65be/1/2?X-Amz-Signature=abc"
        assertEquals(s3, Redirects.next(from, s3))
        assertNotNull(Redirects.next(from, "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r"))
        assertEquals("https://github.com/Purple-Palm/Bird-Air-Tweaking/releases/download/x.apk", Redirects.next(from, "../x.apk"))
        assertNull("plain http", Redirects.next(from, "http://objects.githubusercontent.com/a"))
        assertNull(Redirects.next(from, "https://evil.example/bird-air-watch.apk"))
        assertNull(Redirects.next(from, "https://githubusercontent.com.evil.example/a"))
        assertNull(Redirects.next(from, null))
        assertNull(Redirects.next(from, " "))
        assertFalse(Redirects.allowedHost(null))
        assertTrue(Redirects.allowedHost("GitHub.com"))
    }

    @Test fun resumeRanges() {
        assertEquals(100L, Ranges.start("bytes 100-199/200"))
        assertEquals(200L, Ranges.total("bytes 100-199/200"))
        assertNull(Ranges.total("bytes 100-199/*"))
        assertNull(Ranges.start(null))
        assertEquals(200L, Ranges.total(206, 100, 100, "bytes 100-199/200"))
        assertEquals("no Content-Range total: offset + length", 200L, Ranges.total(206, 100, 100, "bytes 100-199/*"))
        assertEquals(500L, Ranges.total(200, 0, 500, null))
        assertNull(Ranges.total(200, 0, -1, null))
    }

    @Test fun networkErrorsInRussian() {
        assertEquals("нет интернета (не нашёлся github.com)", NetError.text(java.net.UnknownHostException("Unable to resolve host")))
        assertEquals("сеть не отвечает", NetError.text(java.net.SocketTimeoutException("Read timed out")))
        assertEquals("нет соединения", NetError.text(java.net.ConnectException("refused")))
        assertEquals("сбой защищённого соединения", NetError.text(javax.net.ssl.SSLHandshakeException("x")))
        assertEquals("ответ сервера 503", NetError.text(java.io.IOException("ответ сервера 503")))
        assertEquals("нет сети", NetError.text(java.io.IOException()))
    }

    @Test fun speed() {
        val r = Rate(windowMs = 3000)
        assertNull(r.bytesPerS())
        r.add(0, 0)
        r.add(100, 10_000)
        assertNull("too short to say", r.bytesPerS())
        r.add(1000, 100_000)
        assertEquals(100_000.0, r.bytesPerS()!!, 1e-9)
        // the window forgets the start
        r.add(10_000, 100_000)
        r.add(11_000, 200_000)
        assertEquals(100_000.0, r.bytesPerS()!!, 1e-9)
    }

    @Test fun statusesAreTheSdks() {
        assertEquals(PackageInstaller.STATUS_PENDING_USER_ACTION, InstallStatus.PENDING_USER_ACTION)
        assertEquals(PackageInstaller.STATUS_SUCCESS, InstallStatus.SUCCESS)
        assertEquals(PackageInstaller.STATUS_FAILURE, InstallStatus.FAILURE)
        assertEquals(PackageInstaller.STATUS_FAILURE_BLOCKED, InstallStatus.FAILURE_BLOCKED)
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, InstallStatus.FAILURE_ABORTED)
        assertEquals(PackageInstaller.STATUS_FAILURE_INVALID, InstallStatus.FAILURE_INVALID)
        assertEquals(PackageInstaller.STATUS_FAILURE_CONFLICT, InstallStatus.FAILURE_CONFLICT)
        assertEquals(PackageInstaller.STATUS_FAILURE_STORAGE, InstallStatus.FAILURE_STORAGE)
        assertEquals(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, InstallStatus.FAILURE_INCOMPATIBLE)
        assertEquals(PackageInstaller.STATUS_FAILURE_TIMEOUT, InstallStatus.FAILURE_TIMEOUT)
    }

    @Test fun statusMapping() {
        fun kind(s: Int) = InstallStatus.of(s, null).kind
        assertEquals(InstallStatus.Kind.CONFIRM, kind(InstallStatus.PENDING_USER_ACTION))
        assertNull(InstallStatus.of(InstallStatus.PENDING_USER_ACTION, null).text)
        assertEquals(InstallStatus.Kind.DONE, kind(InstallStatus.SUCCESS))
        assertFalse(InstallStatus.of(InstallStatus.SUCCESS, null).err)
        assertEquals(InstallStatus.Kind.CANCELLED, kind(InstallStatus.FAILURE_ABORTED))
        assertEquals("Установка отменена.", InstallStatus.of(InstallStatus.FAILURE_ABORTED, null).text)
        assertEquals(InstallStatus.Kind.REDOWNLOAD, kind(InstallStatus.FAILURE_INVALID))
        assertEquals(InstallStatus.Kind.REDOWNLOAD, kind(InstallStatus.FAILURE_INCOMPATIBLE))
        for (s in listOf(InstallStatus.FAILURE, InstallStatus.FAILURE_BLOCKED, InstallStatus.FAILURE_CONFLICT, InstallStatus.FAILURE_STORAGE, InstallStatus.FAILURE_TIMEOUT, 42)) {
            val o = InstallStatus.of(s, null)
            assertEquals("status $s", InstallStatus.Kind.RETRY, o.kind)
            assertTrue(o.err)
            assertTrue("Russian text for $s", o.text!!.any { it in 'а'..'я' })
        }
        assertTrue(InstallStatus.of(InstallStatus.FAILURE_STORAGE, null).text!!.contains("мало места"))
        assertTrue(InstallStatus.of(42, null).text!!.contains("42"))
        // the system's reason rides along
        assertTrue(InstallStatus.of(InstallStatus.FAILURE, "INSTALL_FAILED_X: boom").text!!.contains("(INSTALL_FAILED_X: boom)"))
        assertTrue(InstallStatus.of(InstallStatus.FAILURE_BLOCKED, null).text!!.contains("Bugjaeger"))
    }

    @Test fun chipTexts() {
        val r = WatchRelease(106, watchSize = 25_722_553)
        // nothing known yet / up to date
        assertEquals(UpdateChip("Проверить обновления", null, UpdateAction.CHECK), UpdateView.of(UpdateState(), 105))
        assertEquals("стоит последняя: v105", UpdateView.of(UpdateState(latest = WatchRelease(105)), 105).secondary)
        assertEquals(UpdateAction.CHECK, UpdateView.of(UpdateState(latest = r), 106).action)
        // newer in the release
        val avail = UpdateView.of(UpdateState(latest = r), 105)
        assertEquals("Обновить часы (v106)", avail.label)
        assertEquals(UpdateAction.DOWNLOAD, avail.action)
        assertEquals("24,5 МБ · быстрее по Wi-Fi", plain(avail.secondary))
        assertEquals("быстрее по Wi-Fi", UpdateView.of(UpdateState(latest = WatchRelease(106)), 105).secondary)
        val resume = UpdateView.of(UpdateState(latest = r, got = 12 * 1_048_576L, total = r.watchSize, err = true, message = "Не скачалось"), 105)
        assertEquals("Повторить (v106)", resume.label)
        assertEquals("докачаю с 12,0 из 24,5 МБ · быстрее по Wi-Fi", plain(resume.secondary))
        // downloading
        val d = UpdateView.of(UpdateState(latest = r, phase = UpdatePhase.DOWNLOADING, got = 6_430_638, total = r.watchSize, bytesPerS = 1.5 * 1_048_576, wifi = true), 105)
        assertEquals("Скачиваю 25 %", plain(d.label))
        assertEquals("6,1 из 24,5 МБ · 1,5 МБ/с\nпо Wi-Fi", plain(d.secondary))
        assertEquals(0.25f, d.progress!!, 0.001f)
        assertTrue(d.cancel)
        assertNull("on Wi-Fi: no hint", d.hint)
        assertEquals(UpdateAction.NONE, d.action)
        val bt = UpdateView.of(UpdateState(latest = WatchRelease(106), phase = UpdatePhase.DOWNLOADING, got = 1_048_576, bytesPerS = 40_960.0, wifi = false), 105)
        assertEquals("Скачиваю v106", bt.label)
        assertEquals("1,0 МБ · 40 КБ/с\nчерез телефон", plain(bt.secondary))
        assertTrue(bt.hint!!.contains("быстрее по Wi-Fi", ignoreCase = true))
        assertTrue(UpdateView.of(UpdateState(latest = r, phase = UpdatePhase.DOWNLOADING), 105).secondary!!.endsWith("включаю Wi-Fi…"))
        // downloaded: a tap installs, or first the permission
        val ready = UpdateState(latest = r, phase = UpdatePhase.READY, readyCode = 106)
        assertEquals(UpdateChip("Установить v106", "Bird Air закроется — открой снова", UpdateAction.INSTALL, primary = true), UpdateView.of(ready, 105))
        assertEquals(UpdateAction.ALLOW, UpdateView.of(ready.copy(needsPermission = true), 105).action)
        assertEquals("Разрешить установку", UpdateView.of(ready.copy(needsPermission = true), 105).label)
        assertEquals(UpdateAction.NONE, UpdateView.of(ready.copy(phase = UpdatePhase.INSTALLING), 105).action)
        assertEquals(UpdateAction.CONFIRM, UpdateView.of(ready.copy(phase = UpdatePhase.CONFIRM), 105).action)
        assertEquals(UpdateAction.NONE, UpdateView.of(UpdateState(phase = UpdatePhase.CHECKING), 105).action)
    }
}
