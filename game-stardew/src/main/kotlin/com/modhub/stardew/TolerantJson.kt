package com.modhub.stardew

/**
 * Minimal, tolerant JSON reader for SMAPI `manifest.json` files.
 *
 * SMAPI itself reads manifests with Json.NET, which accepts things strict JSON does not,
 * and mod authors rely on that. This reader accepts:
 *  - `//` line comments and block comments,
 *  - trailing commas in objects and arrays,
 *  - single-quoted strings,
 *  - a leading UTF-8 BOM.
 *
 * Values map to: [Map] (insertion ordered), [List], [String], [Double]/[Long], [Boolean], null.
 * Kept dependency-free on purpose (no kotlinx-serialization) so the parser runs in plain JVM
 * tests and stays tiny; manifests are small.
 */
internal object TolerantJson {

    class JsonParseException(message: String, val position: Int) : IllegalArgumentException("$message at offset $position")

    fun parse(text: String): Any? {
        val p = Parser(text.removePrefix("﻿"))
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw p.error("Unexpected trailing content")
        return v
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length
        fun error(msg: String) = JsonParseException(msg, i)

        fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && i + 1 < s.length && s[i + 1] == '/' -> {
                        while (i < s.length && s[i] != '\n') i++
                    }
                    c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                        val end = s.indexOf("*/", i + 2)
                        if (end < 0) throw error("Unterminated comment")
                        i = end + 2
                    }
                    else -> return
                }
            }
        }

        fun readValue(): Any? {
            if (atEnd()) throw error("Unexpected end of input")
            return when (val c = s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"', '\'' -> readString(c)
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c == '+' || c.isDigit() || c == '.') readNumber() else throw error("Unexpected '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw error("Expected $word")
            i += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            while (true) {
                skipWs()
                if (atEnd()) throw error("Unterminated object")
                if (s[i] == '}') { i++; return out }
                val key = when (val c = s[i]) {
                    '"', '\'' -> readString(c)
                    else -> readBareKey()
                }
                skipWs()
                if (atEnd() || s[i] != ':') throw error("Expected ':'")
                i++
                skipWs()
                out[key] = readValue()
                skipWs()
                if (atEnd()) throw error("Unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw error("Expected ',' or '}'")
                }
            }
        }

        private fun readBareKey(): String {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) i++
            if (start == i) throw error("Expected key")
            return s.substring(start, i)
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            while (true) {
                skipWs()
                if (atEnd()) throw error("Unterminated array")
                if (s[i] == ']') { i++; return out }
                out += readValue()
                skipWs()
                if (atEnd()) throw error("Unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw error("Expected ',' or ']'")
                }
            }
        }

        private fun readString(quote: Char): String {
            val sb = StringBuilder()
            i++ // opening quote
            while (true) {
                if (atEnd()) throw error("Unterminated string")
                val c = s[i++]
                when {
                    c == quote -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) throw error("Unterminated escape")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) throw error("Bad unicode escape")
                                val hex = s.substring(i, i + 4)
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: throw error("Bad unicode escape"))
                                i += 4
                            }
                            else -> sb.append(e) // \" \' \\ \/ and lenient unknown escapes
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val text = s.substring(start, i)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw JsonParseException("Bad number '$text'", start)
        }
    }
}
