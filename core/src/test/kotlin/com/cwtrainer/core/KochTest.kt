package com.cwtrainer.core

import com.cwtrainer.core.koch.Koch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KochTest {
    @Test fun orderIsTheOneFromTheBrief() {
        assertEquals(41, Koch.ORDER.size)
        assertEquals(41, Koch.ORDER.toSet().size)
        assertEquals(listOf("K", "M"), Koch.unlocked(2))
        assertEquals("KMURESNAPTLWI.JZ=FOY,VG5/Q92H38B?47C1D60X", Koch.ORDER.joinToString(""))
    }

    @Test fun startsWithTwoCharactersAndAddsOneAtATime() {
        assertEquals(2, Koch.unlocked(0).size)
        assertEquals("U", Koch.next(2))
        assertEquals(listOf("K", "M", "U"), Koch.unlocked(3))
        assertEquals("U", Koch.newest(3))
        assertEquals(null, Koch.next(41))
        assertEquals(41, Koch.unlocked(99).size)
    }

    @Test fun unlocksAtNinetyPercentOverFiveMinutes() {
        assertTrue(Koch.shouldUnlockNext(2, 0.90, 300, 100))
        assertTrue(Koch.shouldUnlockNext(10, 0.97, 420, 200))
        assertEquals(3, Koch.levelAfterSession(2, 0.95, 300, 100))
    }

    @Test fun doesNotUnlockBelowAccuracyOrBeforeFiveMinutes() {
        assertFalse(Koch.shouldUnlockNext(2, 0.899, 300, 100))
        assertFalse(Koch.shouldUnlockNext(2, 1.0, 299, 100))
        assertFalse("a handful of characters is not a session", Koch.shouldUnlockNext(2, 1.0, 300, 10))
        assertFalse("nothing left to unlock", Koch.shouldUnlockNext(41, 1.0, 600, 500))
        assertEquals(2, Koch.levelAfterSession(2, 0.5, 300, 100))
        assertEquals(41, Koch.levelAfterSession(41, 1.0, 600, 500))
    }

    @Test fun effectiveSpeedTightensUntilItEqualsCharacterSpeed() {
        assertEquals(8.0, Koch.effectiveWpm(2, 20.0), 1e-9)
        assertEquals(20.0, Koch.effectiveWpm(41, 20.0), 1e-9)
        var prev = 0.0
        for (level in 2..41) {
            val e = Koch.effectiveWpm(level, 20.0)
            assertTrue(e >= prev && e <= 20.0)
            prev = e
        }
        assertEquals(30.0, Koch.effectiveWpm(41, 30.0), 1e-9)
        assertEquals(8.0, Koch.effectiveWpm(2, 15.0), 1e-9)
    }

    @Test fun lettersAreCompleteOnlyAtTheEnd() {
        assertFalse(Koch.allLettersLearned(40))
        assertTrue(Koch.allLettersLearned(41))
        assertTrue(Koch.allLearned(41))
    }
}
