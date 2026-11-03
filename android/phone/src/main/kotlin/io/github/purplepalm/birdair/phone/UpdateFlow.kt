package io.github.purplepalm.birdair.phone

/** The in-app update as one state machine: nothing → checking → available → downloading → ready (the APK kept) → the system installer. */
enum class UpdPhase { NONE, CHECKING, AVAILABLE, DOWNLOADING, READY }

/** Pure rules of the updater: what state it is in, what the one button says, when the installer may open. */
object UpdateFlow {
    /** Downloading first; a kept APK newer than this build and not older than the release → READY; then checking; a newer release → AVAILABLE. */
    fun phase(u: UpdateState, installed: Int): UpdPhase = when {
        u.downloading -> UpdPhase.DOWNLOADING
        keep(u.readyCode, installed, u.latest) -> UpdPhase.READY
        u.checking -> UpdPhase.CHECKING
        Versions.isNewer(u.latest, installed) -> UpdPhase.AVAILABLE
        else -> UpdPhase.NONE
    }

    /** Installing closes the app and drops the link: never while moving. */
    fun installBlock(moving: Boolean): String? = if (moving) INSTALL_BLOCKED else null
    const val INSTALL_BLOCKED = "Ты едешь — установка недоступна"

    /** The installer opens by itself after a download only when the owner tapped for it and no recording runs. */
    fun openWhenDone(byTap: Boolean, running: Boolean): Boolean = byTap && !running

    /** A downloaded APK of [apkCode] is worth keeping: newer than [installed] and not older than what the release says now. */
    fun keep(apkCode: Int?, installed: Int, latest: UpdateInfo?): Boolean =
        apkCode != null && apkCode > installed && apkCode >= (latest?.versionCode ?: 0)

    /**
     * The state once version.json was read ([info]; null: it could not be, the last good one stays): the release the
     * download is checked against (its size and SHA-256), when it was read; [manual] (the button) says what came out
     * even when nothing is new; a kept APK older than the release now goes (downloaded again).
     */
    fun checked(u: UpdateState, info: UpdateInfo?, installed: Int, manual: Boolean, nowMs: Long): UpdateState {
        val latest = info ?: u.latest
        val msg = when {
            info == null -> if (manual) "Не удалось прочитать version.json релиза android-latest: нет сети или CI его ещё не выложил." else u.message
            Versions.isNewer(info, installed) -> "Есть обновление: ${info.label()}."
            manual -> "Новее нет: v$installed — последняя сборка."
            else -> u.message
        }
        return u.copy(latest = latest, checkedAt = if (info != null) nowMs else u.checkedAt, checking = false, message = msg,
            err = info == null && manual, readyCode = u.readyCode?.takeIf { keep(it, installed, latest) })
    }

    /**
     * After the update went in (MY_PACKAGE_REPLACED): the saved scooter to connect to again, only if a recording ran
     * when the install began and the permission is there; else null. [at] ([Prefs.resumeAfterUpdateAt]): when an install
     * began with a recording running, moved along while it still ran ([attemptAt]), good for [RESUME_WINDOW_MS] after —
     * the installer kills the process as it goes in, and an attempt that never did (cancelled, the process killed before
     * it came back) must not start a recording for an update put on later by hand with nothing running; 0: none ran.
     * null: begun by v111, which kept nothing — its own rule, [wanted], this once.
     */
    fun resumeTo(at: Long?, wanted: Boolean, nowMs: Long, address: String?, canConnect: Boolean): String? {
        val recording = if (at == null) wanted else at > 0 && nowMs - at in 0..RESUME_WINDOW_MS
        return address?.takeIf { recording && canConnect }
    }

    /**
     * [Prefs.resumeAfterUpdateAt] at the service's periodic tick: while the recording runs, an install attempt's time
     * moves along with it — the system installer waits any time for «Обновить» (the phone in its mount, a call), and the
     * window counts from the last moment the recording ran, not from the tap. 0 (none) and null (v111's) stay.
     */
    fun attemptAt(at: Long?, running: Boolean, nowMs: Long): Long? = if (running && at != null && at > 0) nowMs else at

    /** How long after the recording last ran ([ScooterRepo.beforeInstall], then [attemptAt]) an update still brings the link back ([resumeTo]). */
    const val RESUME_WINDOW_MS = 15 * 60_000L

    /** 0…1 of the download, by bytes when the size is known. */
    fun fraction(u: UpdateState): Float? {
        val total = u.totalBytes ?: u.latest?.phoneSize
        return if (total != null && total > 0) (u.gotBytes.toFloat() / total).coerceIn(0f, 1f) else u.progress
    }

    /** The download in whole percent («63»). */
    fun percent(u: UpdateState): Int? = fraction(u)?.let { Math.round(it * 100) }

    /**
     * The one button: «Проверить обновления» / «Проверяю…» / «Обновить до v109» / «Скачиваю 63 %» / «Установить v109»
     * («Сохраняю лог…» while [UpdateState.saving]: the tap went through, the installer comes next).
     */
    fun button(phase: UpdPhase, latest: UpdateInfo?, u: UpdateState): String = when (phase) {
        UpdPhase.NONE -> "Проверить обновления"
        UpdPhase.CHECKING -> "Проверяю…"
        UpdPhase.AVAILABLE -> "Обновить до v" + (latest?.versionCode ?: "")
        UpdPhase.DOWNLOADING -> "Скачиваю" + (percent(u)?.let { " $it %" } ?: "…")
        UpdPhase.READY -> if (u.saving) SAVING else "Установить v" + (u.readyCode ?: latest?.versionCode ?: "")
    }
    const val SAVING = "Сохраняю лог…"

    /** «18,4 МБ» */
    fun mb(bytes: Long?): String? = bytes?.takeIf { it > 0 }?.let { Fmt.n(it / 1048576.0, 1) + " МБ" }
}
