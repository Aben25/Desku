package com.deskbuddy.brain

/** Text-to-speech reads markdown symbols aloud ("asterisk"), so strip any that slip through. */
object SpeechText {
    fun clean(text: String): String = text
        .replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^\\s*(#{1,6}|[-*•]|\\d+[.)])\\s+"), "")
        .replace(Regex("[*_`#]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
