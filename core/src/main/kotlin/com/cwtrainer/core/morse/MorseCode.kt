package com.cwtrainer.core.morse

/**
 * The code table. A "symbol" is either a single character ("K", "5", "?") or a prosign written
 * in angle brackets ("<AR>"). Prosigns are sent as one run-together character.
 *
 * v2 (Turkish characters): add entries to [characters]; nothing else needs to change.
 */
object MorseCode {
    val characters: Map<String, String> = linkedMapOf(
        "A" to ".-", "B" to "-...", "C" to "-.-.", "D" to "-..", "E" to ".", "F" to "..-.",
        "G" to "--.", "H" to "....", "I" to "..", "J" to ".---", "K" to "-.-", "L" to ".-..",
        "M" to "--", "N" to "-.", "O" to "---", "P" to ".--.", "Q" to "--.-", "R" to ".-.",
        "S" to "...", "T" to "-", "U" to "..-", "V" to "...-", "W" to ".--", "X" to "-..-",
        "Y" to "-.--", "Z" to "--..",
        "0" to "-----", "1" to ".----", "2" to "..---", "3" to "...--", "4" to "....-",
        "5" to ".....", "6" to "-....", "7" to "--...", "8" to "---..", "9" to "----.",
        "." to ".-.-.-", "," to "--..--", "?" to "..--..", "/" to "-..-.", "=" to "-...-",
    )

    data class Prosign(val symbol: String, val pattern: String, val meaning: String)

    val prosigns: List<Prosign> = listOf(
        Prosign("<AR>", ".-.-.", "End of message"),
        Prosign("<SK>", "...-.-", "End of contact"),
        Prosign("<BT>", "-...-", "Break / new paragraph"),
        Prosign("<KN>", "-.--.", "Go ahead, named station only"),
        Prosign("<BK>", "-...-.-", "Break-in / back to you"),
    )

    private val all: Map<String, String> = characters + prosigns.associate { it.symbol to it.pattern }

    // Plain characters win when a pattern is shared (BT and "=" are the same sound).
    private val reverse: Map<String, String> =
        prosigns.associate { it.pattern to it.symbol } + characters.entries.associate { it.value to it.key }

    fun pattern(symbol: String): String? = all[symbol]

    fun symbolFor(pattern: String): String? = reverse[pattern]

    fun isProsign(symbol: String): Boolean = symbol.length > 1

    /** Label without brackets, for buttons: "<AR>" -> "AR". */
    fun display(symbol: String): String = symbol.removePrefix("<").removeSuffix(">")

    /**
     * Splits text into symbols. A single space " " in the result is a word gap.
     * Unknown characters are dropped. Input is case-insensitive.
     */
    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val s = text.uppercase()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '<' -> {
                    val end = s.indexOf('>', i)
                    if (end > 0) {
                        val sym = s.substring(i, end + 1)
                        if (all.containsKey(sym)) out.add(sym)
                        i = end
                    }
                }
                c.isWhitespace() -> if (out.isNotEmpty() && out.last() != " ") out.add(" ")
                all.containsKey(c.toString()) -> out.add(c.toString())
            }
            i++
        }
        while (out.isNotEmpty() && out.last() == " ") out.removeAt(out.size - 1)
        return out
    }
}
