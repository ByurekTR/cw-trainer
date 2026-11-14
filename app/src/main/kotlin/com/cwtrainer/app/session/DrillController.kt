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
import com.cwtrainer.core.content.Qso
import com.cwtrainer.core.drill.DrillContext
import com.cwtrainer.core.drill.DrillMode
import com.cwtrainer.core.drill.DrillType
import com.cwtrainer.core.drill.Drills
import com.cwtrainer.core.drill.ItemIds
import com.cwtrainer.core.drill.Prompt
import com.cwtrainer.core.drill.ReceiveDrill
import com.cwtrainer.core.drill.Scoring
import com.cwtrainer.core.drill.SymbolResult
import com.cwtrainer.core.drill.WordRecognitionDrill
import com.cwtrainer.core.koch.Koch
import com.cwtrainer.core.morse.FistModel
import com.cwtrainer.core.morse.Keyer
import com.cwtrainer.core.morse.MorseCode
import com.cwtrainer.core.morse.Timing
import com.cwtrainer.core.srs.Sm2
import com.cwtrainer.core.srs.SrsItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class Phase { READY, ACTIVE, FEEDBACK, DONE }

data class SymbolStat(val symbol: String, val correct: Int, val total: Int) {
    val accuracy: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
}

data class Summary(
    val seconds: Int,
    val total: Int,
    val correct: Int,
    val symbols: List<SymbolStat>,
    /** Character unlocked by this session, if any. */
    val unlocked: String?,
    /** Why nothing was unlocked (receive drill only), or null. */
    val unlockHint: String?,
    val charWpm: Double,
    val effWpm: Double,
) {
    val accuracy: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
}

/**
 * Runs one session of any receive-type drill (COPY, RECOGNISE or HEAD_COPY mode).
 * Created when the drill screen opens and closed when it leaves; all state the UI shows is
 * Compose state on this object.
 */
