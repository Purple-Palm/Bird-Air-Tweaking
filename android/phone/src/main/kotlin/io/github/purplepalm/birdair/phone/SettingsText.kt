package io.github.purplepalm.birdair.phone

/** Настройки' words about this build and its update (pure): the version lines, the card's title, change and note. */
object UpdateCardText {
    /** «8-a41290d (v108)»; a build without a name just «v108». */
    fun version(name: String?, code: Int): String = if (name.isNullOrEmpty()) "v$code" else "$name (v$code)"

    /** «Bird Air 8-a41290d (v108)» */
    fun app(name: String, code: Int): String = "Bird Air " + version(name, code)

    /**
     * «В релизе android-latest: v109 (9-c3f19d2) · проверено 21.10 19:28»; the same build as this one: «v108 — она и стоит»;
     * nothing read yet: «ещё не проверено». [checked] the time of the last check, as shown.
     */
    fun release(latest: UpdateInfo?, installed: Int, checked: String?): String {
        val head = "В релизе android-latest: "
        if (latest == null) return head + "ещё не проверено"
        val v = "v${latest.versionCode}" + when {
            latest.versionCode == installed -> " — она и стоит"
            latest.versionName.isNotEmpty() -> " (${latest.versionName})"
            else -> ""
        }
        return head + v + (checked?.let { " · проверено $it" } ?: "")
    }

    /** The version the card is about: the kept APK once it is ready, else the release's. */
    fun code(phase: UpdPhase, u: UpdateState): Int? = if (phase == UpdPhase.READY) u.readyCode ?: u.latest?.versionCode else u.latest?.versionCode

    /** «Есть обновление: v109» / «Скачано: v109» */
    fun title(phase: UpdPhase, code: Int): String = (if (phase == UpdPhase.READY) "Скачано" else "Есть обновление") + ": v$code"

    /** «Сейчас 8-a41290d (v108) → 9-c3f19d2 (v109) · 18,4 МБ»; the release's name only when it is that version. */
    fun change(installedName: String, installed: Int, to: Int, latest: UpdateInfo?, bytes: Long?): String {
        val toName = latest?.takeIf { it.versionCode == to }?.versionName
        return "Сейчас " + version(installedName, installed) + " → " + version(toName, to) + (UpdateFlow.mb(bytes)?.let { " · $it" } ?: "")
    }

    /** The APK's size: from version.json, else what the download says. */
    fun size(u: UpdateState): Long? = u.latest?.phoneSize ?: u.totalBytes

    enum class Tone { TEXT, OK, WARN, CRIT }

    /** The card's last block: a line ([Tone.TEXT], [Tone.OK]) or a plate with a title and a text ([Tone.WARN], [Tone.CRIT]). */
    data class Note(val tone: Tone, val title: String, val text: String? = null)

    /**
     * What happens next. AVAILABLE: when it will be installed; DOWNLOADING: how far, and that the installer waits while a
     * recording runs; READY: moving — not now (the button is off), recording — the link drops, idle — the file is kept.
     */
    fun note(phase: UpdPhase, u: UpdateState, running: Boolean, moving: Boolean): Note? = when (phase) {
        UpdPhase.AVAILABLE -> Note(Tone.TEXT, when {
            moving -> "Скачаю сейчас — поставишь на стоянке."
            running -> "Скачаю сейчас; поставишь, когда будешь готов: связь оборвётся."
            else -> "Скачаю " + (UpdateFlow.mb(size(u))?.let { "$it " } ?: "") + "и сразу открою установщик."
        })
        UpdPhase.DOWNLOADING -> {
            val total = size(u)
            val got = Fmt.n(u.gotBytes / MB, 1)
            Note(Tone.TEXT, Versions.PHONE_APK + ": " + (if (total != null && total > 0) got + " из " + UpdateFlow.mb(total) else "$got МБ") +
                if (running) " · установщик сам не откроется, пока идёт запись" else "")
        }
        UpdPhase.READY -> when {
            moving -> Note(Tone.CRIT, UpdateFlow.INSTALL_BLOCKED,
                "Она закроет приложение и оборвёт связь с самокатом. Остановись: на стоянке появится плитка «Установить».")
            running -> Note(Tone.WARN, "Связь с самокатом оборвётся",
                "Установка закроет приложение. Лог и одометр сохраню, после установки служба переподключится сама.")
            else -> Note(Tone.OK, "Скачано (v${code(phase, u)}). Закрыл установщик? «Установить» возьмёт скачанный файл.")
        }
        else -> null
    }

    /**
     * The updater's own message where the card is not shown (nothing new, checking): «Новее нет: v108 — последняя
     * сборка.» or an error; true = an error. While checking the button says it. Inside the card only errors show.
     */
    fun line(phase: UpdPhase, u: UpdateState): Pair<String, Boolean>? = when {
        u.message.isEmpty() -> null
        phase == UpdPhase.NONE -> u.message to u.err
        phase == UpdPhase.CHECKING -> if (u.err) u.message to true else null
        else -> null
    }

    private const val MB = 1048576.0
}

/** «Исправить, км»: what the owner typed, as km (a comma or a dot, spaces between thousands); null when it is not a mileage. */
object OdometerInput {
    const val MAX_KM = 1_000_000.0

    fun parse(text: String): Double? {
        val km = text.filterNot { it.isWhitespace() }.replace(',', '.').toDoubleOrNull() ?: return null
        return km.takeIf { it.isFinite() && it >= 0 && it <= MAX_KM }
    }
}
