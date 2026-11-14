package com.cwtrainer.core.content

import kotlin.random.Random

/** Word lists and on-air vocabulary. Everything is upper case, as sent. */
object Content {
    /** 50 words and abbreviations heard in almost every CW contact. */
    val cwWords: List<String> = listOf(
        "CQ", "DE", "TNX", "NAME", "RIG", "ANT", "WX", "HR", "UR", "ES",
        "FB", "OM", "TU", "73", "88", "RST", "AGN", "PSE", "RPT", "GM",
        "GA", "GE", "GN", "DX", "HW", "CPY", "CUL", "SRI", "VY", "NR",
        "PWR", "WATTS", "DR", "OP", "YL", "XYL", "HI", "ABT", "BTU", "FER",
        "TEMP", "SIG", "CONDX", "599", "5NN", "TEST", "QRP", "DIPOLE", "KEY", "CALL",
    )

    /** 50 of the most common English words. */
    val englishWords: List<String> = listOf(
        "THE", "AND", "YOU", "THAT", "WAS", "FOR", "ARE", "WITH", "HIS", "THEY",
        "THIS", "HAVE", "FROM", "ONE", "HAD", "WORD", "BUT", "NOT", "WHAT", "ALL",
        "WERE", "WE", "WHEN", "YOUR", "CAN", "SAID", "THERE", "USE", "EACH", "WHICH",
        "SHE", "DO", "HOW", "THEIR", "IF", "WILL", "UP", "OTHER", "ABOUT", "OUT",
        "MANY", "THEN", "THEM", "SO", "SOME", "HER", "WOULD", "MAKE", "LIKE", "TIME",
    )

    /** The top-100 list for instant word recognition. */
    val words: List<String> = cwWords + englishWords

    data class Term(val text: String, val meaning: String)

    val qCodes: List<Term> = listOf(
        Term("QTH", "My location is"),
        Term("QRZ", "Who is calling me?"),
        Term("QSL", "I confirm receipt"),
        Term("QRM", "Interference from other stations"),
        Term("QRN", "Static / atmospheric noise"),
        Term("QSB", "Your signal is fading"),
        Term("QRS", "Send more slowly"),
        Term("QRQ", "Send faster"),
        Term("QSY", "Change frequency"),
        Term("QRP", "Low power"),
        Term("QRT", "Closing down"),
        Term("QRL", "Is this frequency in use?"),
        Term("QRV", "I am ready"),
        Term("QSO", "A contact"),
    )

    val abbreviations: List<Term> = listOf(
        Term("RST", "Readability, strength, tone report"),
        Term("73", "Best regards"),
        Term("TU", "Thank you"),
        Term("FB", "Fine business (great)"),
        Term("OM", "Old man (any male operator)"),
        Term("TNX", "Thanks"),
        Term("PSE", "Please"),
        Term("AGN", "Again"),
        Term("HW", "How (do you copy)?"),
        Term("WX", "Weather"),
        Term("ES", "And"),
        Term("HR", "Here"),
        Term("UR", "Your / you are"),
        Term("CUL", "See you later"),
        Term("GM", "Good morning"),
        Term("GE", "Good evening"),
        Term("5NN", "599, contest style"),
        Term("ANT", "Antenna"),
        Term("RIG", "Radio"),
        Term("PWR", "Power"),
    )

    val names: List<String> = listOf(
        "ALI", "AHMET", "MEHMET", "CAN", "EMRE", "DENIZ", "AYSE", "ELIF", "HANS", "JOHN",
        "MIKE", "TOM", "BOB", "JIM", "PETER", "IVAN", "LUIGI", "PAUL", "ANNA", "KEN",
    )

    val places: List<String> = listOf(
        "ANKARA", "ISTANBUL", "IZMIR", "BURSA", "ANTALYA", "KONYA", "BERLIN", "LONDON", "PARIS", "ROME",
        "MADRID", "WIEN", "SOFIA", "ATHENS", "MOSCOW", "TOKYO", "BOSTON", "DENVER", "OSLO", "PRAHA",
    )

    val rigs: List<String> = listOf("QMX", "QCX", "IC7300", "FT891", "K2", "KX3", "TS590", "G90", "FT817", "HB1B")
    val antennas: List<String> = listOf("DIPOLE", "EFHW", "VERTICAL", "YAGI", "LOOP", "INV V", "GP", "LW")
    val weather: List<String> = listOf("SUNNY", "RAIN", "CLOUDY", "SNOW", "WINDY", "FOG", "CLEAR", "COLD", "HOT")

    /** Short plain-language lines for head copy. */
    val sentences: List<String> = listOf(
        "THE RIG IS A QRP KIT",
        "WX HR IS RAIN ES COLD",
        "MY ANT IS A DIPOLE",
        "TNX FER THE CALL OM",
        "UR SIG IS VY FB",
        "HW CPY?",
        "PSE RPT UR NAME",
        "I AM NEW TO CW",
        "THE BAND IS OPEN",
        "PWR HR IS 5 WATTS",
        "CONDX ARE NOT SO GOOD",
        "NICE TO MEET YOU",
        "HOPE TO CUL ON THE AIR",
        "THIS IS MY FIRST QSO",
        "QRM IS BAD HR",
        "GM OM ES TNX",
        "THE KEY IS OLD BUT GOOD",
        "WE HAD SNOW ALL DAY",
        "WHAT IS UR QTH?",
        "SRI LOST YOU IN QSB",
        "73 ES GOOD DX",
        "I USE A SMALL LOOP",
        "TIME TO QRT NOW",
        "YOU ARE 599 HR",
    )

    fun <T> List<T>.pick(rng: Random): T = this[rng.nextInt(size)]
}
