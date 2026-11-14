package com.cwtrainer.core.drill

import com.cwtrainer.core.morse.MorseCode

data class SymbolResult(val symbol: String, val correct: Boolean)

object Scoring {
    /**
     * Compares what was sent with what was typed, symbol by symbol, using an edit-distance
     * alignment. A dropped or extra character therefore costs one error instead of shifting
     * everything after it. Word spaces are ignored on both sides.
     *
     * @return one entry per sent symbol
     */
    fun align(sent: List<String>, typed: List<String>): List<SymbolResult> {
        val a = sent.filter { it != " " }
        val b = typed.filter { it != " " }
        val n = a.size
        val m = b.size
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            val sub = d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(sub, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        val ok = BooleanArray(n)
        var i = n
        var j = m
        while (i > 0) {
            when {
                j > 0 && d[i][j] == d[i - 1][j - 1] + (if (a[i - 1] == b[j - 1]) 0 else 1) -> {
                    ok[i - 1] = a[i - 1] == b[j - 1]; i--; j--
                }
                d[i][j] == d[i - 1][j] + 1 -> { ok[i - 1] = false; i-- }  // sent but not typed
                else -> j--                                                // typed but never sent
            }
        }
        return a.mapIndexed { k, s -> SymbolResult(s, ok[k]) }
    }

    fun align(sent: String, typed: String): List<SymbolResult> =
        align(MorseCode.tokenize(sent), MorseCode.tokenize(typed))

    fun accuracy(results: List<SymbolResult>): Double =
        if (results.isEmpty()) 0.0 else results.count { it.correct }.toDouble() / results.size
}
