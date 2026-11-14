package com.cwtrainer.core.drill

import com.cwtrainer.core.content.Callsigns
import com.cwtrainer.core.content.Content
import com.cwtrainer.core.content.Content.pick
import com.cwtrainer.core.content.Qso
import com.cwtrainer.core.koch.Koch
import com.cwtrainer.core.morse.MorseCode
import com.cwtrainer.core.srs.SrsItem
import com.cwtrainer.core.srs.SrsScheduler
import kotlin.random.Random

/** How the trainee answers. The UI has one screen per mode, not per drill. */
enum class DrillMode { COPY, RECOGNISE, HEAD_COPY, SEND }

enum class Keyboard { UNLOCKED, FULL }

/**
 * One thing to play and how to judge the answer.
 *
 * @param send text to key (prosigns in angle brackets)
 * @param itemIds spaced-repetition items judged as a whole by this prompt (words, prosigns, terms).
 *        Character items are always updated from the per-character comparison in COPY mode.
 * @param choices buttons for RECOGNISE mode
 * @param hint shown after the answer (meaning of a Q-code, etc.)
 */
data class Prompt(
    val send: String,
    val itemIds: List<String> = emptyList(),
    val choices: List<String> = emptyList(),
    val hint: String = "",
    val kind: String = "",
)

/** Everything a drill may look at when choosing the next prompt. */
class DrillContext(
    val level: Int,
    val groupLength: Int,
    val now: Long,
    val rng: Random,
    private val lookup: (String) -> SrsItem?,
) {
    val unlocked: List<String> = Koch.unlocked(level)
    fun item(id: String): SrsItem = lookup(id) ?: SrsItem(id)
    fun canSpell(text: String): Boolean {
        val have = unlocked.toSet()
        return MorseCode.tokenize(text).all { it == " " || it in have }
    }
}

/**
 * A pluggable drill. To add one (v2: pile-up, QSO role-play, Turkish characters):
 * implement this interface and add the object to [Drills.all]. The home screen, the
 * "Continue training" recommender, history and spaced repetition pick it up from there.
 */
interface DrillType {
    val id: String
    val title: String
    val summary: String
    val mode: DrillMode
    val keyboard: Keyboard get() = Keyboard.UNLOCKED

    /** Uses the realistic-band and fist settings (i.e. it is a receive drill). */
    val usesBand: Boolean get() = true

    /** null when available, otherwise a short reason shown on the locked card. */
    fun lockReason(level: Int): String?

    fun next(ctx: DrillContext): Prompt
}

object ItemIds {
    fun char(symbol: String) = "c:$symbol"
    fun word(word: String) = "w:$word"
    fun prosign(symbol: String) = "p:$symbol"
    fun term(text: String) = "r:$text"
    fun isChar(id: String) = id.startsWith("c:")
}

private const val NEED_LETTERS = "Unlocks when all letters are learned"

/** 1. Core Koch receive drill: random groups from the unlocked characters. */
object ReceiveDrill : DrillType {
    override val id = "receive"
    override val title = "Receive"
    override val summary = "Copy random groups and type what you hear"
    override val mode = DrillMode.COPY
    override fun lockReason(level: Int): String? = null

    override fun next(ctx: DrillContext): Prompt {
        val newest = Koch.newest(ctx.level)
        val items = ctx.unlocked.map { ctx.item(ItemIds.char(it)) }
        val picked = SrsScheduler.pickMany(items, ctx.groupLength.coerceIn(1, 10), ctx.now, ctx.rng) {
            if (it.id == ItemIds.char(newest)) 1.6 else 1.0
        }
        return Prompt(send = picked.joinToString("") { it.id.removePrefix("c:") }, kind = "group")
    }
}

/** 3. One character, tap it fast. Reaction time feeds spaced repetition. */
object CharRecognitionDrill : DrillType {
    override val id = "chars"
    override val title = "Instant characters"
    override val summary = "One character plays, tap it as fast as you can"
    override val mode = DrillMode.RECOGNISE
    override fun lockReason(level: Int): String? = null

