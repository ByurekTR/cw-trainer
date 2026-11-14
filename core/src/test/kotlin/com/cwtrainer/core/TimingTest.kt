package com.cwtrainer.core

import com.cwtrainer.core.morse.FistModel
import com.cwtrainer.core.morse.Keyer
import com.cwtrainer.core.morse.MorseCode
import com.cwtrainer.core.morse.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TimingTest {
    private val eps = 1e-6

    @Test fun dotLengthFollowsParisStandard() {
        assertEquals(60.0, Timing(20.0).dotMs, eps)
        assertEquals(80.0, Timing(15.0).dotMs, eps)
        assertEquals(40.0, Timing(30.0).dotMs, eps)
        assertEquals(180.0, Timing(20.0).dashMs, eps)
    }

    @Test fun standardSpacingWhenEffectiveEqualsCharacterSpeed() {
        val t = Timing(20.0, 20.0)
        assertEquals(3 * t.dotMs, t.charGapMs, eps)
        assertEquals(7 * t.dotMs, t.wordGapMs, eps)
    }

    @Test fun farnsworthGapsMatchTheFormula() {
        // 20 WPM characters at 8 WPM effective: t_a = (60*20 - 37.2*8) / (8*20) = 5.64 s
        val t = Timing(20.0, 8.0)
        assertEquals(60.0, t.dotMs, eps)                       // characters stay at 20 WPM
        assertEquals(3 * 5640.0 / 19, t.charGapMs, 1e-6)       // 890.53 ms
        assertEquals(7 * 5640.0 / 19, t.wordGapMs, 1e-6)       // 2077.89 ms
        assertEquals(7.0 / 3.0, t.wordGapMs / t.charGapMs, eps)
    }

    @Test fun effectiveSpeedIsExactForParis() {
        for (c in listOf(15.0, 18.0, 20.0, 25.0, 30.0)) {
            for (s in listOf(5.0, 8.0, 12.5, c)) {
                val t = Timing(c, s)
                val plan = Keyer.plan("PARIS", t)
                // One word plus its word gap must last exactly 60/s seconds.
                assertEquals("c=$c s=$s", 60000.0 / s, plan.totalMs + t.wordGapMs, 1e-6)
                assertEquals(60000.0 / s, t.wordMs, 1e-6)
            }
        }
    }

    @Test fun effectiveSpeedNeverExceedsCharacterSpeed() {
        val t = Timing(15.0, 25.0)
        assertEquals(3 * t.dotMs, t.charGapMs, eps)
    }

    @Test fun planHasExactElementLengths() {
        val t = Timing(20.0, 10.0)
        val plan = Keyer.plan("KM K", t)
        val ms = plan.elements.map { it.ms }
        val d = t.dotMs
        val expected = listOf(
            3 * d, d, d, d, 3 * d,          // K  -.-
            t.charGapMs,
            3 * d, d, 3 * d,                // M  --
            t.wordGapMs,
            3 * d, d, d, d, 3 * d,          // K
        )
        assertEquals(expected.size, ms.size)
        expected.zip(ms).forEach { (e, a) -> assertEquals(e, a, eps) }
        assertTrue(plan.elements.first().on && plan.elements.last().on)
        plan.elements.zipWithNext().forEach { (a, b) -> assertTrue(a.on != b.on) }
        assertEquals(listOf("K", "M", "K"), plan.symbols.map { it.symbol })
        assertEquals(plan.totalMs, plan.symbols.last().endMs, eps)
    }

    @Test fun prosignsRunTogetherWithNoLetterGap() {
        val t = Timing(20.0, 8.0)
        val prosign = Keyer.plan("<AR>", t)
        val letters = Keyer.plan("AR", t)
        assertEquals(1, prosign.symbols.size)
        assertEquals(13 * t.dotMs, prosign.totalMs, eps)                 // .-.-. = 9 mark + 4 gap units
        assertEquals(12 * t.dotMs + t.charGapMs, letters.totalMs, eps)   // A + letter gap + R
        assertTrue(prosign.elements.none { !it.on && it.ms > t.dotMs + eps })
    }

    @Test fun machineFistIsExactAndHumanFistIsNot() {
        val t = Timing(20.0)
        val a = Keyer.plan("CQ DE TA2ABC", t, FistModel.MACHINE, Random(1))
        val b = Keyer.plan("CQ DE TA2ABC", t, FistModel.MACHINE, Random(2))
        assertEquals(a.elements, b.elements)
        val h = Keyer.plan("CQ DE TA2ABC", t, FistModel.HEAVY_DASHES, Random(1))
        assertEquals(a.elements.size, h.elements.size)
        val dashes = h.elements.filter { it.on && it.ms > 2 * t.dotMs }.map { it.ms }.average()
        val dots = h.elements.filter { it.on && it.ms <= 2 * t.dotMs }.map { it.ms }.average()
        assertTrue("heavy dashes should exceed 3:1, was ${dashes / dots}", dashes / dots > 3.5)
    }

    @Test fun tokenizerHandlesProsignsSpacesAndCase() {
        assertEquals(listOf("C", "Q", " ", "<AR>", " ", "K"), MorseCode.tokenize("  cq   <ar> k  "))
        assertEquals(listOf("A", "B"), MorseCode.tokenize("A#B"))
        assertEquals("=", MorseCode.symbolFor("-...-"))
        assertEquals("<AR>", MorseCode.symbolFor(".-.-."))
    }
}
