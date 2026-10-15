package io.github.purplepalm.birdair.phone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/** Where logs go: the page's GitHub settings (bal.gh). The token is kept apart and never in here. */
data class GhConfig(
    val owner: String = "Purple-Palm",
    val repo: String = "Bird-Air-Tweaking",
    val branch: String = "main",
    val folder: String = "logs",
    val auto: Boolean = false,
) {
    val dest: String get() = "$owner/$repo/$branch/$folder"
    val complete: Boolean get() = owner.isNotBlank() && repo.isNotBlank() && branch.isNotBlank()
}

/** One HTTP request to the GitHub REST API, built without touching the network (unit-tested). */
class GhRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

/** What a PUT gave back: the blob sha (needed to overwrite the file next time) and the page link. */
data class GhPut(val path: String, val sha: String?, val htmlUrl: String?)

class GhError(message: String, val status: Int, val ghMessage: String) : Exception(message)

/** The contents API as the page uses it (assets/app.js, «GitHub upload»). */
object GitHubApi {
    const val API = "https://api.github.com"
    private val JSON = Json { ignoreUnknownKeys = true }

    /** Pasted tokens sometimes carry spaces or invisible characters. */
    fun cleanToken(t: String?): String = (t ?: "").replace(Regex("[\\s\\u200B-\\u200D\\u2060\\uFEFF]"), "")
    fun tokenOk(t: String): Boolean = Regex("[A-Za-z0-9_]+").matches(t)

    /** File name of a recording in the repo: <base>-app.json.gz, <base>-2-app.json.gz when that one is taken. */
    fun fileName(base: String, attempt: Int = 1): String = base + (if (attempt > 1) "-$attempt" else "") + "-app.json.gz"

    /** folder/name, slashes around the folder trimmed. */
    fun path(folder: String, name: String): String {
        val f = folder.trim().trim('/')
        return if (f.isEmpty()) name else "$f/$name"
    }

    fun commitMessage(base: String, entries: Int): String = "Лог $base: $entries записей (приложение)"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun repoUrl(cfg: GhConfig): String = API + "/repos/" + enc(cfg.owner.trim()) + "/" + enc(cfg.repo.trim())

    fun contentsUrl(cfg: GhConfig, path: String): String = repoUrl(cfg) + "/contents/" + path.split('/').joinToString("/") { enc(it) }

    fun headers(token: String, json: Boolean): Map<String, String> = buildMap {
        put("Accept", "application/vnd.github+json")
        put("Authorization", "Bearer $token")
        put("X-GitHub-Api-Version", "2022-11-28")
        put("User-Agent", "bird-air-lab-android")
        if (json) put("Content-Type", "application/json; charset=utf-8")
    }

    /** PUT /repos/{o}/{r}/contents/{path}: base64 content, branch, message, and [sha] when overwriting. */
    fun put(cfg: GhConfig, token: String, path: String, content: ByteArray, message: String, sha: String?): GhRequest {
        val body = JsonObject(buildMap {
            put("message", JsonPrimitive(message))
            put("content", JsonPrimitive(Base64.getEncoder().encodeToString(content)))
            put("branch", JsonPrimitive(cfg.branch.trim()))
            if (!sha.isNullOrEmpty()) put("sha", JsonPrimitive(sha))
        })
        return GhRequest("PUT", contentsUrl(cfg, path), headers(token, true), body.toString())
    }

    fun getRepo(cfg: GhConfig, token: String) = GhRequest("GET", repoUrl(cfg), headers(token, false), null)

    fun getBranch(cfg: GhConfig, token: String) =
        GhRequest("GET", repoUrl(cfg) + "/branches/" + enc(cfg.branch.trim()), headers(token, false), null)

    /** sha and html_url of "content" in a PUT response. */
    fun parsePut(path: String, body: String?): GhPut {
        val c = try { body?.let { JSON.parseToJsonElement(it).jsonObject["content"]?.jsonObject } } catch (e: Exception) { null }
        return GhPut(path, c?.get("sha")?.jsonPrimitive?.contentOrNull, c?.get("html_url")?.jsonPrimitive?.contentOrNull)
    }

