package com.example.passportphotomaker.domain.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * High quality bitmap upscaler optimized for passport/document photos.
 *
 * Pipeline:
 * Source ↓ Hardware bitmap protection ↓ Optional noise reduction ↓ Iterative 2x scaling ↓ USM
 * sharpening ↓ Optional film grain
 */
object ImageUpscaler {

    private const val MAX_STEP_FACTOR = 2.0
    private const val MAX_OUTPUT_PX = 4800
    private const val MIN_DIMENSION = 64

    data class UpscaleResult(val bitmap: Bitmap, val appliedScale: Float)

    fun upscale(
        source: Bitmap,
        scaleFactor: Float,
        maxOutputPx: Int = 4000,
        sharpenStrength: Float = 0.6f,
        noiseReduction: Float = 0.4f,
        filmGrain: Float = 0f,
        filmGrainSeed: Long = Random.nextLong()
    ): UpscaleResult {

        require(scaleFactor > 1f) { "scaleFactor must be greater than 1" }
        require(source.width > 0 && source.height > 0) { "Bitmap has invalid dimensions" }

        /*
         * Hardware bitmaps cannot be edited.
         * Convert them before processing.
         */
        val input = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        }

        val maxEdge = max(input.width, input.height)
        val allowedScale = maxOutputPx.coerceAtMost(MAX_OUTPUT_PX).toFloat() / maxEdge
        val effectiveScale = max(1f, min(scaleFactor, allowedScale))

        if (effectiveScale <= 1f) {
            return UpscaleResult(
                bitmap = input.copy(Bitmap.Config.ARGB_8888, false),
                appliedScale = 1f
            )
        }

        val targetWidth = (input.width * effectiveScale).toInt().coerceAtLeast(MIN_DIMENSION)
        val targetHeight = (input.height * effectiveScale).toInt().coerceAtLeast(MIN_DIMENSION)

        var current = if (noiseReduction > 0f) {
            reduceNoise(input, noiseReduction)
        } else {
            input.copy(Bitmap.Config.ARGB_8888, false)
        }

        /*
         * Progressive upscale.
         * Each step is <=2x to preserve details.
         */
        val steps = planSteps(current.width, current.height, targetWidth, targetHeight)

        for ((width, height) in steps) {
            val scaled = scaleBitmap(current, width, height)
            current.recycle()
            current = scaled

            if (sharpenStrength > 0f) {
                val sharpened = sharpen(current, sharpenStrength)
                current.recycle()
                current = sharpened
            }
        }

        if (filmGrain > 0f) {
            val grained = addFilmGrain(current, filmGrain, filmGrainSeed)
            current.recycle()
            current = grained
        }

