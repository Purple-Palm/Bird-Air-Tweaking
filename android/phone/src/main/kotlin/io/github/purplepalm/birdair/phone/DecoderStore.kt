package io.github.purplepalm.birdair.phone

import android.content.Context
import io.github.purplepalm.birdair.core.DecoderSpec
import io.github.purplepalm.birdair.core.Decoders
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A decoder and where it came from. */
class DecoderCandidate(val spec: DecoderSpec, val source: String, val text: String)

/** Which decoder to use: the highest version among the bundled one, the cached one and the site's. */
object DecoderPick {
    const val BUNDLED = "в приложении"
    const val CACHED = "сохранённый"
    const val SITE = "с сайта"

    /** A usable decoder: our format with at least one field. */
    fun usable(d: DecoderSpec?): Boolean = d != null && d.format == Decoders.FORMAT && d.fields.isNotEmpty()

    fun parse(text: String?, source: String): DecoderCandidate? {
        val d = text?.let { Decoders.parseOrNull(it) }
        return if (usable(d)) DecoderCandidate(d!!, source, text) else null
    }

    /** Highest version wins; on a tie the earlier candidate stays. */
    fun best(cands: List<DecoderCandidate?>): DecoderCandidate? {
        var best: DecoderCandidate? = null
        for (c in cands) if (c != null && (best == null || c.spec.version > best.spec.version)) best = c
        return best
    }
}

/** Bundled asset (the repo's decoders/current.json at build time), the cache in filesDir, the site's copy. */
object DecoderStore {
    const val URL = "https://purple-palm.github.io/Bird-Air-Tweaking/decoders/current.json"
    private const val ASSET = "decoder/current.json"
    private const val TIMEOUT_MS = 5000

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "decoder/current.json")

    fun bundled(ctx: Context): DecoderCandidate? = try {
        DecoderPick.parse(ctx.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }, DecoderPick.BUNDLED)
    } catch (e: IOException) { null }

    fun cached(ctx: Context): DecoderCandidate? = try {
        val f = cacheFile(ctx)
        if (f.isFile) DecoderPick.parse(f.readText(), DecoderPick.CACHED) else null
    } catch (e: IOException) { null }

    /** Startup choice without the network. */
    fun local(ctx: Context): DecoderCandidate? = DecoderPick.best(listOf(bundled(ctx), cached(ctx)))

    /** The site's decoder (5 s timeout), null when offline or not a decoder. Blocking: call on IO. */
    fun fetch(): DecoderCandidate? = try {
        val c = URL(URL).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache")
            if (c.responseCode == 200) DecoderPick.parse(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }, DecoderPick.SITE) else null
        } finally { c.disconnect() }
    } catch (e: IOException) { null }

    fun save(ctx: Context, c: DecoderCandidate) {
        try {
            val f = cacheFile(ctx)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(c.text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: IOException) { /* the bundled one still works */ }
    }
}
