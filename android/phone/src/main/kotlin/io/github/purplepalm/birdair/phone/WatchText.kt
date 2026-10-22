package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.dial.DialFace

/** «Часы»' words (pure): the watch's status, the face the watch draws, the version hint. */
object WatchText {
    /** The status card: [title] the watch's name, [linked] a node is connected («● на связи» before [line]). */
    data class Status(val title: String, val linked: Boolean, val line: String)

    /** The watch app said hello lately: every 30 s while data flows, every 5 s while not ([Versions.WATCH_ACTIVE_MS]). */
    fun open(w: WatchStatus, nowElapsedMs: Long): Boolean =
        w.helloAt > 0 && nowElapsedMs >= w.helloAt && nowElapsedMs - w.helloAt <= Versions.WATCH_ACTIVE_MS

    /** «Galaxy Watch Ultra» · «● на связи · показывает поездку» / «оживёт, когда подключишься» / «открой Bird Air на часах». */
    fun status(w: WatchStatus, open: Boolean, running: Boolean): Status = when {
        !w.available -> Status("Часы", false, "Wear OS недоступен на телефоне")
        w.nodes.isNotEmpty() -> Status(w.nodes.joinToString(), true, when {
            !open -> "открой Bird Air на часах"
            running -> "показывает поездку"
            else -> "оживёт, когда подключишься"
        })
        !w.checked -> Status("Часы", false, "проверяю…")
        else -> Status("Часы", false, "нет на связи — включи часы и Bluetooth на них")
    }

    /** «ЦИФЕРБЛАТ»'s right side: «на часах: Крупные лампы ✓», «отправляю на часы…» or, with no watch around, when it gets it. */
    fun faceLine(f: WatchFaceState, linked: Boolean): String = when {
        f.applied -> "на часах: " + f.selected.title + " ✓"
        linked -> "отправляю на часы…"
        else -> "часы возьмут, когда включатся"
    }

    /** Under the tiles: «Крупные лампы (обычный): погасших ламп нет; …». */
    fun about(face: DialFace): String {
        val a = face.about.replaceFirstChar { it.lowercase() }
        return face.title + (if (face == DialFace.DEFAULT) " (обычный)" else "") + ": " + a + if (a.endsWith(".")) "" else "."
    }

    /** A plate's title and text. */
    data class Hint(val title: String, val text: String)

    /** The watch is behind the phone (the rules of [Versions.watchHint]): «На часах v106, на телефоне v108» and what to do. */
    fun hint(v: WatchVersion?, helloSeen: Boolean, phoneCode: Int): Hint? {
        if (Versions.watchHint(v, helloSeen, phoneCode) == null) return null
        val c = v?.versionCode
            ?: return Hint("Часы не сообщают версию", "Там старая сборка: один раз поставь ${Versions.WATCH_APK} вручную (android/README.md), дальше — на часах «Подробно → Обновить часы».")
        return Hint("На часах v$c, на телефоне v$phoneCode", "Обнови часы: на часах «Подробно → Обновить часы». Без этого новые циферблаты там не появятся.")
    }

    /** «Версия на часах: 6-b71d0e3 (v106)»; behind the phone also where to look when the watch has no such button. */
    fun version(v: WatchVersion?, behind: Boolean): String? {
        if (v == null || (v.versionCode == null && v.versionName == null)) return null
        val s = "Версия на часах: " + (v.versionCode?.let { UpdateCardText.version(v.versionName, it) } ?: v.versionName)
        return s + if (behind) ". Нет кнопки «Обновить часы» — один раз вручную, android/README.md" else ""
    }
}
