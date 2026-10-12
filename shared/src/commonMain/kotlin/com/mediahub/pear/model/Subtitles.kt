package com.mediahub.pear.model

/** One downloadable subtitle file for a title (spec: `OnlineSubtitle`). */
data class OnlineSubtitle(
    val id: String,
    val url: String,
    /** As the service reports it, for example `eng`. */
    val lang: String,
    /** Where it came from, for the menu: "OpenSubtitles" or the name of the add-on that offered it. */
    val source: String = "OpenSubtitles",
) {
    /** "English"; the upper-case code when the language isn't in [SubLanguages] (Swift asks the system's locale tables). */
    val languageName: String
        get() {
            val code = SubLanguages.canonical(lang)
            return SubLanguages.all.firstOrNull { it.code == code }?.name ?: lang.uppercase()
        }
}

/** The subtitle languages the app knows (spec: `SubLanguages` in SubtitleSupport.swift). */
object SubLanguages {
    data class Lang(val code: String, val name: String, val aliases: List<String>) {
        val id: String get() = code
    }

    val all: List<Lang> = listOf(
        Lang("en", "English", listOf("eng")), Lang("ar", "Arabic", listOf("ara")),
        Lang("fr", "French", listOf("fra", "fre")), Lang("es", "Spanish", listOf("spa")),
        Lang("de", "German", listOf("deu", "ger")), Lang("it", "Italian", listOf("ita")),
        Lang("pt", "Portuguese", listOf("por", "pob")), Lang("ru", "Russian", listOf("rus")),
        Lang("tr", "Turkish", listOf("tur")), Lang("ja", "Japanese", listOf("jpn")),
        Lang("ko", "Korean", listOf("kor")), Lang("zh", "Chinese", listOf("zho", "chi", "cmn")),
        Lang("hi", "Hindi", listOf("hin")), Lang("nl", "Dutch", listOf("nld", "dut")),
        Lang("pl", "Polish", listOf("pol")), Lang("sv", "Swedish", listOf("swe")),
        Lang("da", "Danish", listOf("dan")), Lang("no", "Norwegian", listOf("nor", "nob")),
        Lang("fi", "Finnish", listOf("fin")), Lang("el", "Greek", listOf("ell", "gre")),
        Lang("he", "Hebrew", listOf("heb")), Lang("id", "Indonesian", listOf("ind")),
        Lang("th", "Thai", listOf("tha")), Lang("vi", "Vietnamese", listOf("vie")),
        Lang("uk", "Ukrainian", listOf("ukr")), Lang("cs", "Czech", listOf("ces", "cze")),
        Lang("hu", "Hungarian", listOf("hun")), Lang("ro", "Romanian", listOf("ron", "rum")),
    )

    /** Two-letter code for any spelling of a language (`eng`, `en-US`, `English`), or null if unknown. */
    fun canonical(raw: String?): String? {
        var l = raw?.trim()?.lowercase() ?: return null
        if (l.isEmpty()) return null
        val cut = l.indexOfFirst { it == '-' || it == '_' }
        if (cut >= 0) l = l.substring(0, cut)
        for (lang in all) {
            if (l == lang.code || l in lang.aliases || l == lang.name.lowercase()) return lang.code
        }
        return null
    }

    fun matches(track: String?, wanted: String): Boolean {
        val a = canonical(track)
        val b = canonical(wanted)
        if (a != null && b != null) return a == b
        return track?.lowercase() == wanted.lowercase()
    }
}
