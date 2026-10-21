package io.github.purplepalm.birdair.phone

/** The owner's marker words: the ones tools/analyze_log.py reads (MARKER_KINDS), one kind each; режим and фара come from the commands. */
object MarkerWords {
    val PRESETS = listOf("Газ", "Отпустил газ", "Тормоз", "Стоп", "Зарядка вкл", "Зарядка выкл")

    /** A one-tap mark before it is labelled: the analyzer reads it as no kind. */
    fun auto(n: Int) = "Метка $n"

    /** The next one-tap mark of a recording that has [markers] (the owner's, the lamps' are not counted). */
    fun next(markers: List<Marker>) = auto(markers.size + 1)
}
