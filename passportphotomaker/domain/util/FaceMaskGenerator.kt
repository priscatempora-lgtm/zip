package com.example.passportphotomaker.domain.util

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.example.passportphotomaker.domain.model.FaceData

object FaceMaskGenerator {

    /**
     * Takes the raw TFLite segmentation mask and the MediaPipe landmarks,
     * and generates the 9 flat FloatArrays required by the NativeBeautyEngine.
     */
    fun generateNativeMasks(
        faceData: FaceData,
        width: Int,
        height: Int
    ): FaceData {
        // 1. Base geometric exclusions
        val refinedMask2D = punchExclusionZones(faceData.segmentationMask, faceData, width, height)
        val faceOvalMask2D = createFaceOvalMask(faceData, width, height, 35f)

        // 2. Build the 2D versions of the 9 required masks
        val sharpMask2D = preBlurMask(faceOvalMask2D, width, height, 12)
        
        var blemishMask2D = preBlurMask(multiplyMasks(refinedMask2D, faceOvalMask2D), width, height, 2)
        blemishMask2D = applyOcularSmoothingShield(blemishMask2D, faceData, width, height)

        val brightnessMask2D = preBlurMask(
            multiplyMasks(
                punchExclusionZones(faceData.segmentationMask, faceData, width, height, listOf(FEATURE_LEFT_EYE, FEATURE_RIGHT_EYE)),
                faceOvalMask2D
            ),
            width, height, 12
        )

        val eyebrowMask2D = createFeatureMask(faceData, listOf(FEATURE_LEFT_BROW, FEATURE_RIGHT_BROW), width, height, 3f, 2f)
        val eyesMask2D = createFeatureMask(faceData, listOf(LEFT_EYE_INDICES, RIGHT_EYE_INDICES), width, height, 2f)
        val eyeBagsMask2D = createFeatureMask(faceData, listOf(LEFT_EYE_BAG_INDICES, RIGHT_EYE_BAG_INDICES, LEFT_EYE_BAG_TIER2, RIGHT_EYE_BAG_TIER2, LEFT_EYE_BAG_TIER3, RIGHT_EYE_BAG_TIER3), width, height, 20f)
        val irisMask2D = createFeatureMask(faceData, listOf(LEFT_IRIS_INDICES, RIGHT_IRIS_INDICES), width, height, 4f)
        val teethMask2D = createFeatureMask(faceData, listOf(FEATURE_LIPS_INNER), width, height, 4f)

        // 3. Flatten them to 1D FloatArrays for the C++ engine
        return faceData.copy(
            width = width,
            height = height,
            refinedMask = flatten(refinedMask2D, width, height),
            sharpMask = flatten(sharpMask2D, width, height),
            blemishMask = flatten(blemishMask2D, width, height),
            brightnessMask = flatten(brightnessMask2D, width, height),
            eyebrowMask = flatten(eyebrowMask2D, width, height),
            eyesMask = flatten(eyesMask2D, width, height),
            eyeBagsMask = flatten(eyeBagsMask2D, width, height),
            irisMask = flatten(irisMask2D, width, height),
            teethMask = flatten(teethMask2D, width, height)
        )
    }

