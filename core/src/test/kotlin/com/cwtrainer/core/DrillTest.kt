package com.cwtrainer.core

import com.cwtrainer.core.content.Callsigns
import com.cwtrainer.core.content.Content
import com.cwtrainer.core.content.Qso
import com.cwtrainer.core.drill.CharRecognitionDrill
import com.cwtrainer.core.drill.DrillContext
import com.cwtrainer.core.drill.Drills
import com.cwtrainer.core.drill.HeadCopyDrill
import com.cwtrainer.core.drill.ItemIds
import com.cwtrainer.core.drill.ProsignDrill
import com.cwtrainer.core.drill.RealWorldDrill
import com.cwtrainer.core.drill.ReceiveDrill
import com.cwtrainer.core.drill.Recommender
import com.cwtrainer.core.drill.Scoring
import com.cwtrainer.core.drill.SendDrill
import com.cwtrainer.core.drill.WordRecognitionDrill
import com.cwtrainer.core.koch.Koch
import com.cwtrainer.core.morse.MorseCode
import com.cwtrainer.core.srs.Sm2
import com.cwtrainer.core.srs.SrsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DrillTest {
    private val now = 1_700_000_000_000L
    private fun ctx(level: Int, items: Map<String, SrsItem> = emptyMap(), seed: Int = 1, group: Int = 5) =
        DrillContext(level, group, now, Random(seed)) { items[it] }

    private fun sendable(text: String): Boolean {
        val stripped = text.uppercase().replace(Regex("<[A-Z]+>"), "").filter { !it.isWhitespace() }
        val prosigns = Regex("<[A-Z]+>").findAll(text.uppercase()).count()
        return MorseCode.tokenize(text).count { it != " " } == stripped.length + prosigns
    }

    @Test fun receiveGroupsUseOnlyUnlockedCharacters() {
        for (level in listOf(2, 3, 10, 41)) {
            val allowed = Koch.unlocked(level).toSet()
            val c = ctx(level)
            repeat(200) {
                val p = ReceiveDrill.next(c)
                assertEquals(5, p.send.length)
                assertTrue(p.send.all { it.toString() in allowed })
            }
        }
    }

    @Test fun newestCharacterAndWeakCharactersAppearMoreOften() {
        var weak = SrsItem(ItemIds.char("K"))
        repeat(20) { weak = Sm2.review(weak, 1, now - 100_000 + it) }
        val items = HashMap<String, SrsItem>()
        items[weak.id] = weak
        for (s in listOf("M", "U", "R", "E", "S")) {
            var it = SrsItem(ItemIds.char(s))
            repeat(30) { i -> it = Sm2.review(it, 5, now - 100_000 + i, 400) }
            items[it.id] = it
        }
        val c = ctx(6, items)
        val counts = HashMap<Char, Int>()
        repeat(400) { ReceiveDrill.next(c).send.forEach { ch -> counts.merge(ch, 1, Int::plus) } }
        assertTrue("weak K ${counts['K']} vs M ${counts['M']}", counts['K']!! > 2 * counts['M']!!)
    }

    @Test fun wordListIsOneHundredUniqueSendableWords() {
        assertEquals(100, Content.words.size)
        assertEquals(100, Content.words.toSet().size)
        for (w in listOf("CQ", "DE", "TNX", "NAME", "RIG", "ANT", "WX", "HR", "UR", "ES", "THE", "AND")) {
            assertTrue(w, w in Content.words)
        }
        Content.words.forEach { assertTrue(it, sendable(it)) }
    }

    @Test fun allContentCanBeSent() {
        val rng = Random(5)
        (Content.sentences + Content.qCodes.map { it.text } + Content.abbreviations.map { it.text } +
            Content.names + Content.places + Content.rigs + Content.antennas + Content.weather)
            .forEach { assertTrue(it, sendable(it)) }
        repeat(500) {
            val q = Qso.exchange(rng)
            assertTrue(q, sendable(q))
            assertTrue(sendable(Qso.background(rng)) && sendable(Qso.shortLine(rng)))
        }
        for (q in listOf("QTH", "QRZ", "QSL", "QRM", "QRN", "QSB")) assertTrue(Content.qCodes.any { it.text == q })
        for (a in listOf("RST", "73", "TU", "FB", "OM")) assertTrue(Content.abbreviations.any { it.text == a })
    }

    @Test fun callsignsLookRealAndIncludeTurkishPrefixes() {
        val rng = Random(11)
        val tr = Regex("^(TA|TB|YM)[0-9][A-Z]{1,3}$")
        val seen = HashSet<String>()
        repeat(300) {
            val c = Callsigns.turkish(rng)
            assertTrue(c, tr.matches(c))
            seen.add(c.substring(0, 2))
        }
        assertEquals(setOf("TA", "TB", "YM"), seen)
        val any = Regex("^[A-Z0-9]{1,3}[0-9][A-Z]{1,3}(/P|/QRP)?$")
        var turkish = 0
        repeat(1000) {
            val c = Callsigns.random(rng)
            assertTrue(c, any.matches(c))
            if (c.startsWith("TA") || c.startsWith("TB") || c.startsWith("YM")) turkish++
        }
        assertTrue("turkish share $turkish", turkish in 300..500)
    }

    @Test fun drillsUnlockAsTheBriefDescribes() {
        assertNull(ReceiveDrill.lockReason(2))
        assertNull(CharRecognitionDrill.lockReason(2))
        assertNull(SendDrill.lockReason(2))
        assertNotNull(HeadCopyDrill.lockReason(2))
        assertNull(HeadCopyDrill.lockReason(HeadCopyDrill.MIN_LEVEL))
        for (d in listOf(WordRecognitionDrill, ProsignDrill, RealWorldDrill)) {
            assertNotNull(d.id, d.lockReason(40))
            assertNull(d.id, d.lockReason(41))
        }
        assertEquals(7, Drills.all.map { it.id }.toSet().size)
    }

    @Test fun recognitionPromptsOfferTheAnswerAmongTheChoices() {
        val c = ctx(41)
        repeat(100) {
            val w = WordRecognitionDrill.next(c)
            assertEquals(6, w.choices.toSet().size)
            assertTrue(w.send in w.choices)
            assertEquals(listOf(ItemIds.word(w.send)), w.itemIds)
            val p = ProsignDrill.next(c)
            assertTrue(p.send in p.choices && p.choices.size == 5)
            val ch = CharRecognitionDrill.next(ctx(4))
            assertEquals(Koch.unlocked(4), ch.choices)
        }
    }

    @Test fun headCopyOnlyUsesWhatIsUnlocked() {
        for (level in listOf(6, 12, 25, 40, 41)) {
            val c = ctx(level)
            repeat(200) {
                val p = HeadCopyDrill.next(c)
                assertTrue("level $level: ${p.send}", c.canSpell(p.send))
            }
        }
        repeat(200) { assertTrue(sendable(RealWorldDrill.next(ctx(41, seed = it)).send)) }
    }

    @Test fun scoringAlignsDroppedAndExtraCharacters() {
        assertEquals(listOf(true, true, true, true, true), Scoring.align("KMURE", "KMURE").map { it.correct })
        // One character dropped: only that one is wrong, not everything after it.
        assertEquals(listOf(true, false, true, true, true), Scoring.align("KMURE", "KURE").map { it.correct })
        // One substituted.
        assertEquals(listOf(true, true, false, true, true), Scoring.align("KMURE", "KMSRE").map { it.correct })
        // An extra typed character does not hurt the others.
        assertEquals(listOf(true, true, true, true, true), Scoring.align("KMURE", "KMUURE").map { it.correct })
        assertEquals(listOf(false, false), Scoring.align("KM", "").map { it.correct })
        assertEquals(0.8, Scoring.accuracy(Scoring.align("KMURE", "KURE")), 1e-9)
        assertEquals(listOf(true, true, true), Scoring.align("CQ <AR>", "cq<ar>").map { it.correct })
    }

    @Test fun recommenderLeadsWithKochThenAlternates() {
        val first = Recommender.next(2, emptyMap(), now, 20.0)
        assertEquals("receive", first.drill.id)
        val second = Recommender.next(2, emptyMap(), now, 20.0, lastDrillId = "receive", receiveDoneToday = true)
        assertTrue(second.drill.id != "receive")
        assertNull(second.drill.lockReason(2))
        val top = Recommender.next(41, emptyMap(), now, 20.0, lastDrillId = "receive")
        assertNull(top.drill.lockReason(41))
    }
}
