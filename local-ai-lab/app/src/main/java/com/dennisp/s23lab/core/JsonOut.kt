package com.dennisp.s23lab.core

import java.util.Locale

/**
 * Minimal JSON writer, so results can be exported and unit tested without Android's org.json stubs.
 * Accepts null, Boolean, Number, String, Map<String, *> and Iterable<*>. Non-finite numbers become null.
 */
object JsonOut {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value)
            is Double -> if (value.isFinite()) sb.append(number(value)) else sb.append("null")
            is Float -> if (value.isFinite()) sb.append(number(value.toDouble())) else sb.append("null")
            is Number -> sb.append(value.toString())
            is String -> string(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    string(sb, k.toString())
                    sb.append(':')
                    append(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    append(sb, v)
                }
                sb.append(']')
            }
            else -> string(sb, value.toString())
        }
    }

    // Double.toString is always a valid JSON number for finite values (for example 1.5, 1.0E-5).
    private fun number(d: Double): String =
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format(Locale.US, "\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
