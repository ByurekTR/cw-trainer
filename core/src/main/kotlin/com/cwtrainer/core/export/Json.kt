package com.cwtrainer.core.export

/**
 * A very small JSON reader/writer, so backups need no library and can be unit-tested on a
 * desktop JVM. Values are plain Kotlin: Map<String, Any?>, List<Any?>, String, Long, Double,
 * Boolean and null.
 */
object Json {
    class ParseException(message: String) : Exception(message)

    fun write(value: Any?): String = StringBuilder().also { write(value, it, 0) }.toString()

    private fun write(v: Any?, out: StringBuilder, depth: Int) {
        when (v) {
            null -> out.append("null")
            is Boolean -> out.append(v)
            is Int, is Long, is Short, is Byte -> out.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                out.append(if (d.isNaN() || d.isInfinite()) "0" else d.toString())
            }
            is String -> quote(v, out)
            is Map<*, *> -> {
                if (v.isEmpty()) { out.append("{}"); return }
                out.append("{")
                var first = true
                for ((k, x) in v) {
                    if (!first) out.append(",")
                    first = false
                    // One level of line breaks keeps the file readable without bloating it.
                    if (depth == 0) out.append("\n ")
                    quote(k.toString(), out)
                    out.append(":")
                    write(x, out, depth + 1)
                }
                if (depth == 0) out.append("\n")
                out.append("}")
            }
            is Iterable<*> -> {
                out.append("[")
                var first = true
                for (x in v) {
                    if (!first) out.append(",")
                    first = false
                    if (depth == 1) out.append("\n  ")
                    write(x, out, depth + 1)
                }
                out.append("]")
            }
            else -> quote(v.toString(), out)
        }
    }

    private fun quote(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipSpace()
        val v = p.value()
        p.skipSpace()
        if (!p.atEnd()) throw ParseException("Unexpected text after the end of the document at ${p.pos}")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun skipSpace() { while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t' || s[pos] == '﻿')) pos++ }
        private fun fail(what: String): Nothing = throw ParseException("$what at position $pos")
        private fun peek(): Char = if (pos < s.length) s[pos] else fail("Unexpected end")

        fun value(): Any? {
            skipSpace()
            return when (val c = peek()) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("Unexpected character '$c'")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("Expected $word")
            pos += word.length
            return v
        }

        private fun num(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) {
                t.toDoubleOrNull() ?: fail("Bad number '$t'")
            } else {
                t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("Bad number '$t'")
            }
        }

        private fun str(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("Unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> out.append('"'); '\\' -> out.append('\\'); '/' -> out.append('/')
                            'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                            'b' -> out.append('\b'); 'f' -> out.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) fail("Bad unicode escape")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("Bad unicode escape")
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("Bad escape '\\$e'")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipSpace()
            if (peek() == ']') { pos++; return out }
            while (true) {
                out.add(value())
                skipSpace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> fail("Expected , or ]")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipSpace()
                if (peek() != '"') fail("Expected a key")
                val k = str()
                skipSpace()
                if (peek() != ':') fail("Expected :")
                pos++
                out[k] = value()
                skipSpace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> fail("Expected , or }")
                }
            }
        }
    }
}
