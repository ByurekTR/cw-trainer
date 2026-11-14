package com.cwtrainer.core.content

import com.cwtrainer.core.content.Content.pick
import kotlin.random.Random

/** Short simulated on-air exchanges, one transmission at a time. */
object Qso {
    private fun rst(rng: Random): String = "5" + (5 + rng.nextInt(5)) + "9"

    fun cq(rng: Random): String {
        val me = Callsigns.random(rng)
        return if (rng.nextBoolean()) "CQ CQ CQ DE $me $me K" else "CQ CQ DE $me $me $me <AR> K"
    }

    fun answer(rng: Random): String {
        val a = Callsigns.random(rng)
        val b = Callsigns.random(rng)
        return "$a DE $b $b <KN>"
    }

    fun report(rng: Random): String {
        val a = Callsigns.random(rng)
        val b = Callsigns.random(rng)
        val r = rst(rng)
        val name = Content.names.pick(rng)
        val qth = Content.places.pick(rng)
        val greet = listOf("GM", "GA", "GE").pick(rng)
        return "$a DE $b $greet OM TNX FER CALL <BT> UR RST $r $r <BT> NAME $name $name <BT> QTH $qth $qth <BT> HW? $a DE $b <KN>"
    }

    fun station(rng: Random): String {
        val rig = Content.rigs.pick(rng)
        val ant = Content.antennas.pick(rng)
        val pwr = listOf("5", "10", "50", "100").pick(rng)
        val wx = Content.weather.pick(rng)
        return "RIG HR IS $rig PWR $pwr WATTS <BT> ANT IS $ant <BT> WX HR $wx <BT> HW CPY?"
    }

    fun signOff(rng: Random): String {
        val a = Callsigns.random(rng)
        val b = Callsigns.random(rng)
        return "TNX FER FB QSO OM <BT> 73 ES CUL <AR> $a DE $b <SK>"
    }

    fun contest(rng: Random): String {
        val a = Callsigns.random(rng)
        val nr = (1 + rng.nextInt(999)).toString().padStart(3, '0')
        return if (rng.nextBoolean()) "$a 5NN $nr" else "$a TU 5NN ${1 + rng.nextInt(40)}"
    }

    fun shortLine(rng: Random): String = when (rng.nextInt(6)) {
        0 -> "UR RST ${rst(rng)}"
        1 -> "NAME ${Content.names.pick(rng)}"
        2 -> "QTH ${Content.places.pick(rng)}"
        3 -> "RIG ${Content.rigs.pick(rng)}"
        4 -> "ANT ${Content.antennas.pick(rng)}"
        else -> "WX ${Content.weather.pick(rng)}"
    }

    /** Any one transmission from a typical contact. */
    fun exchange(rng: Random): String = when (rng.nextInt(6)) {
        0 -> cq(rng)
        1 -> answer(rng)
        2 -> report(rng)
        3 -> station(rng)
        4 -> signOff(rng)
        else -> contest(rng)
    }

    /** Text for the interfering (QRM) station. */
    fun background(rng: Random): String = when (rng.nextInt(4)) {
        0 -> cq(rng)
        1 -> contest(rng) + " " + contest(rng)
        2 -> report(rng)
        else -> "CQ TEST DE ${Callsigns.random(rng)} ${Callsigns.random(rng)}"
    }
}
