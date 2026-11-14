package com.cwtrainer.app.session

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cwtrainer.app.AppContainer
import com.cwtrainer.app.data.Repository
import com.cwtrainer.app.data.SessionEntity
import com.cwtrainer.app.data.Settings
import com.cwtrainer.core.audio.BandConditions
import com.cwtrainer.core.audio.CwEngine
import com.cwtrainer.core.drill.DrillContext
import com.cwtrainer.core.drill.SendDrill
import com.cwtrainer.core.send.IambicKeyer
import com.cwtrainer.core.send.SendDecoder
import com.cwtrainer.core.send.TimingScore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * Send practice: turns touches into key-down/key-up times, plays the sidetone, and decodes.
 *
 * The input abstraction is simply "something calls [straight] or [paddle]". v2's USB-OTG or
 * Bluetooth key maps its key events to the same two functions; nothing below them changes.
 */
class SendController(private val c: AppContainer) : CwEngine.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = c.player.engine
    private val rng = Random(System.nanoTime())
    private val initial: Settings = c.repo.settings.value ?: Settings()
    private val startedAt = System.currentTimeMillis()

    private class Edge(val down: Boolean, val timeMs: Double)
    private val edges = ConcurrentLinkedQueue<Edge>()

    private val keyer = IambicKeyer(initial.sendWpm.toDouble())
    private var decoder = SendDecoder(initial.sendWpm.toDouble())

    var iambic by mutableStateOf(initial.sendIambic)
        private set
    var keyerWpm by mutableIntStateOf(initial.sendWpm)
        private set
    var swapPaddles by mutableStateOf(initial.swapPaddles)
        private set
    var target by mutableStateOf("")
        private set
    var decoded by mutableStateOf("")
        private set
    var pending by mutableStateOf("")
        private set
    var liveWpm by mutableIntStateOf(0)
        private set
    var score by mutableStateOf<TimingScore?>(null)
        private set
    var keyIsDown by mutableStateOf(false)
        private set

    private var activeSeconds = 0
    private var lastActivity = 0L
    private var resumed = false
    private var closed = false

    init {
        c.listening.stop()
        engine.listener = this
        engine.pitchHz = initial.pitchHz.toDouble()
        engine.conditions = BandConditions.CLEAN
        engine.stopPlayback()
        engine.setKeyer(if (iambic) keyer else null)
        newTarget()
        scope.launch {
            var lastSecond = SystemClock.elapsedRealtime()
            while (isActive) {
                pump()
                val now = SystemClock.elapsedRealtime()
                if (now - lastSecond >= 1000) {
                    lastSecond = now
                    if (resumed && now - lastActivity < 5000) activeSeconds++
                }
                delay(16)
            }
        }
    }

    private fun clock(): Double = if (iambic) engine.clockMs else SystemClock.uptimeMillis().toDouble()

    private fun pump() {
        var changed = false
        while (true) {
            val e = edges.poll() ?: break
            if (e.down) decoder.keyDown(e.timeMs) else decoder.keyUp(e.timeMs)
            keyIsDown = e.down
            changed = true
        }
        decoder.tick(clock())
        val d = decoder.decoded
        if (changed || d != decoded) {
            lastActivity = SystemClock.elapsedRealtime()
            decoded = d
            score = decoder.score().takeIf { it.marks >= 4 }
            liveWpm = decoder.estimatedWpm.toInt()
        }
        pending = decoder.pending
    }

    /** Straight key. [uptimeMs] is the touch event's own timestamp, which is far steadier than "now". */
    fun straight(down: Boolean, uptimeMs: Long) {
        if (iambic) return
        engine.setKey(down)
        edges.add(Edge(down, uptimeMs.toDouble()))
    }

    fun paddle(dit: Boolean, pressed: Boolean) {
        if (!iambic) return
        val isDit = if (swapPaddles) !dit else dit
        if (isDit) keyer.ditPressed = pressed else keyer.dahPressed = pressed
    }

    /** From the audio thread: the keyer closed or opened the key. */
    override fun onKeyerEdge(down: Boolean, timeMs: Double) {
        edges.add(Edge(down, timeMs))
    }

    fun setMode(useIambic: Boolean) {
        if (useIambic == iambic) return
        iambic = useIambic
        keyer.reset()
        engine.setKey(false)
        engine.setKeyer(if (useIambic) keyer else null)
        clear()
        scope.launch { c.repo.updateSettings { it.copy(sendIambic = useIambic) } }
    }

    fun changeKeyerWpm(wpm: Int) {
        keyerWpm = wpm.coerceIn(5, 35)
        keyer.wpm = keyerWpm.toDouble()
        scope.launch { c.repo.updateSettings { it.copy(sendWpm = keyerWpm) } }
    }

    fun toggleSwap() {
        swapPaddles = !swapPaddles
        keyer.reset()
        scope.launch { c.repo.updateSettings { it.copy(swapPaddles = swapPaddles) } }
    }

    fun clear() {
        edges.clear()
        decoder = SendDecoder(if (iambic) keyerWpm.toDouble() else 15.0)
        decoded = ""
        pending = ""
        score = null
        liveWpm = 0
        keyIsDown = false
    }

    fun newTarget() {
        val level = initial.kochLevel
        target = SendDrill.next(DrillContext(level, initial.groupLength, System.currentTimeMillis(), rng) { c.repo.items.value[it] }).send
    }

    fun onResume() {
        resumed = true
        c.player.start()
    }

    fun onPause() {
        resumed = false
        keyer.reset()
        engine.setKey(false)
        c.player.stop()
    }

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        keyer.reset()
        engine.setKey(false)
        engine.setKeyer(null)
        if (engine.listener === this) engine.listener = null
        if (activeSeconds >= 20) {
            val s = score
            val session = SessionEntity(
                startedAt = startedAt, day = Repository.today(), drillId = SendDrill.id, seconds = activeSeconds,
                charWpm = (s?.wpm ?: keyerWpm.toDouble()), effWpm = (s?.wpm ?: keyerWpm.toDouble()),
                total = if (s == null) 0 else 100, correct = s?.score ?: 0, level = initial.kochLevel,
                band = BandConditions.CLEAN.name,
            )
            c.scope.launch {
                c.repo.addSession(session)
                c.repo.updateSettings { it.copy(lastDrill = SendDrill.id) }
            }
        }
    }
}
