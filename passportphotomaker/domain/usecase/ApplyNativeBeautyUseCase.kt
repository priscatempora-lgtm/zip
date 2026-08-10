package com.example.passportphotomaker.domain.usecase

import android.graphics.Bitmap
import com.example.passportphotomaker.domain.model.BeautyParameters
import com.example.passportphotomaker.domain.model.FaceData
import com.example.passportphotomaker.nativebridge.NativeBeautyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApplyNativeBeautyUseCase {

    suspend operator fun invoke(
        bitmap: Bitmap,
        faceData: FaceData,
        parameters: BeautyParameters
    ): Bitmap? = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height

        try {
            val params = parameters.withGlobalIntensity()
            
            val safeSkinBrightness = params.skinBrightness.coerceIn(0.0f, 0.5f)

            val inputPixels = IntArray(totalPixels)
            bitmap.getPixels(inputPixels, 0, width, 0, 0, width, height)

            // FIX: Stored result in outputPixels instead of result
            val outputPixels = NativeBeautyEngine.processImage(
                pixels = inputPixels,
                width = width,
                height = height,
                refinedMask = faceData.refinedMask,
                sharpMask = faceData.sharpMask,
                blemishMask = faceData.blemishMask,
                brightnessMask = faceData.brightnessMask,
                eyebrowMask = faceData.eyebrowMask,
                eyesMask = faceData.eyesMask,
                eyeBagsMask = faceData.eyeBagsMask,
                irisMask = faceData.irisMask,
                teethMask = faceData.teethMask,
                
                blemishStrength = params.blemishReduction,
                sharpenStrength = params.faceSharpening,
                eyebrowStrength = params.eyebrowDefinition,
                skinBrightnessStrength = safeSkinBrightness,
                underEyeStrength = params.underEyeReduction,
                eyeBrightnessStrength = params.eyeBrightness,
                teethWhiteningStrength = params.teethWhitening
            )
            
            if (outputPixels == null || outputPixels.size != totalPixels) return@withContext null

            val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            outputBitmap.setPixels(outputPixels, 0, width, 0, 0, width, height)

            outputBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}