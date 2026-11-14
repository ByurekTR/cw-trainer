package com.cwtrainer.core.listen

import com.cwtrainer.core.content.Callsigns
import com.cwtrainer.core.content.Content
import com.cwtrainer.core.content.Qso
import com.cwtrainer.core.drill.ItemIds
import com.cwtrainer.core.koch.Koch
import com.cwtrainer.core.morse.Keyer
import com.cwtrainer.core.morse.MorseCode
import com.cwtrainer.core.morse.Timing
import com.cwtrainer.core.srs.SrsItem
import com.cwtrainer.core.srs.SrsScheduler
import kotlin.random.Random

enum class ListenKind(val label: String) {
    GROUP("Groups"), WORD("Words"), CALLSIGN("Callsigns"), QSO("QSO text")
}

data class ListenItem(val text: String, val kind: ListenKind)

/**
 * Endless source of listening material drawn from the spaced-repetition queue: weak, slow and
 * due characters and words come up more often, exactly as in the drills. Only material that
 * can be spelled with the unlocked characters is used.
 *
 * v2+: a pile-up or QSO role-play playlist is another class with the same next() shape.
 */
class PlaylistGenerator(
    private val level: Int,
    private val groupLength: Int,
    private val now: Long,
    private val rng: Random,
    private val lookup: (String) -> SrsItem?,
) {
    private val unlocked = Koch.unlocked(level)
    private val have = unlocked.toSet()
    private val words = Content.words.filter { canSpell(it) }
    private var last: String? = null

    private fun canSpell(text: String) = MorseCode.tokenize(text).all { it == " " || it in have }
    private fun item(id: String) = lookup(id) ?: SrsItem(id)

    /** What this level can offer. Groups are always there. */
    val kinds: List<ListenKind> = buildList {
        add(ListenKind.GROUP)
        if (words.size >= 6) add(ListenKind.WORD)
        if (Koch.allLearned(level)) { add(ListenKind.CALLSIGN); add(ListenKind.QSO) }
    }

    fun next(): ListenItem {
        repeat(8) {
            val candidate = make(pickKind())
            if (candidate.text != last) { last = candidate.text; return candidate }
        }
        return make(ListenKind.GROUP).also { last = it.text }
    }

    private fun pickKind(): ListenKind {
        val weights = kinds.map {
            when (it) {
                ListenKind.GROUP -> if (kinds.size == 1) 1.0 else 2.0
                ListenKind.WORD -> 3.0
                ListenKind.CALLSIGN -> 2.0
                ListenKind.QSO -> 3.0
            }
        }
        var r = rng.nextDouble() * weights.sum()
        for (i in kinds.indices) { r -= weights[i]; if (r <= 0) return kinds[i] }
        return kinds.last()
    }

    private fun make(kind: ListenKind): ListenItem = when (kind) {
        ListenKind.GROUP -> {
            val chars = unlocked.map { item(ItemIds.char(it)) }
            val picked = SrsScheduler.pickMany(chars, groupLength.coerceIn(2, 8), now, rng)
            ListenItem(picked.joinToString("") { it.id.removePrefix("c:") }, kind)
        }
        ListenKind.WORD -> {
            // Two or three words in a row, like a phrase.
            val pool = words.map { item(ItemIds.word(it)) }
            val n = 2 + rng.nextInt(2)
            ListenItem(SrsScheduler.pickMany(pool, n, now, rng).joinToString(" ") { it.id.removePrefix("w:") }, kind)
        }
        ListenKind.CALLSIGN -> ListenItem(Callsigns.random(rng) + " DE " + Callsigns.random(rng), kind)
        ListenKind.QSO -> ListenItem(Qso.exchange(rng), kind)
    }
}

object Playlist {
    /** How long an item sounds, without the gap that follows it. */
    fun durationMs(item: ListenItem, timing: Timing): Double = Keyer.plan(item.text, timing).totalMs

    /**
     * A fixed playlist of about [totalMs]: items are added until the next one would start
     * after the end. (The app generates lazily with the same rule; this is the testable form.)
     */
    fun build(generator: PlaylistGenerator, timing: Timing, totalMs: Double, gapMs: Double): List<ListenItem> {
        val out = ArrayList<ListenItem>()
        var t = 0.0
        while (t < totalMs) {
            val item = generator.next()
            out.add(item)
            t += durationMs(item, timing) + gapMs
        }
        return out
    }
}
