package com.example.passportphotomaker.domain.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import com.example.passportphotomaker.domain.model.CropBox
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

object BitmapUtils {

    // THE FIX: Helper to read the hidden camera rotation metadata
    private fun getExifRotation(context: Context, uri: Uri): Float {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
    }

    fun cropAndRotateBitmap(
        context: Context,
        imageUri: Uri,
        rotationDegrees: Float,
        cropBox: CropBox,
        imageRectOnScreen: Rect
    ): Bitmap? {
        return try {
            // 1. Load the raw original image
            val inputStream: InputStream? = context.contentResolver.openInputStream(imageUri)
            val rawBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (rawBitmap == null) return null

            // 2. THE FIX: Pre-rotate the raw bitmap so it matches what the UI displayed!
            val exifRotation = getExifRotation(context, imageUri)
            val originalBitmap = if (exifRotation != 0f) {
                val exifMatrix = Matrix().apply { postRotate(exifRotation) }
                val correctedBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, exifMatrix, true)
                if (correctedBitmap != rawBitmap) rawBitmap.recycle() // Free memory
                correctedBitmap
            } else {
                rawBitmap
            }

            // 3. Rotate the EXIF-corrected image (this expands the bounding box into a diamond)
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees)
            val rotatedBitmap = Bitmap.createBitmap(
                originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true
            )

            // 4. Find the base ratio (compensating for UI 90-degree layout swaps)
            val isSideways = (abs(rotationDegrees) / 90f).toInt() % 2 != 0
            val uiBaseWidth = if (isSideways) imageRectOnScreen.height else imageRectOnScreen.width
            val baseRatio = originalBitmap.width.toFloat() / uiBaseWidth

            // 5. Calculate the Auto-Zoom scale factor used in the UI
            val rad = Math.toRadians(rotationDegrees.toDouble())
            val cosVal = abs(cos(rad)).toFloat()
            val sinVal = abs(sin(rad)).toFloat()
            
            val w = originalBitmap.width.toFloat()
            val h = originalBitmap.height.toFloat()
            
            val scaleFactor = max(
                (w * cosVal + h * sinVal) / w,
                (h * cosVal + w * sinVal) / h
            )

            // 6. Effective mapping ratio
            val effectiveRatio = baseRatio / scaleFactor

            // 7. Anchor mapping to the absolute CENTER of the image
            val uiCenterX = imageRectOnScreen.left + imageRectOnScreen.width / 2f
            val uiCenterY = imageRectOnScreen.top + imageRectOnScreen.height / 2f

            val physCenterX = rotatedBitmap.width / 2f
            val physCenterY = rotatedBitmap.height / 2f

            val cropUiCenterX = cropBox.left + cropBox.width / 2f
            val cropUiCenterY = cropBox.top + cropBox.height / 2f

            val dxUi = cropUiCenterX - uiCenterX
            val dyUi = cropUiCenterY - uiCenterY

            val dxPhys = dxUi * effectiveRatio
            val dyPhys = dyUi * effectiveRatio

            val physCropCenterX = physCenterX + dxPhys
            val physCropCenterY = physCenterY + dyPhys

            val physCropWidth = cropBox.width * effectiveRatio
            val physCropHeight = cropBox.height * effectiveRatio

            // 8. Calculate final bounding cut points
            var cropLeft = (physCropCenterX - physCropWidth / 2f).toInt()
            var cropTop = (physCropCenterY - physCropHeight / 2f).toInt()
            var cropWidth = physCropWidth.toInt()
            var cropHeight = physCropHeight.toInt()

            // 9. Safely clamp
            cropLeft = cropLeft.coerceIn(0, rotatedBitmap.width)
            cropTop = cropTop.coerceIn(0, rotatedBitmap.height)
            cropWidth = cropWidth.coerceIn(1, rotatedBitmap.width - cropLeft)
            cropHeight = cropHeight.coerceIn(1, rotatedBitmap.height - cropTop)

            // 10. Execute final cut
            val finalBitmap = Bitmap.createBitmap(rotatedBitmap, cropLeft, cropTop, cropWidth, cropHeight)
            
            if (rotatedBitmap != originalBitmap) rotatedBitmap.recycle()
            originalBitmap.recycle()

            finalBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
