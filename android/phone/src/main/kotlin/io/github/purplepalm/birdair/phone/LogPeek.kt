package io.github.purplepalm.birdair.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * How many entries a saved log holds («Логи»: «… · 9806 записей»): [LogFiles.peek] streams the whole file once, so the
 * count is kept per name, size and time (a file saved again is read again). One file at a time, on IO.
 */
object LogPeek {
    private const val MAX = 64
    private val cache = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > MAX
    }
    private val lock = Mutex()

    private fun key(f: LocalLog) = f.name + "|" + f.size + "|" + f.mtime

    /** The count if [f] (these bytes) was read already. */
    fun cached(f: LocalLog): Int? = synchronized(cache) { cache[key(f)] }

    /** [f] in [dir]: its entry count; null when the file is gone or unreadable. */
    suspend fun entries(dir: File, f: LocalLog): Int? {
        cached(f)?.let { return it }
        return lock.withLock {
            withContext(Dispatchers.IO) {
                cached(f) ?: read(File(dir, f.name))?.also { n -> synchronized(cache) { cache[key(f)] = n } }
            }
        }
    }

    /** The entries of a .json.gz log; null when it cannot be read. */
    fun read(file: File): Int? = try {
        if (!file.isFile) null else GZIPInputStream(file.inputStream().buffered(1 shl 16)).use { LogFiles.peek(it).entries }
    } catch (e: IOException) { null }

    fun clear() = synchronized(cache) { cache.clear() }
}
