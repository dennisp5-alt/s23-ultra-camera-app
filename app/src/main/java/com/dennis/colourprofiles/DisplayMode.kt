package com.dennis.colourprofiles

/** Values of Samsung's screen_mode_setting. */
enum class DisplayMode(val id: Int, val label: String) {
    BASIC(2, "Basic (sRGB)"),
    NATURAL(3, "Natural"),
    VIVID(4, "Vivid"),
    PHOTO(1, "AMOLED Photo (Adobe RGB)"),
    CINEMA(0, "AMOLED Cinema (DCI-P3)");

    companion object {
        fun fromId(id: Int): DisplayMode = entries.firstOrNull { it.id == id } ?: VIVID
    }
}
