package com.dsh.audiohaptics

import java.util.Locale

/**
 * 报文模板：把 "AA 03 {level} 55" 这样的串编译成字节。
 * {level} 会按 0..100 的强度映射到 0..maxByte。
 */
object Protocol {

    fun build(template: String, level: Int, maxByte: Int): ByteArray? {
        val tpl = template.trim()
        if (tpl.isEmpty()) return null

        val cap = maxByte.coerceIn(1, 255)
        val value = (level.toDouble() / 100.0 * cap).toInt().coerceIn(0, 255)

        val out = ArrayList<Byte>()
        for (tok in tpl.split(Regex("\\s+"))) {
            if (tok.isEmpty()) continue
            if (tok.equals("{level}", ignoreCase = true)) {
                out.add(value.toByte())
            } else {
                val hex = tok.removePrefix("0x").removePrefix("0X")
                val v = hex.toIntOrNull(16) ?: return null
                if (v < 0 || v > 255) return null
                out.add(v.toByte())
            }
        }
        if (out.isEmpty()) return null
        return out.toByteArray()
    }

    fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format(Locale.US, "%02X", it) }
}
