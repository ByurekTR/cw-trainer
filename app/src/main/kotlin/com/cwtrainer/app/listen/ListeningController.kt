package com.cwtrainer.app.listen

import com.cwtrainer.app.audio.AudioPlayer
import com.cwtrainer.app.data.Repository
import com.cwtrainer.app.data.SessionEntity
import com.cwtrainer.app.data.Settings
import com.cwtrainer.core.audio.BandConditions
import com.cwtrainer.core.audio.CwEngine
import com.cwtrainer.core.content.Qso
import com.cwtrainer.core.koch.Koch
import com.cwtrainer.core.listen.ListenItem
import com.cwtrainer.core.listen.PlaylistGenerator
import com.cwtrainer.core.morse.FistModel
import com.cwtrainer.core.morse.Keyer
import com.cwtrainer.core.morse.Timing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class ListenStatus { IDLE, PLAYING, PAUSED, FINISHED }

data class ListenState(
    val status: ListenStatus = ListenStatus.IDLE,
    val elapsedSec: Int = 0,
    val totalSec: Int = 0,
    val itemsPlayed: Int = 0,
    val reveal: Boolean = false,
    /** Texts already heard, newest last. Filled only when reveal is on. */
    val revealed: List<String> = emptyList(),
) {
    val active: Boolean get() = status == ListenStatus.PLAYING || status == ListenStatus.PAUSED
}

/**
 * The listening session itself: picks items from the spaced-repetition queue, plays them
 * through the shared engine with a gap after each, and keeps the clock. It knows nothing about
 * Android; [ListeningService] wraps it with the foreground service, notification, audio focus
 * and wake lock, and the screen simply observes [state].
 *
 * Lives for the whole process (see AppContainer). All methods are called on the main thread.
 */
class ListeningController(
    private val player: AudioPlayer,
    private val repo: Repository,
    private val scope: CoroutineScope,
) : CwEngine.Listener {
    private val engine = player.engine
    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state

    val isActive: Boolean get() = _state.value.active

    private var generator: PlaylistGenerator? = null
    private var timing = Timing(20.0)
    private var band = BandConditions.CLEAN
    private var fistAllowed = false
    private var gapMs = 3000L
    private var settings = Settings()
    private var startedAt = 0L
    private val rng = Random(System.nanoTime())
    private val qrmRng = Random(System.nanoTime() + 31)

    private var current: ListenItem? = null
    private var playId = -1L
    private var inGap = false
    private var gapJob: Job? = null
    private var clockJob: Job? = null

    fun start(minutes: Int, gapSec: Int, reveal: Boolean) {
        if (isActive) stop()
        val s = repo.settings.value ?: Settings()
        settings = s
        val level = s.kochLevel
        timing = Timing(s.charWpm.toDouble(), s.effectiveWpm(level))
        band = BandConditions.byName(s.band)
        fistAllowed = s.fist && Koch.allLettersLearned(level)
        gapMs = gapSec.coerceIn(0, 60) * 1000L
        val items = repo.items.value
        generator = PlaylistGenerator(level, s.groupLength, System.currentTimeMillis(), rng) { items[it] }
        startedAt = System.currentTimeMillis()

        engine.listener = this
        engine.setKeyer(null)
        engine.setKey(false)
        engine.pitchHz = s.pitchHz.toDouble()
        engine.conditions = band
        engine.volume = NORMAL_VOLUME
        engine.qrmText = { Qso.background(qrmRng) }

        _state.value = ListenState(ListenStatus.PLAYING, 0, minutes * 60, 0, reveal, emptyList())
        player.start()
        startClock()
        playNext()
    }

    private fun startClock() {
        clockJob?.cancel()
        clockJob = scope.launch {
            while (true) {
                delay(1000)
                val st = _state.value
                if (st.status == ListenStatus.PLAYING) _state.value = st.copy(elapsedSec = st.elapsedSec + 1)
            }
        }
    }

    private fun playNext() {
        val st = _state.value
        if (st.status != ListenStatus.PLAYING) return
        if (st.elapsedSec >= st.totalSec) { end(ListenStatus.FINISHED); return }
        val item = generator?.next() ?: return
        current = item
        playCurrent()
    }

    private fun playCurrent() {
        val item = current ?: return
        inGap = false
        val fist = if (fistAllowed) FistModel.personalities[rng.nextInt(FistModel.personalities.size)] else FistModel.MACHINE
        playId = engine.play(Keyer.plan(item.text, timing, fist, rng), leadInMs = 300.0)
    }

    /** Audio thread: the item has finished sounding. */
    override fun onFinished(playId: Long) {
        scope.launch {
            val st = _state.value
            if (playId != this@ListeningController.playId || st.status != ListenStatus.PLAYING) return@launch
            val text = current?.text?.replace("<", "")?.replace(">", "")
            _state.value = st.copy(
                itemsPlayed = st.itemsPlayed + 1,
                revealed = if (st.reveal && text != null) (st.revealed + text).takeLast(MAX_REVEALED) else st.revealed,
            )
            startGap()
        }
    }

    private fun startGap() {
        inGap = true
        gapJob?.cancel()
        gapJob = scope.launch {
            delay(gapMs)
            inGap = false
            playNext()
        }
    }

    fun pause() {
        val st = _state.value
        if (st.status != ListenStatus.PLAYING) return
        gapJob?.cancel()
        _state.value = st.copy(status = ListenStatus.PAUSED)
        player.stop()
    }

    fun resume() {
        val st = _state.value
        if (st.status != ListenStatus.PAUSED) return
        // Another screen may have used the engine while we were paused: take it back.
        engine.listener = this
        engine.setKeyer(null)
        engine.conditions = band
        _state.value = st.copy(status = ListenStatus.PLAYING)
        player.start()
        if (inGap) playNext() else playCurrent()   // an interrupted item starts again from the top
    }

    fun skip() {
        if (!isActive) return
        gapJob?.cancel()
        inGap = true
        if (_state.value.status == ListenStatus.PLAYING) {
            engine.stopPlayback()
            playNext()
        }
    }

    /** Lower the level while another app speaks briefly (navigation prompts and the like). */
    fun duck(on: Boolean) {
        engine.volume = if (on) DUCKED_VOLUME else NORMAL_VOLUME
    }

    fun stop() {
        if (isActive) end(ListenStatus.IDLE)
    }

    /** Clears a FINISHED state once the screen has shown it. */
    fun acknowledge() {
        if (_state.value.status == ListenStatus.FINISHED) _state.value = ListenState()
    }

    private fun end(status: ListenStatus) {
        val st = _state.value
        gapJob?.cancel()
        clockJob?.cancel()
        player.stop()
        engine.volume = NORMAL_VOLUME
        engine.conditions = BandConditions.CLEAN
        if (engine.listener === this) engine.listener = null
        _state.value = st.copy(status = status)
        if (st.elapsedSec >= 20) {
            val session = SessionEntity(
                startedAt = startedAt, day = Repository.today(), drillId = DRILL_ID, seconds = st.elapsedSec,
                charWpm = timing.charWpm, effWpm = timing.effective, total = 0, correct = 0,
                level = settings.kochLevel, band = band.name,
            )
            scope.launch { repo.addSession(session) }
        }
    }

    companion object {
        const val DRILL_ID = "listening"
        private const val MAX_REVEALED = 30
        private const val NORMAL_VOLUME = 0.9
        private const val DUCKED_VOLUME = 0.2
    }
}
