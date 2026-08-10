package com.example.passportphotomaker.domain.model

import android.graphics.Bitmap
import java.util.UUID

data class PrintBatch(
    val id: String = UUID.randomUUID().toString(),
    val imagePath: String,          // now the sole source of truth — no more eager Bitmap
    val widthMm: Float,
    val heightMm: Float,
    val copies: Int = 1,
    val gapMm: Float = 2f,
    val drawGuides: Boolean = true,
    val aspectRatio: Float = 1f
)
