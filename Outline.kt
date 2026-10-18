package com.muraltracer.app

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure-Kotlin Sobel edge detector -> transparent bitmap with green line art. */
object Outline {
    private const val MAX_SIDE = 1280
    private const val LINE_COLOR = 0x00E676

    /** Lower threshold = more detail. */
    fun fromBitmap(src: Bitmap, threshold: Int): Bitmap {
        val k = min(1f, MAX_SIDE.toFloat() / max(src.width, src.height))
        val bmp = if (k < 1f) {
            Bitmap.createScaledBitmap(
                src, max(1, (src.width * k).toInt()), max(1, (src.height * k).toInt()), true
            )
        } else src
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val gray = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            gray[i] = (0.299f * ((p shr 16) and 0xFF) +
                0.587f * ((p shr 8) and 0xFF) +
                0.114f * (p and 0xFF)).toInt()
        }

        // 3x3 box blur to reduce noise
        val g = gray.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            var s = 0
            for (dy in -1..1) for (dx in -1..1) s += gray[i + dy * w + dx]
            g[i] = s / 9
        }

        // Sobel
        val alpha = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val tl = g[i - w - 1]; val t = g[i - w]; val tr = g[i - w + 1]
            val l = g[i - 1]; val r = g[i + 1]
            val bl = g[i + w - 1]; val b = g[i + w]; val br = g[i + w + 1]
            val gx = -tl - 2 * l - bl + tr + 2 * r + br
            val gy = -tl - 2 * t - tr + bl + 2 * b + br
            val mag = sqrt((gx * gx + gy * gy).toFloat())
            alpha[i] = ((mag - threshold) * 3f).toInt().coerceIn(0, 255)
        }

        // Thicken lines by 1px so they stay visible when scaled up on the wall
        val out = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val a = max(
                max(alpha[i], alpha[i - 1]),
                max(max(alpha[i + 1], alpha[i - w]), alpha[i + w])
            )
            out[i] = (a shl 24) or LINE_COLOR
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
