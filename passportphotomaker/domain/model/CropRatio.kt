package com.example.passportphotomaker.domain.model

data class CropRatio(
    val name: String,
    val widthRatio: Float,
    val heightRatio: Float,
    val isStandardPassport: Boolean = false
) {
    companion object {
        val RATIO_FREE = CropRatio("Free Crop", 0f, 0f)
        val RATIO_1_1 = CropRatio("1:1", 1f, 1f)
        val RATIO_2_3 = CropRatio("2:3", 2f, 3f)
        val RATIO_3_2 = CropRatio("3:2", 3f, 2f)
        val RATIO_3_4 = CropRatio("3:4", 3f, 4f)
        val RATIO_4_3 = CropRatio("4:3", 4f, 3f)
        val RATIO_4_5 = CropRatio("4:5", 4f, 5f)
        val RATIO_5_4 = CropRatio("5:4", 5f, 4f)
        val RATIO_9_16 = CropRatio("9:16", 9f, 16f)
        val RATIO_16_9 = CropRatio("16:9", 16f, 9f)
        
        // Professional ID Sizes
        val RATIO_35_45 = CropRatio("35:45 Passport", 35f, 45f, true)
        val RATIO_35_50 = CropRatio("35:50 Visa", 35f, 50f, true)
        val RATIO_50_50 = CropRatio("50:50 ID", 50f, 50f, true)
        val RATIO_50_70 = CropRatio("50:70 Portrait", 50f, 70f, true)
        val RATIO_2X2_INCH = CropRatio("2×2 Inch", 2f, 2f, true)
        
        // Print Photo Sizes
        val RATIO_4X6_INCH = CropRatio("4×6 Inch", 4f, 6f)
        val RATIO_5X7_INCH = CropRatio("5×7 Inch", 5f, 7f)
        val RATIO_8X10_INCH = CropRatio("8×10 Inch", 8f, 10f)

        val allPresets = listOf(
            RATIO_FREE, RATIO_35_45, RATIO_2X2_INCH, RATIO_35_50, RATIO_50_50, RATIO_50_70,
            RATIO_1_1, RATIO_3_4, RATIO_4_3, RATIO_4X6_INCH, RATIO_5X7_INCH, RATIO_8X10_INCH,
            RATIO_2_3, RATIO_3_2, RATIO_4_5, RATIO_5_4, RATIO_9_16, RATIO_16_9
        )
    }
}
