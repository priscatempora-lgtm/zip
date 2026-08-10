package com.example.passportphotomaker.domain.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.example.passportphotomaker.domain.model.PaperSize
import com.example.passportphotomaker.domain.model.PrintBatch

object PrintSheetGenerator {

    private const val DPI = 300f
    private const val MM_TO_INCH = 25.4f
    private fun mmToPx(mm: Float): Float = (mm / MM_TO_INCH) * DPI

    data class Coord(val x: Float, val y: Float)
    data class PlacedRect(val x: Float, val y: Float, val physW: Float, val physH: Float, val collW: Float, val collH: Float)
    data class PrintSlot(val xMm: Float, val yMm: Float, val wMm: Float, val hMm: Float, val isExisting: Boolean, val batchIndex: Int)

    private fun findPlacementMm(
        physW: Float, physH: Float, collW: Float, collH: Float,
        safeRight: Float, safeBottom: Float,
        placedRects: List<PlacedRect>, marginMm: Float
    ): Coord? {
        val xCoords = mutableSetOf(marginMm)
        val yCoords = mutableSetOf(marginMm)

        for (r in placedRects) {
            xCoords.add(r.x + r.collW); xCoords.add(r.x)
            yCoords.add(r.y + r.collH); yCoords.add(r.y)
        }

        val sortedX = xCoords.filter { it + physW <= safeRight + 0.01f }.sorted()
        val sortedY = yCoords.filter { it + physH <= safeBottom + 0.01f }.sorted()

        for (y in sortedY) {
            for (x in sortedX) {
                var overlaps = false
                val epsilon = 0.1f // FIX: Absorb tiny floating point variances to prevent staggered gaps!
                for (p in placedRects) {
                    if (x < p.x + p.collW - epsilon && x + collW > p.x + epsilon &&
                        y < p.y + p.collH - epsilon && y + collH > p.y + epsilon) {
                        overlaps = true
                        break
                    }
                }
                if (!overlaps) return Coord(x, y)
            }
        }
        return null
    }

    // Now simply packs the provided list of batches until they are exhausted or the page is full
    fun buildLayoutPlan(paperSize: PaperSize, batches: List<PrintBatch>, marginMm: Float = 6f): List<PrintSlot> {
        val slots = mutableListOf<PrintSlot>()
        val placedRects = mutableListOf<PlacedRect>()
        val safeRightMm = paperSize.widthMm - marginMm
        val safeBottomMm = paperSize.heightMm - marginMm

        for ((bIndex, batch) in batches.withIndex()) {
            val collW = batch.widthMm + (batch.gapMm - 2f)
            val collH = batch.heightMm + (batch.gapMm - 2f)
            
            for (i in 0 until batch.copies) {
                val pt = findPlacementMm(batch.widthMm, batch.heightMm, collW, collH, safeRightMm, safeBottomMm, placedRects, marginMm)
                if (pt != null) {
                    placedRects.add(PlacedRect(pt.x, pt.y, batch.widthMm, batch.heightMm, collW, collH))
                    slots.add(PrintSlot(pt.x, pt.y, batch.widthMm, batch.heightMm, true, bIndex))
                } else break // Page is full for this size
            }
        }
        return slots
    }

    fun generateMultiBatchSheet(
        paperSize: PaperSize,
        batches: List<PrintBatch>,
        marginMm: Float = 6f,
        targetDpi: Int = 300,
        context: android.content.Context
    ): android.graphics.Bitmap? {
        return try {
            val pxPerMm = targetDpi / 25.4f
            fun toPx(mm: Float): Float = mm * pxPerMm

            val sheetW = toPx(paperSize.widthMm).toInt()
            val sheetH = toPx(paperSize.heightMm).toInt()

            val printSheet = android.graphics.Bitmap.createBitmap(sheetW, sheetH, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(printSheet).apply { drawColor(android.graphics.Color.WHITE) }

            val plan = buildLayoutPlan(paperSize, batches, marginMm)
            val bitmapPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
            val borderPaint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; strokeWidth = toPx(0.35f); style = android.graphics.Paint.Style.STROKE; isAntiAlias = true }
            val guidePaint = android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY; strokeWidth = toPx(0.2f); style = android.graphics.Paint.Style.STROKE; pathEffect = android.graphics.DashPathEffect(floatArrayOf(15f, 15f), 0f) }

            for (slot in plan) {
                val batch = batches[slot.batchIndex]
                val xPx = toPx(slot.xMm)
                val yPx = toPx(slot.yMm)
                val wPx = toPx(slot.wMm)
                val hPx = toPx(slot.hMm)

                if (batch.drawGuides) canvas.drawRect(xPx, yPx, xPx + wPx, yPx + hPx, guidePaint)
                canvas.drawRect(xPx + toPx(1f), yPx + toPx(1f), xPx + wPx - toPx(1f), yPx + hPx - toPx(1f), borderPaint)

                val slotL = xPx + toPx(2f); val slotT = yPx + toPx(2f)
                val slotW = wPx - toPx(4f); val slotH = hPx - toPx(4f)

                val path = batch.imagePath
                val hqBitmap: android.graphics.Bitmap? = if (path.startsWith("content://") || path.startsWith("file://")) {
                    android.graphics.ImageDecoder.decodeBitmap(
                        android.graphics.ImageDecoder.createSource(context.contentResolver, android.net.Uri.parse(path))
                    ) { decoder, _, _ -> decoder.isMutableRequired = true }
                } else {
                    android.graphics.BitmapFactory.decodeFile(path)
                }

                if (hqBitmap == null) continue // decode failed for this slot — skip it, don't abort the whole sheet

                val bmpAspect  = hqBitmap.width.toFloat() / hqBitmap.height.toFloat()
                val slotAspect = slotW / slotH

                var drawW = slotW
                var drawH = slotH
                if (bmpAspect > slotAspect) {
                    drawH = slotW / bmpAspect
                } else {
                    drawW = slotH * bmpAspect
                }

                val drawL = slotL + (slotW - drawW) / 2f
                val drawT = slotT + (slotH - drawH) / 2f

                canvas.drawBitmap(hqBitmap, null, android.graphics.RectF(drawL, drawT, drawL + drawW, drawT + drawH), bitmapPaint)
                hqBitmap.recycle()
            }
            printSheet
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }
}