        return UpscaleResult(bitmap = current, appliedScale = effectiveScale)
    }

    private fun planSteps(srcW: Int, srcH: Int, targetW: Int, targetH: Int): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var width = srcW.toDouble()
        var height = srcH.toDouble()

        while (true) {
            val needed = max(targetW / width, targetH / height)
            if (needed <= 1.0) {
                if (result.lastOrNull() != Pair(targetW, targetH)) {
                    result.add(Pair(targetW, targetH))
                }
                break
            }

            val factor = min(needed, MAX_STEP_FACTOR)
            val nextW = (width * factor).toInt().coerceAtLeast(1)
            val nextH = (height * factor).toInt().coerceAtLeast(1)
            
            result.add(Pair(nextW, nextH))
            width = nextW.toDouble()
            height = nextH.toDouble()

            if (nextW == targetW && nextH == targetH) {
                break
            }
        }
        return result
    }

    private fun scaleBitmap(source: Bitmap, width: Int, height: Int): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
        return output
    }

    // ─────────────────────────────────────────────────────────────
    // Noise Reduction
    // ─────────────────────────────────────────────────────────────

    private fun reduceNoise(source: Bitmap, strength: Float): Bitmap {
        val s = strength.coerceIn(0f, 1f)
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        
        source.getPixels(input, 0, width, 0, 0, width, height)
        val output = IntArray(width * height)
        
        val threshold = 80f + (1f - s) * 260f

        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0; var sumG = 0; var sumB = 0
                var sumR2 = 0f; var sumG2 = 0f; var sumB2 = 0f
                var count = 0

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = (x + dx).coerceIn(0, width - 1)
                        val ny = (y + dy).coerceIn(0, height - 1)
                        val pixel = input[ny * width + nx]

                        val r = (pixel shr 16) and 255
                        val g = (pixel shr 8) and 255
                        val b = pixel and 255

                        sumR += r; sumG += g; sumB += b
                        sumR2 += r.toFloat() * r
                        sumG2 += g.toFloat() * g
                        sumB2 += b.toFloat() * b
                        count++
                    }
                }

                val meanR = sumR.toFloat() / count
                val meanG = sumG.toFloat() / count
                val meanB = sumB.toFloat() / count

                val variance = ((sumR2 / count - meanR * meanR) + (sumG2 / count - meanG * meanG) + (sumB2 / count - meanB * meanB)) / 3f
                val blend = (s * (1f - (variance / threshold).coerceIn(0f, 1f))).coerceIn(0f, 1f)
                val original = input[y * width + x]

                val alpha = original ushr 24 and 255
                val r = original shr 16 and 255
                val g = original shr 8 and 255
                val b = original and 255

                val newR = (r + (meanR - r) * blend).toInt().coerceIn(0, 255)
                val newG = (g + (meanG - g) * blend).toInt().coerceIn(0, 255)
                val newB = (b + (meanB - b) * blend).toInt().coerceIn(0, 255)

                output[y * width + x] = (alpha shl 24) or (newR shl 16) or (newG shl 8) or newB
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, width, 0, 0, width, height)
        return result
    }

    // ─────────────────────────────────────────────────────────────
    // Unsharp Mask Sharpening
    // ─────────────────────────────────────────────────────────────

    private fun sharpen(source: Bitmap, strength: Float): Bitmap {
        val s = strength.coerceIn(0f, 2f)
        val width = source.width
        val height = source.height

        /*
         * Smaller blur preview. Reduces memory usage and fakes a massive blur radius!
         */
        val smallWidth = (width * 0.125f).toInt().coerceAtLeast(1)
        val smallHeight = (height * 0.125f).toInt().coerceAtLeast(1)
        
        val small = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)
        val blur = Bitmap.createScaledBitmap(small, width, height, true)
        small.recycle()

        val originalPixels = IntArray(width * height)
        val blurPixels = IntArray(width * height)

        source.getPixels(originalPixels, 0, width, 0, 0, width, height)
        blur.getPixels(blurPixels, 0, width, 0, 0, width, height)
        blur.recycle()

        for (i in originalPixels.indices) {
            val src = originalPixels[i]
            val blr = blurPixels[i]
            
            val alpha = src ushr 24 and 255
            val r = src shr 16 and 255
            val g = src shr 8 and 255
            val b = src and 255
            
            val br = blr shr 16 and 255
            val bg = blr shr 8 and 255
            val bb = blr and 255

            val newR = (r + (r - br) * s).toInt().coerceIn(0, 255)
            val newG = (g + (g - bg) * s).toInt().coerceIn(0, 255)
            val newB = (b + (b - bb) * s).toInt().coerceIn(0, 255)

            originalPixels[i] = (alpha shl 24) or (newR shl 16) or (newG shl 8) or newB
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(originalPixels, 0, width, 0, 0, width, height)
        return result
    }

    // ─────────────────────────────────────────────────────────────
    // Film Grain
    // ─────────────────────────────────────────────────────────────

    private fun addFilmGrain(source: Bitmap, strength: Float, seed: Long): Bitmap {
        val s = strength.coerceIn(0f, 1f)
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val width = result.width
        val height = result.height
        
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)
        val random = Random(seed)

        val amount = (s * 25f).toInt()

        if (amount > 0) {
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val delta = random.nextInt(-amount, amount + 1)
                
                // THE FIX: Properly wrapped the bitwise extraction in parentheses before adding delta!
                val r = (((pixel shr 16) and 255) + delta).coerceIn(0, 255)
                val g = (((pixel shr 8) and 255) + delta).coerceIn(0, 255)
                val b = ((pixel and 255) + delta).coerceIn(0, 255)

                pixels[i] = (pixel and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
}