    override fun next(ctx: DrillContext): Prompt {
        val items = ctx.unlocked.map { ctx.item(ItemIds.char(it)) }
        val p = SrsScheduler.pick(items, ctx.now, ctx.rng)
        return Prompt(send = p.id.removePrefix("c:"), itemIds = listOf(p.id), choices = ctx.unlocked, kind = "char")
    }
}

/** 4. Whole-word sound patterns. */
object WordRecognitionDrill : DrillType {
    override val id = "words"
    override val title = "Instant words"
    override val summary = "Top 100 CW and English words as whole sounds"
    override val mode = DrillMode.RECOGNISE
    override fun lockReason(level: Int): String? = if (Koch.allLettersLearned(level)) null else NEED_LETTERS

    override fun next(ctx: DrillContext): Prompt {
        val pool = Content.words.filter { ctx.canSpell(it) }.ifEmpty { Content.words }
        val p = SrsScheduler.pick(pool.map { ctx.item(ItemIds.word(it)) }, ctx.now, ctx.rng)
        val word = p.id.removePrefix("w:")
        // Distractors of similar length, so the answer cannot be guessed from duration alone.
        val near = pool.filter { it != word && kotlin.math.abs(it.length - word.length) <= 1 }.shuffled(ctx.rng)
        val rest = pool.filter { it != word && it !in near }.shuffled(ctx.rng)
        val choices = (listOf(word) + (near + rest).take(5)).shuffled(ctx.rng)
        return Prompt(send = word, itemIds = listOf(p.id), choices = choices, kind = "word")
    }
}

/** 5. Prosigns, sent as single run-together characters. */
object ProsignDrill : DrillType {
    override val id = "prosigns"
    override val title = "Prosigns"
    override val summary = "AR, SK, BT, KN, BK as single sounds"
    override val mode = DrillMode.RECOGNISE
    override fun lockReason(level: Int): String? = if (Koch.allLettersLearned(level)) null else NEED_LETTERS

    override fun next(ctx: DrillContext): Prompt {
        val all = MorseCode.prosigns
        val p = SrsScheduler.pick(all.map { ctx.item(ItemIds.prosign(it.symbol)) }, ctx.now, ctx.rng)
        val sym = p.id.removePrefix("p:")
        return Prompt(
            send = sym, itemIds = listOf(p.id), choices = all.map { it.symbol },
            hint = all.first { it.symbol == sym }.meaning, kind = "prosign",
        )
    }
}

/** 6. Callsigns, Q-codes, abbreviations and short QSO exchanges, typed. */
object RealWorldDrill : DrillType {
    override val id = "realworld"
    override val title = "Real-world copy"
    override val summary = "Callsigns, Q-codes, abbreviations and QSO exchanges"
    override val mode = DrillMode.COPY
    override val keyboard = Keyboard.FULL
    override fun lockReason(level: Int): String? = if (Koch.allLearned(level)) null else "Unlocks when all characters are learned"

    override fun next(ctx: DrillContext): Prompt = when (ctx.rng.nextInt(10)) {
        0, 1, 2 -> Prompt(send = Callsigns.random(ctx.rng), kind = "callsign")
        3, 4 -> term(ctx, Content.qCodes, "Q-code")
        5 -> term(ctx, Content.abbreviations, "abbreviation")
        6 -> Prompt(send = Qso.shortLine(ctx.rng), kind = "exchange")
        else -> Prompt(send = Qso.exchange(ctx.rng), kind = "QSO")
    }

    private fun term(ctx: DrillContext, terms: List<Content.Term>, kind: String): Prompt {
        val p = SrsScheduler.pick(terms.map { ctx.item(ItemIds.term(it.text)) }, ctx.now, ctx.rng)
        val t = terms.first { ItemIds.term(it.text) == p.id }
        return Prompt(send = t.text, itemIds = listOf(p.id), hint = t.meaning, kind = kind)
    }
}

