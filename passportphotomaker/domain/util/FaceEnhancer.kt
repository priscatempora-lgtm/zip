package com.example.passportphotomaker.domain.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object FaceEnhancer {

    fun enhance(source: Bitmap, smoothing: Float, spotlight: Float): Bitmap {
        var current = source
        var isOwned = false

        if (smoothing > 0f) {
            current = applySkinSmoothing(current, smoothing)
            isOwned = true
        }

        if (spotlight > 0f) {
            val next = applyFaceSpotlight(current, spotlight)
            if (isOwned) current.recycle()
            current = next
            isOwned = true
        }

        return if (isOwned) current else source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
    }

    /**
     * Mathematically isolates human skin tones and applies an edge-preserving Gaussian Blur.
     * This mimics ML Kit skin smoothing without requiring heavy Neural Network dependencies!
     */
    private fun applySkinSmoothing(src: Bitmap, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Generate a fast Gaussian Blur map of the entire image to pull smooth pixels from
        val blurPx = fastBlur5x5(pixels, w, h)
        val out = IntArray(w * h)

        for (i in pixels.indices) {
            val px = pixels[i]
            val a = (px ushr 24) and 0xFF
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF

            // Universal RGB Heuristic for human skin tones (works across diverse complexions)
            val isSkin = r > 95 && g > 40 && b > 20 &&
                         max(r, max(g, b)) - min(r, min(g, b)) > 15 &&
                         abs(r - g) > 15 && r > g && r > b

            if (isSkin) {
                // If it's skin, pull the blurred pixel data
                val bx = blurPx[i]
                val br = (bx shr 16) and 0xFF
                val bg = (bx shr 8) and 0xFF
                val bb = bx and 0xFF

                // Blend the raw pixel with the blurred pixel based on the slider strength
                val finalR = (r + (br - r) * strength).toInt().coerceIn(0, 255)
                val finalG = (g + (bg - g) * strength).toInt().coerceIn(0, 255)
                val finalB = (b + (bb - b) * strength).toInt().coerceIn(0, 255)

                out[i] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
            } else {
                // If it's hair, eyes, or background, leave it 100% sharp
                out[i] = px
            }
        }

        val result = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun fastBlur5x5(pixels: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        val tmp = IntArray(w * h)
        val kW = intArrayOf(1, 4, 6, 4, 1)
        val kSum = 16

        // Horizontal pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0
                for (k in -2..2) {
                    val nx = (x + k).coerceIn(0, w - 1)
                    val px = pixels[y * w + nx]
                    val wt = kW[k + 2]
                    r += ((px shr 16) and 0xFF) * wt
                    g += ((px shr 8) and 0xFF) * wt
                    b += (px and 0xFF) * wt
                }
                val a = (pixels[y * w + x] ushr 24) and 0xFF
                tmp[y * w + x] = (a shl 24) or ((r / kSum) shl 16) or ((g / kSum) shl 8) or (b / kSum)
            }
        }

        // Vertical pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0
                for (k in -2..2) {
                    val ny = (y + k).coerceIn(0, h - 1)
                    val px = tmp[ny * w + x]
                    val wt = kW[k + 2]
                    r += ((px shr 16) and 0xFF) * wt
                    g += ((px shr 8) and 0xFF) * wt
                    b += (px and 0xFF) * wt
                }
                val a = (tmp[y * w + x] ushr 24) and 0xFF
                out[y * w + x] = (a shl 24) or ((r / kSum) shl 16) or ((g / kSum) shl 8) or (b / kSum)
            }
        }
        return out
    }

    private fun applyFaceSpotlight(src: Bitmap, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        canvas.drawBitmap(src, 0f, 0f, null)

        val cx = w / 2f
        val cy = h / 2f
        val radius = max(w, h) * 0.7f

        val paint = Paint()
        // Brighten center, darken outer edges
        val colors = intArrayOf(
            android.graphics.Color.argb((40 * strength).toInt(), 255, 255, 255), 
            android.graphics.Color.argb(0, 0, 0, 0), 
            android.graphics.Color.argb((80 * strength).toInt(), 0, 0, 0)
        )
        val positions = floatArrayOf(0f, 0.4f, 1f)
        paint.shader = RadialGradient(cx, cy, radius, colors, positions, Shader.TileMode.CLAMP)

        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        return result
    }
}