    /** "message" of an error response. */
    fun message(body: String?): String = try {
        body?.let { JSON.parseToJsonElement(it).jsonObject["message"]?.jsonPrimitive?.contentOrNull } ?: ""
    } catch (e: Exception) { "" }

    /** The page's ghError(): a Russian explanation for a failed request; [status] 0 = no network. */
    fun errorText(status: Int, message: String?, cfg: GhConfig): String {
        val m = message ?: ""
        val repo = cfg.owner + "/" + cfg.repo
        val branch = Regex("branch", RegexOption.IGNORE_CASE).containsMatchIn(m)
        return when {
            status == 0 -> "Нет связи с api.github.com. Проверь интернет и нажми ещё раз."
            (status == 404 || status == 422) && branch -> "Ветка «${cfg.branch}» не найдена в $repo ($status). Проверь ветку в настройках GitHub."
            status == 401 -> "Токен не принят (401): он неверный, отозван или истёк. Создай новый и вставь в настройках GitHub."
            status == 403 && Regex("rate limit", RegexOption.IGNORE_CASE).containsMatchIn(m) -> "GitHub временно ограничил запросы (403). Подожди пару минут."
            status == 403 -> "Доступ запрещён (403): токену не выдано право Contents: Read and write на $repo."
            status == 404 -> "Репозиторий $repo не найден (404). Проверь owner и repo; ещё 404 бывает, когда токен не допущен к этому репозиторию."
            status == 409 && Regex("rule|protect|pull request", RegexOption.IGNORE_CASE).containsMatchIn(m) ->
                "Ветка «${cfg.branch}» защищена правилами репозитория (409): ${m.lineSequence().first()} Выбери другую ветку в настройках GitHub."
            status == 409 && (m.isEmpty() || Regex("expected", RegexOption.IGNORE_CASE).containsMatchIn(m)) ->
                "Конфликт (409): ветка изменилась во время записи. Отправлю ещё раз позже."
            status == 409 -> "Конфликт (409): $m."
            status == 422 && isShaConflict(status, m) -> "Конфликт (422): такой файл уже есть в репозитории."
            status == 422 -> "GitHub отклонил запись (422): ${m.ifEmpty { "неверный запрос" }}."
            else -> "Ошибка GitHub $status" + (if (m.isNotEmpty()) ": $m" else "") + "."
        }
    }

    /** 422 "sha wasn't supplied": the file exists and we did not know its sha. */
    fun isShaConflict(status: Int, message: String): Boolean = status == 422 && Regex("sha", RegexOption.IGNORE_CASE).containsMatchIn(message)
}

/** Sends a [GhRequest] with HttpURLConnection. Blocking: call on Dispatchers.IO. */
object GitHubHttp {
    class Response(val status: Int, val body: String?)

    /** Throws [IOException] when there is no network; HTTP errors come back as a [Response]. */
    fun send(r: GhRequest): Response {
        val c = URL(r.url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = r.method
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.useCaches = false
            for ((k, v) in r.headers) c.setRequestProperty(k, v)
            if (r.body != null) {
                val b = r.body.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setFixedLengthStreamingMode(b.size)
                c.outputStream.use { it.write(b) }
            }
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }
            return Response(status, body)
        } finally {
            c.disconnect()
        }
    }

    /** [send], then a [GhError] with the Russian text unless 2xx. */
    fun call(r: GhRequest, cfg: GhConfig): Response {
        val resp = try { send(r) } catch (e: IOException) { throw GhError(GitHubApi.errorText(0, null, cfg), 0, "") }
        if (resp.status !in 200..299) {
            val m = GitHubApi.message(resp.body)
            throw GhError(GitHubApi.errorText(resp.status, m, cfg), resp.status, m)
        }
        return resp
    }
}