class DrillController(
    private val c: AppContainer,
    drillId: String,
    /** Fixed session length (the widget's quick drill); null = the length from Settings. */
    sessionSecondsOverride: Int? = null,
) : CwEngine.Listener {
    val drill: DrillType = Drills.byId(drillId)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = c.player.engine
    private val rng = Random(System.nanoTime())
    private val qrmRng = Random(System.nanoTime() + 17)

    private val initial: Settings = c.repo.settings.value ?: Settings()
    /** The Koch level is fixed for the whole session. */
    val level: Int = initial.kochLevel
    val charWpm: Double = initial.charWpm.toDouble()
    val effWpm: Double = if (drill === WordRecognitionDrill) charWpm else initial.effectiveWpm(level)
    private val groupLength = initial.groupLength
    private val targetSeconds = sessionSecondsOverride ?: (initial.sessionMinutes * 60)
    private val fistAllowed = initial.fist && Koch.allLettersLearned(level)

    private val items = HashMap<String, SrsItem>(c.repo.items.value)
    private val dirty = HashMap<String, SrsItem>()
    private val stats = LinkedHashMap<String, IntArray>()
    private val startedAt = System.currentTimeMillis()

    var band by mutableStateOf(BandConditions.byName(initial.band))
        private set
    var phase by mutableStateOf(Phase.READY)
        private set
    var prompt by mutableStateOf<Prompt?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var typed by mutableStateOf(listOf<String>())
        private set
    var results by mutableStateOf(listOf<SymbolResult>())
        private set
    var chosen by mutableStateOf<String?>(null)
        private set
    var lastCorrect by mutableStateOf(false)
        private set
    var lastRtMs by mutableStateOf<Long?>(null)
        private set
    var revealed by mutableStateOf(false)
        private set
    var fistName by mutableStateOf<String?>(null)
        private set
    var elapsed by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    var correct by mutableIntStateOf(0)
        private set
    var summary by mutableStateOf<Summary?>(null)
        private set

    val sessionSeconds: Int get() = targetSeconds
    val timeUp: Boolean get() = elapsed >= targetSeconds

    /** The newest Koch character if it has never been drilled: the UI introduces it first. */
    val newCharacter: String? =
        Koch.newest(level).takeIf { drill === ReceiveDrill && (items[ItemIds.char(it)]?.total ?: 0) == 0 }

    private var playId = -1L
    private var finishedAt = 0L
    private var replayed = false
    private var resumed = false
    private var closed = false

    init {
        c.listening.stop()   // one thing at a time on the audio engine
        engine.listener = this
        engine.pitchHz = initial.pitchHz.toDouble()
        engine.conditions = BandConditions.CLEAN
        engine.setKeyer(null)
        engine.qrmText = { Qso.background(qrmRng) }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    fun onResume() {
        resumed = true
        c.player.start()
        if (phase == Phase.ACTIVE) replay(countAsReplay = false)
    }

    fun onPause() {
        resumed = false
        playing = false
        c.player.stop()
    }

    /** Called once a second by the screen. Only time spent actually drilling counts. */
    fun tick() {
        if (resumed && (phase == Phase.ACTIVE || phase == Phase.FEEDBACK)) elapsed++
    }

    fun close() {
        if (closed) return
        closed = true
        if (phase != Phase.DONE) finish()
        scope.cancel()
        if (engine.listener === this) engine.listener = null
        engine.stopPlayback()
        engine.conditions = BandConditions.CLEAN
    }

    // ---- audio -----------------------------------------------------------------------------

    private fun timing(): Timing = Timing(charWpm, effWpm)

    private fun play(text: String, fist: FistModel = FistModel.MACHINE) {
        c.player.start()
        finishedAt = 0L
        playing = true
        playId = engine.play(Keyer.plan(text, timing(), fist, rng))
    }

    override fun onFinished(playId: Long) {
        val at = SystemClock.elapsedRealtime() + c.player.latencyMs
        scope.launch {
            if (playId == this@DrillController.playId) {
                finishedAt = at
                playing = false
            }
        }
    }

    /** Plays one symbol on its own, clean, for the "new character" introduction. */
    fun preview(symbol: String) {
        engine.conditions = BandConditions.CLEAN
        play("$symbol $symbol $symbol")
    }

    fun cycleBand() {
        val all = BandConditions.presets
        band = all[(all.indexOf(band) + 1) % all.size]
        if (phase != Phase.READY) engine.conditions = band
        scope.launch { c.repo.updateSettings { it.copy(band = band.name) } }
    }

    // ---- flow ------------------------------------------------------------------------------

    fun start() {
        if (phase != Phase.READY) return
        engine.conditions = if (drill.usesBand) band else BandConditions.CLEAN
        next()
    }

    fun next() {
        if (phase == Phase.DONE) return
        if (timeUp) { finish(); return }
        val p = drill.next(DrillContext(level, groupLength, System.currentTimeMillis(), rng) { items[it] })
        prompt = p
        typed = emptyList()
        results = emptyList()
        chosen = null
        lastRtMs = null
        revealed = false
        replayed = false
        val fist = if (fistAllowed) FistModel.personalities[rng.nextInt(FistModel.personalities.size)] else FistModel.MACHINE
        fistName = if (fist.isPerfect) null else fist.name
        currentFist = fist
        phase = Phase.ACTIVE
        play(p.send, fist)
    }

    private var currentFist: FistModel = FistModel.MACHINE

    fun replay(countAsReplay: Boolean = true) {
        val p = prompt ?: return
        if (countAsReplay) replayed = true
        play(p.send, currentFist)
    }

    // ---- COPY mode -------------------------------------------------------------------------

    fun type(symbol: String) {
        if (phase == Phase.ACTIVE && typed.size < 200) typed = typed + symbol
    }

    fun backspace() {
        if (phase == Phase.ACTIVE && typed.isNotEmpty()) typed = typed.dropLast(1)
    }

    fun check() {
        val p = prompt ?: return
        if (phase != Phase.ACTIVE || drill.mode != DrillMode.COPY) return
        engine.stopPlayback()
        playing = false
        val r = Scoring.align(MorseCode.tokenize(p.send), typed)
        results = r
        for (s in r) {
            record(s.symbol, s.correct)
            review(if (MorseCode.isProsign(s.symbol)) ItemIds.prosign(s.symbol) else ItemIds.char(s.symbol), s.correct, null)
        }
        val allOk = r.all { it.correct }
        for (id in p.itemIds) review(id, allOk, null)
        lastCorrect = allOk
        total += r.size
        correct += r.count { it.correct }
        flush()
        phase = Phase.FEEDBACK
    }

    // ---- RECOGNISE mode --------------------------------------------------------------------

    fun choose(choice: String) {
        val p = prompt ?: return
        if (phase != Phase.ACTIVE || drill.mode != DrillMode.RECOGNISE) return
        val now = SystemClock.elapsedRealtime()
        val ok = choice == p.send
        val rt: Long = when {
            replayed -> Sm2.SLOW_MS + 1
            finishedAt == 0L -> 1L                       // answered before the sound had even ended
            else -> (now - finishedAt).coerceAtLeast(1L)
        }
        chosen = choice
        lastCorrect = ok
        lastRtMs = if (ok && !replayed) rt else null
        record(p.send, ok)
        for (id in p.itemIds) review(id, ok, rt)
        total += 1
        if (ok) correct += 1
        flush()
        phase = Phase.FEEDBACK
        if (ok) {
            engine.stopPlayback()
            playing = false
            scope.launch {
                delay(450)
                if (phase == Phase.FEEDBACK && prompt === p) next()
            }
        } else {
            // Let the right answer sink in: hear it again while it is shown.
            play(p.send, currentFist)
        }
    }

    // ---- HEAD_COPY mode --------------------------------------------------------------------

    fun reveal() {
        if (phase == Phase.ACTIVE && drill.mode == DrillMode.HEAD_COPY) revealed = true
    }

    fun mark(ok: Boolean) {
        val p = prompt ?: return
        if (phase != Phase.ACTIVE || !revealed) return
        engine.stopPlayback()
        playing = false
        lastCorrect = ok
        record(p.kind.ifEmpty { "item" }, ok)
        for (id in p.itemIds) review(id, ok, null)
        total += 1
        if (ok) correct += 1
        flush()
        next()
    }

    // ---- bookkeeping -----------------------------------------------------------------------

    private fun record(symbol: String, ok: Boolean) {
        val s = stats.getOrPut(symbol) { IntArray(2) }
        if (ok) s[0]++
        s[1]++
    }

    private fun review(id: String, ok: Boolean, rtMs: Long?) {
        val updated = Sm2.review(items[id] ?: SrsItem(id), Sm2.quality(ok, rtMs), System.currentTimeMillis(), rtMs)
        items[id] = updated
        dirty[id] = updated
    }

    private fun flush() {
        if (dirty.isEmpty()) return
        val batch = dirty.values.toList()
        dirty.clear()
        c.scope.launch { c.repo.saveItems(batch) }
    }

    fun finish() {
        if (phase == Phase.DONE) return
        engine.stopPlayback()
        playing = false
        flush()
        val acc = if (total == 0) 0.0 else correct.toDouble() / total
        var unlocked: String? = null
        var hint: String? = null
        if (drill === ReceiveDrill && !Koch.allLearned(level)) {
            if (Koch.shouldUnlockNext(level, acc, elapsed, total)) {
                unlocked = Koch.next(level)
            } else {
                hint = when {
                    elapsed < Koch.UNLOCK_SESSION_SECONDS -> "The next character unlocks after a full 5-minute session at 90 % or better."
                    acc < Koch.UNLOCK_ACCURACY -> "You need 90 % to unlock the next character. Stay on this lesson a little longer."
                    else -> "Not enough characters were copied to judge this session."
                }
            }
        }
        summary = Summary(
            seconds = elapsed, total = total, correct = correct,
            symbols = stats.map { SymbolStat(it.key, it.value[0], it.value[1]) }.sortedBy { it.accuracy },
            unlocked = unlocked, unlockHint = hint, charWpm = charWpm, effWpm = effWpm,
        )
        phase = Phase.DONE

        val worthSaving = elapsed >= 20 && total > 0
        val newLevel = if (unlocked != null) level + 1 else level
        val session = SessionEntity(
            startedAt = startedAt, day = Repository.today(), drillId = drill.id, seconds = elapsed,
            charWpm = charWpm, effWpm = effWpm, total = total, correct = correct, level = level,
            band = if (drill.usesBand) band.name else BandConditions.CLEAN.name,
        )
        c.scope.launch {
            if (worthSaving) {
                c.repo.addSession(session)
                c.repo.updateSettings { it.copy(kochLevel = maxOf(it.kochLevel, newLevel), lastDrill = drill.id) }
            }
        }
    }
}
