package io.github.purplepalm.birdair.phone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GitHubApiTest {
    private val cfg = GhConfig()
    private val token = "abc123_TEST_token"

    @Test fun putRequestLooksLikeThePages() {
        val bytes = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 1, 2, 3, -1)
        val path = GitHubApi.path(cfg.folder, GitHubApi.fileName("20261015-101500-log"))
        assertEquals("logs/20261015-101500-log-app.json.gz", path)
        val r = GitHubApi.put(cfg, token, path, bytes, GitHubApi.commitMessage("20261015-101500-log", 1234), null)
        assertEquals("PUT", r.method)
        assertEquals("https://api.github.com/repos/Purple-Palm/Bird-Air-Tweaking/contents/logs/20261015-101500-log-app.json.gz", r.url)
        assertEquals("Bearer $token", r.headers["Authorization"])
        assertEquals("application/vnd.github+json", r.headers["Accept"])
        assertEquals("2022-11-28", r.headers["X-GitHub-Api-Version"])
        assertTrue(r.headers["Content-Type"]!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(r.body!!).jsonObject
        assertEquals("Лог 20261015-101500-log: 1234 записей (приложение)", body["message"]!!.jsonPrimitive.content)
        assertEquals("main", body["branch"]!!.jsonPrimitive.content)
        assertArrayEquals(bytes, Base64.getDecoder().decode(body["content"]!!.jsonPrimitive.content))
        assertFalse("no sha for a new file", body.containsKey("sha"))
        // the token goes in the header only
        assertFalse(r.body.contains(token))
        assertFalse(r.url.contains(token))
    }

    @Test fun overwriteCarriesTheSha() {
        val r = GitHubApi.put(cfg.copy(branch = " dev "), token, "logs/x-app.json.gz", byteArrayOf(1), "m", "0123abcd")
        val body = Json.parseToJsonElement(r.body!!).jsonObject
        assertEquals("0123abcd", body["sha"]!!.jsonPrimitive.content)
        assertEquals("dev", body["branch"]!!.jsonPrimitive.content)
        assertFalse(Json.parseToJsonElement(GitHubApi.put(cfg, token, "p", byteArrayOf(1), "m", "").body!!).jsonObject.containsKey("sha"))
    }

    @Test fun pathsAreEncodedPerSegment() {
        assertEquals("logs/a.json.gz", GitHubApi.path("/logs/", "a.json.gz"))
        assertEquals("a.json.gz", GitHubApi.path("  ", "a.json.gz"))
        assertEquals("deep/er/a.json.gz", GitHubApi.path("deep/er", "a.json.gz"))
        val u = GitHubApi.contentsUrl(GhConfig(owner = "me", repo = "my repo"), "мои логи/a b.json.gz")
        assertEquals("https://api.github.com/repos/me/my%20repo/contents/%D0%BC%D0%BE%D0%B8%20%D0%BB%D0%BE%D0%B3%D0%B8/a%20b.json.gz", u)
        assertEquals("x-2-app.json.gz", GitHubApi.fileName("x", 2))
        assertEquals("https://api.github.com/repos/Purple-Palm/Bird-Air-Tweaking/branches/main", GitHubApi.getBranch(cfg, token).url)
        assertNull(GitHubApi.getRepo(cfg, token).body)
    }

    @Test fun staleShaIsFetchedAgain() {
        val r = GitHubApi.getContents(cfg.copy(branch = "dev"), token, "logs/x-app.json.gz")
        assertEquals("GET", r.method)
        assertEquals("https://api.github.com/repos/Purple-Palm/Bird-Air-Tweaking/contents/logs/x-app.json.gz?ref=dev", r.url)
        assertNull(r.body)
        assertEquals("3d21ec53a331a6f037a91c368710b99387d012c1", GitHubApi.parseSha("""{"name":"x-app.json.gz","sha":"3d21ec53a331a6f037a91c368710b99387d012c1","size":2048000,"content":"","encoding":"none"}"""))
        assertNull(GitHubApi.parseSha("""[{"sha":"1"}]"""))
        assertNull(GitHubApi.parseSha("<html>"))
        assertNull(GitHubApi.parseSha(null))
        assertTrue(GitHubApi.isStaleSha(409))
        assertTrue(GitHubApi.isStaleSha(422))
        assertFalse(GitHubApi.isStaleSha(403))
    }

    @Test fun dropUploadsAreSpacedOut() {
        val was = ScooterRepo.gh.value to ScooterRepo.hasToken.value
        try {
            ScooterRepo.gh.value = GhConfig(auto = true)
            ScooterRepo.hasToken.value = true
            val now = 10_000_000L
            val s = Session(now - 600_000, { 0L }).apply { rx.set(5) }
            val p = s.part
            assertFalse("an empty part has nothing to send", LogStore.autoDue(s, p, force = true, nowMs = now))
            s.recorder.sys("Подключено", 0)
            assertTrue(LogStore.autoDue(s, p, force = true, minGapMs = LogStore.DROP_EVERY_MS, nowMs = now))
            p.ghTryAt = now - 30_000
            assertFalse("a flapping link: not again within a minute", LogStore.autoDue(s, p, force = true, minGapMs = LogStore.DROP_EVERY_MS, nowMs = now))
            assertTrue("«Отключить» sends anyway", LogStore.autoDue(s, p, force = true, nowMs = now))
            p.ghTryAt = now - LogStore.DROP_EVERY_MS
            assertTrue(LogStore.autoDue(s, p, force = true, minGapMs = LogStore.DROP_EVERY_MS, nowMs = now))
            // on schedule: 5 min after the part started, then 5 min after the last upload
            assertTrue(LogStore.autoDue(s, p, force = false, nowMs = now))
            assertFalse(LogStore.autoDue(s, p, force = false, nowMs = now - 400_000))
            ScooterRepo.gh.value = GhConfig(auto = false)
            assertFalse(LogStore.autoDue(s, p, force = true, nowMs = now))
        } finally {
            ScooterRepo.gh.value = was.first
            ScooterRepo.hasToken.value = was.second
        }
    }

    @Test fun errorsInRussian() {
        val e = { s: Int, m: String? -> GitHubApi.errorText(s, m, cfg) }
        assertTrue(e(0, null).startsWith("Нет связи с api.github.com"))
        assertTrue(e(401, "Bad credentials").startsWith("Токен не принят (401)"))
        assertTrue(e(403, "Resource not accessible by personal access token").contains("Contents: Read and write на Purple-Palm/Bird-Air-Tweaking"))
        assertTrue(e(403, "API rate limit exceeded").startsWith("GitHub временно ограничил"))
        assertTrue(e(404, "Not Found").startsWith("Репозиторий Purple-Palm/Bird-Air-Tweaking не найден (404)"))
        assertTrue(e(404, "Branch main not found").startsWith("Ветка «main» не найдена"))
        assertTrue(e(422, "Branch does not exist").startsWith("Ветка «main» не найдена"))
        assertTrue(e(409, "Repository rule violations found\nChanges must be made through a pull request.").contains("защищена правилами"))
        assertTrue(e(409, "is at abc but expected def").startsWith("Конфликт (409): ветка изменилась"))
        assertTrue(e(422, "Invalid request.\n\n\"sha\" wasn't supplied.").startsWith("Конфликт (422)"))
        assertTrue(GitHubApi.isShaConflict(422, "\"sha\" wasn't supplied."))
        assertFalse(GitHubApi.isShaConflict(409, "sha"))
        assertEquals("GitHub отклонил запись (422): content is not valid Base64.", e(422, "content is not valid Base64"))
        assertEquals("Ошибка GitHub 500.", e(500, ""))
    }

    @Test fun responses() {
        val body = """{"content":{"name":"x","path":"logs/x","sha":"95b966ae","html_url":"https://github.com/o/r/blob/main/logs/x"},"commit":{"sha":"c0ffee"}}"""
        assertEquals(GhPut("logs/x", "95b966ae", "https://github.com/o/r/blob/main/logs/x", commitSha = "c0ffee"), GitHubApi.parsePut("logs/x", body))
        assertEquals(GhPut("p", null, null), GitHubApi.parsePut("p", "not json"))
        // the commit, for «На GitHub · коммит 7c1e2a9» / «Открыть коммит»
        val withCommit = """{"content":{"sha":"95b966ae","html_url":"https://github.com/o/r/blob/main/logs/x"},""" +
            """"commit":{"sha":"7c1e2a9f00d","html_url":"https://github.com/o/r/commit/7c1e2a9f00d","message":"Лог"}}"""
        assertEquals(GhPut("logs/x", "95b966ae", "https://github.com/o/r/blob/main/logs/x", "7c1e2a9f00d", "https://github.com/o/r/commit/7c1e2a9f00d"),
            GitHubApi.parsePut("logs/x", withCommit))
        assertEquals(GhPut("p", null, null), GitHubApi.parsePut("p", null))
        assertEquals("Bad credentials", GitHubApi.message("""{"message":"Bad credentials","documentation_url":"x"}"""))
        assertEquals("", GitHubApi.message("<html>"))
    }

    @Test fun tokens() {
        assertEquals("abc_123", GitHubApi.cleanToken(" abc_​123\n"))
        assertTrue(GitHubApi.tokenOk("abc_123XYZ"))
        assertFalse(GitHubApi.tokenOk("abc-123"))
        assertFalse(GitHubApi.tokenOk("аbc"))   // Cyrillic а
        assertFalse(GitHubApi.tokenOk(""))
    }
}
