package com.example.passportphotomaker.domain.model

import java.util.UUID

data class TextLayer(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val xFrac: Float = 0.5f,       // normalized position (0..1) — independent of image resolution
    val yFrac: Float = 0.5f,
    val fontSizeSp: Float = 24f,
    val colorArgb: Int = android.graphics.Color.WHITE,
    val fontFamily: TextFontFamily = TextFontFamily.SANS_SERIF,
    val isBold: Boolean = false,
    val rotationDegrees: Float = 0f
)

enum class TextFontFamily { SANS_SERIF, SERIF, MONOSPACE }