    private fun flatten(mask2D: Array<FloatArray>, w: Int, h: Int): FloatArray {
        val flat = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                flat[y * w + x] = mask2D[y][x]
            }
        }
        return flat
    }

    // --- RECYCLED MATH FROM APPLYBEAUTYUSECASE.KT ---

    private fun createFeatureMask(faceData: FaceData, polygons: List<List<Int>>, w: Int, h: Int, blurRadius: Float, expansion: Float = 0f): Array<FloatArray> {
        val maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
        canvas.drawColor(Color.BLACK)

        val fillPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            if (expansion > 0f) {
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = expansion
                strokeJoin = Paint.Join.ROUND
            } else {
                style = Paint.Style.FILL
            }
            if (blurRadius > 0f) maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        }

        polygons.forEach { indices ->
            if (indices.isEmpty()) return@forEach
            val path = Path()
            val first = faceData.landmarks[indices[0]]
            path.moveTo(first.x * w, first.y * h)
            for (i in 1 until indices.size) {
                val lm = faceData.landmarks[indices[i]]
                path.lineTo(lm.x * w, lm.y * h)
            }
            path.close()
            canvas.drawPath(path, fillPaint)
        }
        return bitmapToFloatArray(maskBitmap, w, h)
    }

    private fun createFaceOvalMask(faceData: FaceData, w: Int, h: Int, blurRadius: Float): Array<FloatArray> {
        val lm = faceData.landmarks
        val maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
        canvas.drawColor(Color.BLACK)

        val ovalIndices = listOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109)
        val foreheadTop = setOf(109, 10, 338, 67, 297, 251, 21, 54, 103)
        val chin = lm[152]; val browTop = lm[10]
        val faceHeightPx = kotlin.math.abs((chin.y - browTop.y) * h)
        val extendPx = faceHeightPx * 0.55f
        val browTopY = browTop.y * h
        val extendedTopY = browTopY - extendPx

        val path = Path()
        ovalIndices.forEachIndexed { i, idx ->
            val p = lm[idx]
            val x = p.x * w
            val y = if (idx in foreheadTop) p.y * h - extendPx else p.y * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            shader = LinearGradient(0f, extendedTopY, 0f, browTopY, Color.TRANSPARENT, Color.WHITE, Shader.TileMode.CLAMP)
            if (blurRadius > 0f) maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(path, paint)
        return bitmapToFloatArray(maskBitmap, w, h)
    }

    private fun multiplyMasks(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val h = a.size; val w = if (h > 0) a[0].size else 0
        return Array(h) { y -> FloatArray(w) { x -> a[y][x] * b[y][x] } }
    }

    private fun preBlurMask(mask: Array<FloatArray>, w: Int, h: Int, radius: Int): Array<FloatArray> {
        if (radius <= 0) return mask
        val out = Array(h) { FloatArray(w) }; val tmp = Array(h) { FloatArray(w) }
        val div = (2 * radius + 1).toFloat()
        for (y in 0 until h) {
            var sum = 0f
            for (k in -radius..radius) sum += mask[y][k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y][x] = sum / div
                sum += mask[y][(x + radius + 1).coerceIn(0, w - 1)] - mask[y][(x - radius).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (k in -radius..radius) sum += tmp[k.coerceIn(0, h - 1)][x]
            for (y in 0 until h) {
                out[y][x] = sum / div
                sum += tmp[(y + radius + 1).coerceIn(0, h - 1)][x] - tmp[(y - radius).coerceIn(0, h - 1)][x]
            }
        }
        return out
    }

    private fun punchExclusionZones(
        baseMask: Array<FloatArray>, faceData: FaceData, w: Int, h: Int,
        zones: List<List<Int>> = listOf(FEATURE_LEFT_EYE, FEATURE_RIGHT_EYE, FEATURE_LEFT_BROW, FEATURE_RIGHT_BROW, FEATURE_LIPS_OUTER, FEATURE_NOSE_BASE, FEATURE_BINDI_ZONE)
    ): Array<FloatArray> {
        val maskBitmap = floatArrayToBitmap(baseMask, w, h)
        val canvas = Canvas(maskBitmap)
        val eraserPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL; isAntiAlias = true }
        zones.forEach { indices ->
            if (indices.isEmpty()) return@forEach
            val path = Path()
            path.moveTo(faceData.landmarks[indices[0]].x * w, faceData.landmarks[indices[0]].y * h)
            for (i in 1 until indices.size) path.lineTo(faceData.landmarks[indices[i]].x * w, faceData.landmarks[indices[i]].y * h)
            path.close()
            canvas.drawPath(path, eraserPaint)
        }
        return bitmapToFloatArray(maskBitmap, w, h)
    }

    private fun applyOcularSmoothingShield(baseMask: Array<FloatArray>, faceData: FaceData, w: Int, h: Int): Array<FloatArray> {
        val maskBitmap = floatArrayToBitmap(baseMask, w, h)
        val canvas = Canvas(maskBitmap)
        val ocularEraserPaint = Paint().apply {
            color = Color.BLACK; style = Paint.Style.FILL_AND_STROKE; strokeWidth = 24f
            isAntiAlias = true; maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
        }
        listOf(FEATURE_LEFT_EYE, FEATURE_RIGHT_EYE, FEATURE_LEFT_BROW, FEATURE_RIGHT_BROW, FEATURE_LEFT_EYELID, FEATURE_RIGHT_EYELID).forEach { indices ->
            if (indices.isEmpty()) return@forEach
            val path = Path()
            path.moveTo(faceData.landmarks[indices[0]].x * w, faceData.landmarks[indices[0]].y * h)
            for (i in 1 until indices.size) path.lineTo(faceData.landmarks[indices[i]].x * w, faceData.landmarks[indices[i]].y * h)
            path.close()
            canvas.drawPath(path, ocularEraserPaint)
        }
        return bitmapToFloatArray(maskBitmap, w, h)
    }

    private fun floatArrayToBitmap(mask: Array<FloatArray>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = (mask[y][x].coerceIn(0f, 1f) * 255f).toInt()
            pixels[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun bitmapToFloatArray(bmp: Bitmap, w: Int, h: Int): Array<FloatArray> {
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        return Array(h) { y -> FloatArray(w) { x -> ((pixels[y * w + x] shr 16) and 0xFF) / 255f } }
    }

    // Geometry Contours (Identical to ApplyBeautyUseCase)
    private val FEATURE_LEFT_EYE = listOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)
    private val FEATURE_RIGHT_EYE = listOf(362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398)
    private val FEATURE_LEFT_BROW = listOf(70, 63, 105, 66, 107, 55, 65, 52, 53, 46)
    private val FEATURE_RIGHT_BROW = listOf(300, 293, 334, 296, 336, 285, 295, 282, 283, 276)
    private val FEATURE_LIPS_OUTER = listOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185)
    private val FEATURE_LIPS_INNER = listOf(78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191)
    private val FEATURE_NOSE_BASE = listOf(4, 45, 129, 98, 97, 2, 326, 327, 358, 275)
    private val FEATURE_BINDI_ZONE = listOf(108, 151, 337, 336, 285, 168, 55, 107)
    private val FEATURE_LEFT_EYELID = listOf(107, 55, 65, 52, 53, 46, 33, 7, 163, 144, 145, 153, 154, 155, 133)
    private val FEATURE_RIGHT_EYELID = listOf(336, 285, 295, 282, 283, 276, 263, 249, 390, 373, 374, 380, 381, 382, 362)
    private val LEFT_EYE_INDICES = FEATURE_LEFT_EYE
    private val RIGHT_EYE_INDICES = FEATURE_RIGHT_EYE
    private val LEFT_IRIS_INDICES = listOf(469, 470, 471, 472)
    private val RIGHT_IRIS_INDICES = listOf(474, 475, 476, 477)
    private val LEFT_EYE_BAG_INDICES = listOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 112, 26, 22, 23, 24, 110, 25, 226, 130)
    private val RIGHT_EYE_BAG_INDICES = listOf(362, 382, 381, 380, 374, 373, 390, 249, 263, 359, 446, 255, 339, 254, 253, 252, 256, 341)
    private val LEFT_EYE_BAG_TIER2 = listOf(112, 26, 22, 23, 24, 110, 25, 31, 228, 229, 230, 231, 232, 233)
    private val RIGHT_EYE_BAG_TIER2 = listOf(341, 256, 252, 253, 254, 339, 255, 261, 448, 449, 450, 451, 452, 453)
    private val LEFT_EYE_BAG_TIER3 = listOf(233, 232, 231, 230, 229, 228, 31, 111, 117, 118, 119, 120, 121, 128)
    private val RIGHT_EYE_BAG_TIER3 = listOf(453, 452, 451, 450, 449, 448, 261, 340, 346, 347, 348, 349, 350, 357)
}
