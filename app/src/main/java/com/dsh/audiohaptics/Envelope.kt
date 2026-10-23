package com.dsh.audiohaptics

/**
 * 算法核心 —— 与 APK 里 renderjs 模块 5c504ea0 对齐：
 *   RMS -> 20*log10 -> dBFS
 *   线性映射 [-50, -8] dB -> [0, 100]
 *   非对称一阶平滑：上升 0.6 / 回落 0.2（每帧）
 *   结果 < 1 归零，其余四舍五入
 */
object Envelope {

    const val DB_FLOOR = -50.0
    const val DB_CEIL = -8.0
    const val ATTACK = 0.6
    const val RELEASE = 0.2

    fun clamp(v: Double, lo: Double, hi: Double): Double =
        if (v < lo) lo else if (v > hi) hi else v

    /** 单个 RMS -> 0..100（未平滑） */
    fun levelFromRms(rms: Double): Double {
        val db = if (rms > 0.0) 20.0 * Math.log10(rms) else DB_FLOOR
        return clamp((db - DB_FLOOR) / (DB_CEIL - DB_FLOOR) * 100.0, 0.0, 100.0)
    }

    /** 一次平滑（每帧调用一次） */
    fun smooth(prev: Double, target: Double): Double =
        prev + (target - prev) * (if (target > prev) ATTACK else RELEASE)

    /** 平滑后的值 -> 最终下发的整数强度 */
    fun quantize(v: Double): Int =
        if (v < 1.0) 0 else Math.round(clamp(v, 0.0, 100.0)).toInt()
}
