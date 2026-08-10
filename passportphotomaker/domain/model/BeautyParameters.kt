package com.example.passportphotomaker.domain.model

/**
 * Holds all beauty enhancement slider values.
 * Each value is in the range [0f, 1f].
 */
data class BeautyParameters(
    val skinBrightness: Float = 0.3f,
    val blemishReduction: Float = 0.4f,
    val underEyeReduction: Float = 0.3f,
    val eyeBrightness: Float = 0.3f,
    val teethWhitening: Float = 0.3f,
    val faceSharpening: Float = 0.3f,
    val eyebrowDefinition: Float = 0.3f,
    val overallIntensity: Float = 0.5f
) {
    companion object {
        val DEFAULT = BeautyParameters()
        val ZERO = BeautyParameters(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }

    /**
     * Returns a copy of these parameters with [overallIntensity] applied as
     * a global multiplier to every individual slider value.
     */
    fun withGlobalIntensity(): BeautyParameters = copy(
        skinBrightness        = skinBrightness        * overallIntensity,
        blemishReduction      = blemishReduction      * overallIntensity,
        underEyeReduction     = underEyeReduction     * overallIntensity,
        eyeBrightness         = eyeBrightness         * overallIntensity,
        teethWhitening        = teethWhitening        * overallIntensity,
        faceSharpening        = faceSharpening        * overallIntensity,
        eyebrowDefinition     = eyebrowDefinition     * overallIntensity
    )
}
