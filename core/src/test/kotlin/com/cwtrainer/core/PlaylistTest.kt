package com.cwtrainer.core

import com.cwtrainer.core.drill.ItemIds
import com.cwtrainer.core.koch.Koch
import com.cwtrainer.core.listen.ListenKind
import com.cwtrainer.core.listen.Playlist
import com.cwtrainer.core.listen.PlaylistGenerator
import com.cwtrainer.core.morse.MorseCode
import com.cwtrainer.core.morse.Timing
import com.cwtrainer.core.srs.Sm2
import com.cwtrainer.core.srs.SrsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaylistTest {
    private val now = 1_794_000_000_000L
    private fun gen(level: Int, items: Map<String, SrsItem> = emptyMap(), seed: Int = 1) =
        PlaylistGenerator(level, 5, now, Random(seed)) { items[it] }

    @Test fun onlyUnlockedMaterialIsUsed() {
        for (level in listOf(2, 5, 12, 30, 40, 41)) {
            val have = Koch.unlocked(level).toSet()
            val g = gen(level)
            repeat(300) {
                val item = g.next()
                assertTrue("level $level: ${item.text}", MorseCode.tokenize(item.text).all { it == " " || it in have || (level == 41 && MorseCode.isProsign(it)) })
                assertTrue(item.kind in g.kinds)
            }
        }
    }

    @Test fun contentWidensWithTheKochLevel() {
        assertEquals(listOf(ListenKind.GROUP), gen(2).kinds)
        assertTrue(ListenKind.WORD in gen(20).kinds)
        assertTrue(ListenKind.QSO !in gen(40).kinds)
        assertEquals(ListenKind.values().toList(), gen(41).kinds)
        val seen = HashSet<ListenKind>()
        val g = gen(41)
        repeat(200) { seen.add(g.next().kind) }
        assertEquals(4, seen.size)
    }

    @Test fun weakCharactersAreHeardMoreOften() {
        val items = HashMap<String, SrsItem>()
        var weak = SrsItem(ItemIds.char("K"))
        repeat(20) { weak = Sm2.review(weak, 1, now - 50_000 + it) }
        items[weak.id] = weak
        for (s in listOf("M", "U", "R", "E")) {
            var it = SrsItem(ItemIds.char(s))
            repeat(30) { i -> it = Sm2.review(it, 5, now - 50_000 + i, 400) }
            items[it.id] = it
        }
        val g = gen(5, items)
        val counts = HashMap<Char, Int>()
        repeat(300) { g.next().text.forEach { c -> counts.merge(c, 1, Int::plus) } }
        assertTrue("K ${counts['K']} M ${counts['M']}", counts['K']!! > 2 * counts['M']!!)
    }

    @Test fun playlistFillsTheRequestedTime() {
        for (minutes in listOf(10, 20, 30)) {
            val timing = Timing(20.0, 12.0)
            val gap = 3000.0
            val total = minutes * 60_000.0
            val list = Playlist.build(gen(41, seed = minutes), timing, total, gap)
            val length = list.sumOf { Playlist.durationMs(it, timing) + gap }
            assertTrue("$minutes min: $length", length >= total)
            // It overshoots by at most the last item.
            assertTrue(length - total <= Playlist.durationMs(list.last(), timing) + gap)
            assertTrue(list.zipWithNext().none { (a, b) -> a.text == b.text })
        }
    }
}
