package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Commands

/** Reconnect delays after an unintended disconnect: 1, 2, 4, 8, then 10 s for good. */
object Backoff {
    private val STEPS = longArrayOf(1000, 2000, 4000, 8000, 10_000)

    /** [attempt] 0 is the first retry after a drop. */
    fun delayMs(attempt: Int): Long = STEPS[attempt.coerceIn(0, STEPS.size - 1)]
}

/**
 * «Живые данные» pacing, the page's livePulse(): +SVGT=? every [Commands.PULSE_MS]; a pulse that did not go
 * out is retried after [Commands.FAIL_RETRY_MS]. The first failure of a run and the recovery go into the log.
 */
class PulseState {
    class Step(val delayMs: Long, val sys: String? = null, val err: Boolean = false)

    var failing = false
        private set

    fun after(ok: Boolean): Step {
        if (ok) {
            val was = failing
            failing = false
            return Step(Commands.PULSE_MS, if (was) RECOVERED else null)
        }
        val first = !failing
        failing = true
        return Step(Commands.FAIL_RETRY_MS, if (first) FAILED else null, err = first)
    }

    companion object {
        const val FAILED = "Живые данные: запрос +SVGT=? не ушёл — повторяю"
        const val RECOVERED = "Живые данные: запросы снова уходят"
    }
}
