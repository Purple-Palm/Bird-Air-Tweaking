package io.github.purplepalm.birdair.phone

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One GATT operation at a time: Android drops (or refuses with "busy") an operation started before the
 * previous one's callback came. [run] starts an operation and suspends until its callback calls [complete]
 * with the same [Op], or until the timeout. One queue per connection: callbacks of a closed connection
 * cannot complete operations of the next one. Thread-safe; callbacks may come from any thread.
 */
class GattQueue {
    enum class Op { DISCOVER, WRITE_DESC, WRITE_CHAR }

    /** [status]: the callback's GATT status (0 = success) or one of [TIMEOUT], [NOT_STARTED], [CANCELLED], [THREW]. */
    data class Result(val status: Int) {
        val ok: Boolean get() = status == 0
        fun describe(): String = when (status) {
            0 -> "ок"
            TIMEOUT -> "нет ответа"
            NOT_STARTED -> "Android не принял операцию"
            CANCELLED -> "связь прервалась"
            THREW -> "сбой Bluetooth"
            else -> "статус $status"
        }
    }

    private class Pending(val op: Op, val done: CompletableDeferred<Result>)

    private val mutex = Mutex()
    private val lock = Any()
    private var pending: Pending? = null
    @Volatile private var closed = false

    /**
     * Starts [start] (returns false when Android refused it) once the previous operation has finished. A throw
     * from [start] never leaves here: without permission it is [NOT_STARTED]; anything else (a dead Bluetooth
     * process: DeadObjectException as a RuntimeException) is [THREW], and the connection is gone.
     */
    suspend fun run(op: Op, timeoutMs: Long, start: () -> Boolean): Result = mutex.withLock {
        if (closed) return@withLock Result(CANCELLED)
        val p = Pending(op, CompletableDeferred())
        synchronized(lock) { pending = p }
        try {
            val started = try { start() } catch (e: SecurityException) { false } catch (e: Exception) { return@withLock Result(THREW) }
            if (!started) return@withLock Result(NOT_STARTED)
            withTimeoutOrNull(timeoutMs) { p.done.await() } ?: Result(TIMEOUT)
        } finally {
            synchronized(lock) { if (pending === p) pending = null }
        }
    }

    /** A GATT callback; ignored unless an operation of this kind is waiting. */
    fun complete(op: Op, status: Int): Boolean {
        val p = synchronized(lock) {
            val p = pending
            if (p == null || p.op != op) return false
            pending = null
            p
        }
        return p.done.complete(Result(status))
    }

    /** The link dropped: the waiting operation fails now, later ones fail at once. */
    fun close() {
        closed = true
        val p = synchronized(lock) { pending.also { pending = null } }
        p?.done?.complete(Result(CANCELLED))
    }

    companion object {
        const val TIMEOUT = -1
        const val NOT_STARTED = -2
        const val CANCELLED = -3
        const val THREW = -4
    }
}
