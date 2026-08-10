package com.example.passportphotomaker.domain.model

enum class PaperCategory { ISO, US, PHOTO, CUSTOM }

data class PaperSize(
    val id: String,
    val name: String,
    val widthMm: Float,
    val heightMm: Float,
    val category: PaperCategory
) {
    companion object {
        // --- ISO Standards (A0 to A8) ---
        val ISO_A0 = PaperSize("iso_a0", "A0", 841f, 1189f, PaperCategory.ISO)
        val ISO_A1 = PaperSize("iso_a1", "A1", 594f, 841f, PaperCategory.ISO)
        val ISO_A2 = PaperSize("iso_a2", "A2", 420f, 594f, PaperCategory.ISO)
        val ISO_A3 = PaperSize("iso_a3", "A3", 297f, 420f, PaperCategory.ISO)
        val ISO_A4 = PaperSize("iso_a4", "A4", 210f, 297f, PaperCategory.ISO)
        val ISO_A5 = PaperSize("iso_a5", "A5", 148f, 210f, PaperCategory.ISO)
        val ISO_A6 = PaperSize("iso_a6", "A6", 105f, 148f, PaperCategory.ISO)
        val ISO_A7 = PaperSize("iso_a7", "A7", 74f, 105f, PaperCategory.ISO)
        val ISO_A8 = PaperSize("iso_a8", "A8", 52f, 74f, PaperCategory.ISO)

        // --- US Standards ---
        val US_LETTER = PaperSize("us_letter", "Letter", 215.9f, 279.4f, PaperCategory.US)
        val US_LEGAL = PaperSize("us_legal", "Legal", 215.9f, 355.6f, PaperCategory.US)
        val US_TABLOID = PaperSize("us_tabloid", "Tabloid", 279.4f, 431.8f, PaperCategory.US)
        val US_LEDGER = PaperSize("us_ledger", "Ledger", 431.8f, 279.4f, PaperCategory.US)

        // --- Common Photo Print Sizes ---
        val PHOTO_3_5X5 = PaperSize("photo_3.5x5", "3.5×5 inch", 88.9f, 127f, PaperCategory.PHOTO)
        val PHOTO_4X6 = PaperSize("photo_4x6", "4×6 inch", 101.6f, 152.4f, PaperCategory.PHOTO)
        val PHOTO_5X7 = PaperSize("photo_5x7", "5×7 inch", 127f, 177.8f, PaperCategory.PHOTO)
        val PHOTO_6X8 = PaperSize("photo_6x8", "6×8 inch", 152.4f, 203.2f, PaperCategory.PHOTO)
        val PHOTO_8X10 = PaperSize("photo_8x10", "8×10 inch", 203.2f, 254f, PaperCategory.PHOTO)

        // Comprehensive master list of all available presets
        val all = listOf(
            PHOTO_4X6, PHOTO_5X7, PHOTO_6X8, PHOTO_8X10, PHOTO_3_5X5,
            ISO_A4, ISO_A3, ISO_A5, ISO_A6, ISO_A7, ISO_A8, ISO_A1, ISO_A2, ISO_A0,
            US_LETTER, US_LEGAL, US_TABLOID, US_LEDGER
        )
    }
}
