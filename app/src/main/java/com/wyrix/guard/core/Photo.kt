package com.wyrix.guard.core

object PhotoPolicy {
    const val MIN_INTERVAL_MS = 10 * 60_000L
    fun canCapture(enabled: Boolean, cameraGranted: Boolean, enrolled: Boolean, lastAtMs: Long, nowMs: Long, force: Boolean): Boolean =
        enabled && cameraGranted && enrolled && (force || nowMs - lastAtMs >= MIN_INTERVAL_MS)

    const val MAX_EDGE_PX = 1280
    fun sampleSize(w: Int, h: Int): Int { var s = 1; while (maxOf(w, h) / (s * 2) >= MAX_EDGE_PX) s *= 2; return s }
}
