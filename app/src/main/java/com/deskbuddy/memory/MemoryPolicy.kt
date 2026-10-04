package com.deskbuddy.memory

/**
 * Enforced in code, not only in the prompt: a memory is saved only when the user's own latest
 * words ask for it ("remember that...", "don't forget...", "make a note..."), or when they say
 * yes right after Desk Buddy offered to remember something. Questions about memory ("do you
 * remember...?") and ordinary chat never qualify, so ambient conversation is never stored.
 */
object MemoryPolicy {

    private val questionsAboutMemory = Regex(
        "\\b(do|did|can|could) you (still )?remember (what|when|where|who|how|if|whether|anything)\\b" +
            "|\\bdo you remember\\b|\\bdid you remember\\b|\\bwhat do you remember\\b" +
            "|\\bremember when\\b|\\b(i|you) (don'?t|do not|can'?t|cannot) remember\\b" +
            "|\\bwhat did i (ask|tell) you to remember\\b",
    )

    private val saveRequests = listOf(
        // Imperatives at the start of the utterance: "remember...", "please note...", "save this".
        Regex("^(hey (desku|desk you|desk who|buddy)[,.!]?\\s*)?((please|ok|okay|and|also|oh|so|alright|right)[,]?\\s+)*" +
            "(remember|note|save|don'?t forget|do not forget|keep in mind|make a note|write (this|that|it) down|jot (this|that|it) down)\\b"),
        Regex("\\b(can|could|would|will) you (please )?(remember|note|save|keep in mind|make a note|write (this|that|it) down)\\b"),
        Regex("\\b(i want|i'd like|i would like|i need) you to (remember|note|save|keep in mind)\\b"),
        Regex("\\b(please )?remember (that|this|to|my|i|i'm|me|it)\\b"),
        Regex("\\b(save|store) (this|that|it)( for me| to (your )?memory)?\\b"),
        Regex("\\badd (this|that|it) to (your )?(memory|memories|notes)\\b"),
        Regex("\\bdon'?t let me forget\\b|\\bmake a note\\b|\\bnote (that|this|down)\\b"),
    )

    private val affirmations = Regex(
        "^(yes|yeah|yep|yup|sure|please|ok|okay|do it|go ahead|sounds good|please do|absolutely|definitely)\\b",
    )

    private val offerToRemember = Regex("\\b(remember|save|note|keep)\\b[^?]*\\?")

    fun allowsSave(latestUserWords: String, previousAssistantWords: String?): Boolean {
        val said = normalize(latestUserWords)
        if (said.isEmpty()) return false
        val explicit = saveRequests.any { it.containsMatchIn(said) }
        if (explicit && !questionsAboutMemory.containsMatchIn(said)) return true
        val offered = previousAssistantWords?.let { offerToRemember.containsMatchIn(normalize(it)) } ?: false
        return offered && affirmations.containsMatchIn(said)
    }

    private fun normalize(s: String) = s.lowercase().replace('’', '\'').replace(Regex("\\s+"), " ").trim()
}
