package com.cwtrainer.core.send

/**
 * Iambic keyer, mode B, with dot/dash memory.
 *
 * The paddles are just two booleans set from the UI thread; the keyer itself is advanced by
 * the audio engine one sample at a time, so element timing is sample-accurate.
 *
 * Mode B: if the opposite paddle is (or was) closed while an element is being sent, one more
 * alternate element follows, even if both paddles have been let go by then.
 *
 * v2: a USB/Bluetooth paddle sets [ditPressed]/[dahPressed] from key events instead of touch.
 */
class IambicKeyer(wpm: Double) {
    @Volatile var ditPressed: Boolean = false
    @Volatile var dahPressed: Boolean = false
    @Volatile var wpm: Double = wpm

    private enum class State { IDLE, MARK, GAP }

    private var state = State.IDLE
    private var remainingMs = 0.0
    private var lastWasDit = false
    private var ditMemory = false
    private var dahMemory = false
    private var prevDit = false
    private var prevDah = false

    val keyDown: Boolean get() = state == State.MARK

    private val dotMs: Double get() = 1200.0 / wpm.coerceIn(5.0, 60.0)

    /** Advances time by [dtMs] and returns whether the key is closed afterwards. */
    fun advance(dtMs: Double): Boolean {
        val dit = ditPressed
        val dah = dahPressed
        if (state != State.IDLE) {
            // Memory: a fresh press of either paddle, or the opposite paddle held (the squeeze).
            if (dit && !prevDit) ditMemory = true
            if (dah && !prevDah) dahMemory = true
            if (lastWasDit && dah) dahMemory = true
            if (!lastWasDit && dit) ditMemory = true
        }
        prevDit = dit
        prevDah = dah

        var dt = dtMs
        while (true) {
            when (state) {
                State.IDLE -> {
                    when {
                        dit -> start(true)
                        dah -> start(false)
                        else -> return false
                    }
                }
                State.MARK -> {
                    if (dt <= remainingMs) { remainingMs -= dt; return true }
                    dt -= remainingMs
                    state = State.GAP
                    remainingMs = dotMs
                }
                State.GAP -> {
                    if (dt <= remainingMs) { remainingMs -= dt; return false }
                    dt -= remainingMs
                    val wantDit = ditMemory || dit
                    val wantDah = dahMemory || dah
                    when {
                        lastWasDit && wantDah -> start(false)
                        !lastWasDit && wantDit -> start(true)
                        wantDit -> start(true)
                        wantDah -> start(false)
                        else -> { state = State.IDLE; return false }
                    }
                }
            }
        }
    }

    private fun start(dit: Boolean) {
        state = State.MARK
        lastWasDit = dit
        remainingMs = if (dit) dotMs else 3 * dotMs
        if (dit) ditMemory = false else dahMemory = false
    }

    fun reset() {
        state = State.IDLE
        ditMemory = false; dahMemory = false
        ditPressed = false; dahPressed = false
        prevDit = false; prevDah = false
    }
}
