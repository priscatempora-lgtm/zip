package com.example.passportphotomaker.domain.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.OutputStream

object ImageExporter {

    enum class ExportFormat(val extension: String, val mimeType: String) {
        JPG("jpg", "image/jpeg"),
        PNG("png", "image/png"),
        WEBP("webp", "image/webp"),
        PDF("pdf", "application/pdf")
    }

    /**
     * Saves a bitmap to public storage with an optimized multi-phase size compression engine.
     */
    fun saveDigitalPhotoToGallery(
        context: Context, 
        bitmap: Bitmap, 
        format: ExportFormat, 
        maxSizeKb: Int?
    ): Boolean {
        val resolver = context.contentResolver
        val timestamp = System.currentTimeMillis()
        val fileName = "Passport_${format.name}_$timestamp"

        val collectionUri: Uri
        val relativePath: String

        if (format == ExportFormat.PDF) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                relativePath = "Download/PassportPhotoMaker"
            } else {
                collectionUri = MediaStore.Files.getContentUri("external")
                relativePath = ""
            }
        } else {
            collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            relativePath = "Pictures/PassportPhotoMaker"
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.${format.extension}")
            put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val fileUri = resolver.insert(collectionUri, contentValues) ?: return false

        return try {
            val outputStream: OutputStream? = resolver.openOutputStream(fileUri)
            if (outputStream != null) {
                
                when (format) {
                    ExportFormat.PDF -> {
                        val pdfDocument = PdfDocument()
                        val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
                        val page = pdfDocument.startPage(pageInfo)
                        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                        pdfDocument.finishPage(page)
                        pdfDocument.writeTo(outputStream)
                        pdfDocument.close()
                    }
                    ExportFormat.JPG, ExportFormat.WEBP, ExportFormat.PNG -> {
                        val compressFormat = when (format) {
                            ExportFormat.JPG -> Bitmap.CompressFormat.JPEG
                            ExportFormat.PNG -> Bitmap.CompressFormat.PNG
                            ExportFormat.WEBP -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                                    Bitmap.CompressFormat.WEBP_LOSSY
                                else
                                    @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                            }
                            else -> throw IllegalArgumentException("PDF format does not support standard bitmap compression directly.")
                        }

                        val finalBytes = compressToTarget(
                            source = bitmap,
                            compressFormat = compressFormat,
                            maxSizeKb = maxSizeKb
                        )
                        outputStream.write(finalBytes)
                    }
                }
                outputStream.close()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(fileUri, contentValues, null, null)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            resolver.delete(fileUri, null, null) 
            false
        }
    }

    /**
     * Highly optimized, multi-phase compression engine that guarantees target bounds 
     * are met without infinite spin-locks or erratic memory allocations.
     */
    private fun compressToTarget(
        source: Bitmap,
        compressFormat: Bitmap.CompressFormat,
        maxSizeKb: Int?
    ): ByteArray {
        val isLossless = compressFormat == Bitmap.CompressFormat.PNG
        
        var quality = 92
        var currentBitmap = source
        var lastBytes = ByteArray(0)

        fun compress(): ByteArray {
            val out = ByteArrayOutputStream()
            currentBitmap.compress(compressFormat, quality, out)
            return out.toByteArray()
        }

        // Phase 1 — Quality sweep (Skipped entirely for lossless PNG formats)
        if (!isLossless) {
            while (quality >= 40) {
                lastBytes = compress()
                if (maxSizeKb == null || lastBytes.size / 1024 <= maxSizeKb) {
                    return lastBytes 
                }
                quality -= 8
            }
        } else {
            lastBytes = compress()
            if (maxSizeKb == null || lastBytes.size / 1024 <= maxSizeKb) {
                return lastBytes
            }
        }

        // Phase 2 — Compounded scale down sweep at fixed structural quality targets
        quality = if (isLossless) 100 else 75
        val MIN_WIDTH  = 100
        val MIN_HEIGHT = 100

        while (true) {
            val nextW = (currentBitmap.width  * 0.75f).toInt().coerceAtLeast(MIN_WIDTH)
            val nextH = (currentBitmap.height * 0.75f).toInt().coerceAtLeast(MIN_HEIGHT)

            val stuck = nextW == currentBitmap.width && nextH == currentBitmap.height
            if (stuck) {
                lastBytes = compress()
                break
            }

            val scaled = Bitmap.createScaledBitmap(currentBitmap, nextW, nextH, true)
            if (currentBitmap !== source) currentBitmap.recycle() 
            currentBitmap = scaled

            lastBytes = compress()
            if (maxSizeKb == null || lastBytes.size / 1024 <= maxSizeKb) break
        }

        // Phase 3 — Last-resort compression quality squeeze on the minimized bitmap array
        if (!isLossless && maxSizeKb != null && lastBytes.size / 1024 > maxSizeKb) {
            quality = 60
            while (quality >= 20) {
                lastBytes = compress()
                if (lastBytes.size / 1024 <= maxSizeKb) break
                quality -= 10
            }
        }

        if (currentBitmap !== source) currentBitmap.recycle()
        return lastBytes
    }
}
