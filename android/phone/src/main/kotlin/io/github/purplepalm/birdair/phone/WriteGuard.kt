package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Commands
import io.github.purplepalm.birdair.core.Uuids

/**
 * The gate in front of every GATT write the app makes (CLAUDE.md, «Жёсткие правила»): only FFE1 of
 * service FFE0, only the six whitelisted ASCII commands, never KEY? (AE 4B 45 59) and never an OTA/DFU
 * characteristic. [check] returns null when the write may go out, otherwise why not (Russian, for the log).
 */
object WriteGuard {
    const val KEY = "«KEY?» (AE 4B 45 59) — это мастер прошивки, на этом самокате он вызвал ошибку 37. Не отправляю."
    const val NOT_LISTED = "не из списка разрешённых команд (+SVGT=?, +MODE=1/2/3, +HLGT=0/1). Не отправляю."
    const val DANGER = "это характеристика обновления прошивки (OTA/DFU) — писать в неё нельзя."
    const val NOT_FFE1 = "писать можно только в характеристику FFE1 сервиса FFE0."

    /** [bytes] alone: KEY? anywhere, or anything that is not exactly one of the six commands. */
    fun checkPayload(bytes: ByteArray): String? = when {
        Commands.containsKey(bytes) -> KEY
        !Commands.isAllowed(bytes) -> NOT_LISTED
        else -> null
    }

    /** The whole write: payload, then the target. Unknown target (null) is refused too. */
    fun check(service: String?, char: String?, bytes: ByteArray): String? {
        checkPayload(bytes)?.let { return it }
        if (service == null || char == null) return NOT_FFE1
        if (Uuids.isDanger(service, char)) return DANGER
        if (Uuids.full(service) != Uuids.SERVICE || Uuids.full(char) != Uuids.CHAR) return NOT_FFE1
        // belt and braces: core's own last check must agree
        return if (Commands.canWrite(service, char, bytes)) null else NOT_LISTED
    }
}