/** 2. Head copy: listen, reveal, mark yourself. Material widens as characters unlock. */
object HeadCopyDrill : DrillType {
    override val id = "headcopy"
    override val title = "Head copy"
    override val summary = "Listen without typing, reveal, mark yourself"
    override val mode = DrillMode.HEAD_COPY
    const val MIN_LEVEL = 6
    override fun lockReason(level: Int): String? =
        if (level >= MIN_LEVEL) null else "Unlocks at $MIN_LEVEL characters"

    override fun next(ctx: DrillContext): Prompt {
        val spellable = Content.words.filter { ctx.canSpell(it) }
        val full = Koch.allLearned(ctx.level)
        val letters = Koch.allLettersLearned(ctx.level)
        val roll = ctx.rng.nextInt(10)
        return when {
            full && roll < 3 -> Prompt(send = Content.sentences.pick(ctx.rng), kind = "sentence")
            full && roll < 5 -> Prompt(send = Qso.shortLine(ctx.rng), kind = "exchange")
            full && roll < 6 -> Prompt(send = Callsigns.random(ctx.rng), kind = "callsign")
            letters && roll < 3 -> Prompt(send = Content.sentences.filter { ctx.canSpell(it) }.ifEmpty { Content.sentences }.pick(ctx.rng), kind = "sentence")
            spellable.size >= 6 && (letters || roll < 6) -> {
                val p = SrsScheduler.pick(spellable.map { ctx.item(ItemIds.word(it)) }, ctx.now, ctx.rng)
                Prompt(send = p.id.removePrefix("w:"), itemIds = listOf(p.id), kind = "word")
            }
            else -> {
                // Shorter than the typed groups: holding characters in your head is the hard part.
                val n = (ctx.groupLength - 2).coerceIn(2, 4)
                val items = ctx.unlocked.map { ctx.item(ItemIds.char(it)) }
                Prompt(send = SrsScheduler.pickMany(items, n, ctx.now, ctx.rng).joinToString("") { it.id.removePrefix("c:") }, kind = "group")
            }
        }
    }
}

/** 9. Sending practice. The prompt is a suggestion of what to send. */
object SendDrill : DrillType {
    override val id = "send"
    override val title = "Send practice"
    override val summary = "Touch straight key or iambic paddle, decoded live"
    override val mode = DrillMode.SEND
    override val usesBand = false
    override fun lockReason(level: Int): String? = null

    override fun next(ctx: DrillContext): Prompt {
        val spellable = Content.words.filter { ctx.canSpell(it) }
        return if (spellable.size >= 6 && ctx.rng.nextBoolean()) {
            Prompt(send = List(3) { spellable.pick(ctx.rng) }.joinToString(" "), kind = "words")
        } else {
            Prompt(send = List(2) { ReceiveDrill.next(ctx).send }.joinToString(" "), kind = "groups")
        }
    }
}

object Drills {
    val all: List<DrillType> = listOf(
        ReceiveDrill, HeadCopyDrill, CharRecognitionDrill, WordRecognitionDrill,
        ProsignDrill, RealWorldDrill, SendDrill,
    )

    fun byId(id: String): DrillType = all.firstOrNull { it.id == id } ?: ReceiveDrill

    /** Ids of every spaced-repetition item that exists at this level, for seeding and stats. */
    fun itemIdsFor(drill: DrillType, level: Int): List<String> = when (drill) {
        ReceiveDrill, CharRecognitionDrill -> Koch.unlocked(level).map { ItemIds.char(it) }
        WordRecognitionDrill -> Content.words.map { ItemIds.word(it) }
        ProsignDrill -> MorseCode.prosigns.map { ItemIds.prosign(it.symbol) }
        RealWorldDrill -> (Content.qCodes + Content.abbreviations).map { ItemIds.term(it.text) }
        else -> emptyList()
    }
}
