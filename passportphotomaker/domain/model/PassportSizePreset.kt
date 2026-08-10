package com.example.passportphotomaker.domain.model

data class PassportSizePreset(
    val id: String,
    val name: String,
    val country: String,
    val widthMm: Float,
    val heightMm: Float
) {
    companion object {
        val SCHENGEN_VISA = PassportSizePreset("schengen_visa", "Standard Passport", "Global", 35f, 45f)
        val US_PASSPORT = PassportSizePreset("us_passport", "Square Passport", "US/India", 50.8f, 50.8f)
        
        val all = listOf(SCHENGEN_VISA, US_PASSPORT)
    }
}