package com.cwtrainer.core

import com.cwtrainer.core.srs.Sm2
import com.cwtrainer.core.srs.SrsItem
import com.cwtrainer.core.srs.SrsScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SrsTest {
    private val t0 = 1_700_000_000_000L
    private val day = Sm2.DAY_MS

    @Test fun qualityReflectsCorrectnessAndSpeed() {
        assertEquals(0, Sm2.quality(false, answered = false))
        assertEquals(1, Sm2.quality(false, 400))
        assertEquals(5, Sm2.quality(true, 500))
        assertEquals(4, Sm2.quality(true, 1500))
        assertEquals(3, Sm2.quality(true, 4000))
        assertEquals(4, Sm2.quality(true, null))
    }

    @Test fun intervalsFollowSm2WhenReviewedOnSchedule() {
        var it = Sm2.review(SrsItem("c:K"), 4, t0)
        assertEquals(1.0, it.intervalDays, 1e-9)
        assertEquals(t0 + day, it.dueAt)
        it = Sm2.review(it, 4, it.dueAt)
        assertEquals(6.0, it.intervalDays, 1e-9)
        val ease = it.ease
        it = Sm2.review(it, 4, it.dueAt)
        assertEquals(6.0 * ease, it.intervalDays, 1e-6)
        assertEquals(3, it.reps)
    }

    @Test fun cramingInOneSessionDoesNotInflateTheInterval() {
        var it = SrsItem("c:K")
        repeat(40) { i -> it = Sm2.review(it, 5, t0 + i * 3000L, 500) }
        assertEquals(1.0, it.intervalDays, 1e-9)
        assertEquals(1, it.reps)
        assertEquals(40, it.total)
        assertTrue(it.ease <= Sm2.MAX_EASE)
    }

    @Test fun aMissResetsAndComesBackSoon() {
        var it = Sm2.review(SrsItem("c:K"), 5, t0)
        it = Sm2.review(it, 5, it.dueAt)
        val before = it.ease
        val missed = Sm2.review(it, 1, it.dueAt + 5)
        assertEquals(0, missed.reps)
        assertEquals(1, missed.lapses)
        assertTrue(missed.ease < before)
        assertTrue(missed.dueAt - (it.dueAt + 5) <= Sm2.RELEARN_MS)
        var worst = missed
        repeat(30) { worst = Sm2.review(worst, 0, t0) }
        assertEquals(Sm2.MIN_EASE, worst.ease, 1e-9)
    }

    @Test fun slowAnswersLowerEaseAndFastOnesRaiseIt() {
        val slow = Sm2.review(SrsItem("c:K"), Sm2.quality(true, 5000), t0, 5000)
        val fast = Sm2.review(SrsItem("c:K"), Sm2.quality(true, 400), t0, 400)
        assertTrue(slow.ease < Sm2.START_EASE)
        assertTrue(fast.ease > Sm2.START_EASE)
        assertEquals(400.0, fast.avgRtMs, 1e-9)
    }

    private fun mastered(id: String): SrsItem {
        var it = SrsItem(id)
        repeat(30) { i -> it = Sm2.review(it, 5, t0 + i * 2000L, 450) }
        return it
    }

    private fun struggling(id: String): SrsItem {
        var it = SrsItem(id)
        repeat(30) { i -> it = Sm2.review(it, if (i % 2 == 0) 1 else 3, t0 + i * 2000L, 2600) }
        return it
    }

    @Test fun missedAndSlowItemsWeighMoreThanMasteredOnes() {
        val now = t0 + 120_000
        val good = mastered("c:K")
        val bad = struggling("c:M")
        assertTrue(SrsScheduler.weight(bad, now) > 4 * SrsScheduler.weight(good, now))
        assertTrue("new items are introduced eagerly", SrsScheduler.weight(SrsItem("c:U"), now) > SrsScheduler.weight(good, now))
        // A mastered item gains weight again once it falls due.
        assertTrue(SrsScheduler.weight(good, good.dueAt + 1) > SrsScheduler.weight(good, now))
    }

    @Test fun schedulerActuallyShowsWeakItemsMoreOften() {
        val now = t0 + 120_000
        val items = listOf(mastered("c:K"), mastered("c:M"), mastered("c:U"), struggling("c:R"))
        val rng = Random(7)
        val counts = HashMap<String, Int>()
        repeat(4000) { counts.merge(SrsScheduler.pick(items, now, rng).id, 1, Int::plus) }
        val weak = counts["c:R"] ?: 0
        val strong = counts["c:K"] ?: 0
        assertTrue("weak=$weak strong=$strong", weak > 3 * strong)
        assertTrue("mastered items still appear", strong > 50)
    }

    @Test fun pickManyAvoidsImmediateRepeatsWhenItCan() {
        val items = listOf("K", "M", "U", "R").map { SrsItem("c:$it") }
        val picks = SrsScheduler.pickMany(items, 200, t0, Random(3))
        assertEquals(200, picks.size)
        val repeats = picks.zipWithNext().count { (a, b) -> a.id == b.id }
        assertTrue("repeats=$repeats", repeats < 25)
    }

    @Test fun dueCountIgnoresUnseenItems() {
        val a = Sm2.review(SrsItem("w:CQ"), 4, t0)
        assertEquals(0, SrsScheduler.dueCount(listOf(a, SrsItem("w:DE")), t0 + 1000))
        assertEquals(1, SrsScheduler.dueCount(listOf(a, SrsItem("w:DE")), t0 + 2 * day))
    }
}
