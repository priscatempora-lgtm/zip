package com.example.passportphotomaker.nativebridge

object NativeBeautyEngine {
    init {
        System.loadLibrary("beauty-engine")
    }

    external fun processImage(
        pixels: IntArray,
        width: Int,
        height: Int,
        refinedMask: FloatArray,
        sharpMask: FloatArray,
        blemishMask: FloatArray,
        brightnessMask: FloatArray,
        eyebrowMask: FloatArray,
        eyesMask: FloatArray,
        eyeBagsMask: FloatArray,
        irisMask: FloatArray,
        teethMask: FloatArray,
        blemishStrength: Float,
        sharpenStrength: Float,
        eyebrowStrength: Float,
        skinBrightnessStrength: Float,
        underEyeStrength: Float,
        eyeBrightnessStrength: Float,
        teethWhiteningStrength: Float
    ): IntArray
}
