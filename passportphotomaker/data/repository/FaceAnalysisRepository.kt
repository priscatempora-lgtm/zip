package com.example.passportphotomaker.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.example.passportphotomaker.data.inference.FaceLandmarkerDetector
import com.example.passportphotomaker.data.inference.FaceSegmentationDetector
import com.example.passportphotomaker.domain.model.FaceData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * Orchestrates the two-stage face analysis pipeline:
 *
 *  1. **FaceLandmarker** (MediaPipe Tasks) â€” runs face detection and 478-point
 *     landmark extraction in a single call.  This replaces the former
 *     BlazeFace + FaceMesh two-step chain.  The Tasks SDK bundles the custom
 *     C++ ops (Landmarks2TransformMatrix, etc.) required by the Attention model,
 *     which the standard TFLite runtime cannot provide.
 *
 *  2. **FaceSegmentationDetector** (raw TFLite) â€” generates the per-pixel skin
 *     mask used by the beauty effects pipeline.  Unchanged from before.
 *
 * All inference runs on [Dispatchers.Default] so it never blocks the UI thread.
 * Detectors are initialised lazily on first use and kept alive for the lifetime
 * of the repository â€” call [close] when the owning ViewModel is cleared.
 */
class FaceAnalysisRepository(private val context: Context) : AutoCloseable {

    private val landmarker by lazy { FaceLandmarkerDetector(context) }
    private val segmenter  by lazy { FaceSegmentationDetector(context) }

    /**
     * Runs the full two-stage pipeline on [bitmap].
     *
     * @return [FaceData] if a face was detected, or null if no face is found.
     */
        suspend fun analyse(
        bitmap: Bitmap,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): FaceData? = withContext(Dispatchers.Default) {

        onProgress(0.1f, "Detecting face + landmarks…")
        val (box, landmarks) = landmarker.detect(bitmap) ?: return@withContext null

        onProgress(0.7f, "Generating skin mask…")
        val mask = segmenter.segment(bitmap, box)

        // 1. Create a temporary model to hold the raw scan data
        val rawFaceData = FaceData(
            boundingBox = box,
            landmarks = landmarks,
            segmentationMask = mask,
            
            // Temporary empty arrays (FaceMaskGenerator will overwrite them immediately)
            width = bitmap.width, height = bitmap.height,
            refinedMask = FloatArray(0), sharpMask = FloatArray(0), blemishMask = FloatArray(0), 
            brightnessMask = FloatArray(0), eyebrowMask = FloatArray(0), eyesMask = FloatArray(0), 
            eyeBagsMask = FloatArray(0), irisMask = FloatArray(0), teethMask = FloatArray(0)
        )

        onProgress(0.9f, "Building beauty masks…")
        // 2. MAGICIANS AT WORK: Turn the raw scan into the 9 flat masks
        val finalFaceData = com.example.passportphotomaker.domain.util.FaceMaskGenerator.generateNativeMasks(
            faceData = rawFaceData,
            width = bitmap.width,
            height = bitmap.height
        )

        onProgress(1.0f, "Done")
        finalFaceData
    }
    
    override fun close() {
        runCatching { landmarker.close() }
        runCatching { segmenter.close() }
    }
}