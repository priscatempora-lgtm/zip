package com.example.passportphotomaker.domain.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * Background/subject removal using the same BUNDLED TFLite model as
 * FaceSegmentationDetector (selfie_multiclass_256x256.tflite) — no network,
 * no Play Services, works offline on first install.
 *
 * Runs the model ONCE per removeBackground() call and closes it immediately
 * afterward (see original rationale: no resident copy overlapping with
 * FaceSegmentationDetector).
 *
 * HAIR-ACCURACY PIPELINE (v2):
 *  1. Softmax over the 6 class logits -> a genuinely SOFT foreground
 *     probability (1 - P(background)). The old "fg > bg ? fg : 0" hard gate
 *     snapped every borderline hair pixel to 0, destroying strand detail
 *     before refinement even started.
 *  2. The soft mask is refined with a COLOR (RGB) Fast Guided Filter
 *     (He et al.) instead of a grayscale one. Hair frequently differs from
 *     the background in hue but not in luminance (brown hair on a warm-grey
 *     wall); an RGB guide separates those cases, a luma guide cannot.
 *  3. Filter statistics are computed at GUIDE_SIZE=512 (not the model's 256),
 *     with a small radius and small epsilon, so individual strands survive.
 *     The a/b coefficient maps are then bilinearly upsampled and evaluated
 *     against the FULL-RESOLUTION pixel color — the standard Fast Guided
 *     Filter trick, so per-pixel cost at photo resolution stays trivial.
 *  4. Gentle smoothstep alpha shaping: cleans mask noise without amputating
 *     faint strands the way the old hard [0.25, 0.75] stretch did.
 *
 * Input is letterboxed (aspect-ratio-preserving pad), matching
 * FaceSegmentationDetector — a naive squish-resize shifts the mask boundary
 * on non-square photos.
 */
object BackgroundRemover {

    private const val MODEL_FILE = "selfie_multiclass_256x256.tflite"
    private const val INPUT_SIZE = 256
    private const val NUM_CLASSES = 6
    private const val TAG = "BackgroundRemover"

    // Resolution at which the guided filter statistics are computed. Higher
    // than the model's 256 so fine hair strands in the guide image are not
    // averaged away. 512 keeps transient memory ~20 MB of float scratch and
    // runs in well under a second on-device; the OOM catch below is the
    // safety net on very constrained devices.
    private const val GUIDE_SIZE = 512

    // Guided filter parameters. Small radius + small eps = tight edge
    // adherence (good for strands). Radius is in GUIDE_SIZE pixels.
    private const val GF_RADIUS = 4
    private const val GF_EPS = 2e-4f

    // Alpha shaping band: probabilities below LOW are treated as pure
    // background noise, above HIGH as solid foreground; in between we keep a
    // smooth translucent ramp so hair wisps stay semi-transparent.
    private const val ALPHA_LOW = 0.05f
    private const val ALPHA_HIGH = 0.95f

    /**
     * Returns a copy of [source] with background pixels made transparent, or
     * null if segmentation could not be performed (never throws to the caller).
     */
    suspend fun removeBackground(context: Context, source: Bitmap): Bitmap? =
        withContext(Dispatchers.Default) {
            var interpreter: Interpreter? = null
            var gpuDelegate: GpuDelegate? = null
            try {
                if (source.isRecycled || source.width <= 0 || source.height <= 0) return@withContext null

                val model = loadModelFile(context.applicationContext, MODEL_FILE)
                val (builtInterpreter, builtGpuDelegate) = buildInterpreter(model)
                interpreter = builtInterpreter
                gpuDelegate = builtGpuDelegate

                val w = source.width
                val h = source.height

                // ---------- 1. MODEL INFERENCE AT 256 ----------
                val inScale = minOf(INPUT_SIZE / w.toFloat(), INPUT_SIZE / h.toFloat())
                val inW = (w * inScale).toInt().coerceAtLeast(1)
                val inH = (h * inScale).toInt().coerceAtLeast(1)

                val letterboxed = letterboxBitmap(source, inW, inH, INPUT_SIZE)
                val inputBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
                    .order(ByteOrder.nativeOrder())
                val px256 = IntArray(INPUT_SIZE * INPUT_SIZE)
                letterboxed.getPixels(px256, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
                letterboxed.recycle()
                for (p in px256) {
                    inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f)
                    inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)
                    inputBuffer.putFloat((p and 0xFF) / 255f)
                }

                val outputBuffer = ByteBuffer
                    .allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * NUM_CLASSES)
                    .order(ByteOrder.nativeOrder())
                inputBuffer.rewind()
                interpreter.run(inputBuffer, outputBuffer)
                outputBuffer.rewind()

                // ---------- 2. SOFT FOREGROUND PROBABILITY (softmax) ----------
                // fg = 1 - P(background). Crucially this is CONTINUOUS: a
                // pixel that is 40% hair / 60% background gets alpha ~0.4 as
                // a starting point instead of being zeroed by a hard argmax.
                val mask256 = FloatArray(INPUT_SIZE * INPUT_SIZE)
                val logits = FloatArray(NUM_CLASSES)
                for (i in 0 until INPUT_SIZE * INPUT_SIZE) {
                    var maxLogit = Float.NEGATIVE_INFINITY
                    for (c in 0 until NUM_CLASSES) {
                        val v = outputBuffer.float
                        logits[c] = v
                        if (v > maxLogit) maxLogit = v
                    }
                    var sum = 0f
                    var bgExp = 0f
                    for (c in 0 until NUM_CLASSES) {
                        val e = exp(logits[c] - maxLogit)
                        sum += e
                        if (c == 0) bgExp = e
                    }
                    mask256[i] = 1f - (bgExp / sum)
                }

                // ---------- 3. BUILD RGB GUIDE AT GUIDE_SIZE ----------
                val gScale = minOf(GUIDE_SIZE / w.toFloat(), GUIDE_SIZE / h.toFloat())
                val gW = (w * gScale).toInt().coerceAtLeast(1)
                val gH = (h * gScale).toInt().coerceAtLeast(1)
                val gPadX = (GUIDE_SIZE - gW) / 2f
                val gPadY = (GUIDE_SIZE - gH) / 2f

                val guideBmp = letterboxBitmap(source, gW, gH, GUIDE_SIZE)
                val n = GUIDE_SIZE * GUIDE_SIZE
                val gR = FloatArray(n)
                val gG = FloatArray(n)
                val gB = FloatArray(n)
                run {
                    val gpx = IntArray(n)
                    guideBmp.getPixels(gpx, 0, GUIDE_SIZE, 0, 0, GUIDE_SIZE, GUIDE_SIZE)
                    guideBmp.recycle()
                    for (i in 0 until n) {
                        val p = gpx[i]
                        gR[i] = ((p shr 16) and 0xFF) / 255f
                        gG[i] = ((p shr 8) and 0xFF) / 255f
                        gB[i] = (p and 0xFF) / 255f
                    }
                }

                // Bilinearly upsample the 256 soft mask to GUIDE_SIZE. Both
                // are letterboxes of the same image with the same aspect, so
                // a plain resolution-ratio mapping is geometrically correct
                // (up to sub-pixel rounding of the pads).
                val maskP = FloatArray(n)
                run {
                    val ratio = INPUT_SIZE.toFloat() / GUIDE_SIZE
                    for (y in 0 until GUIDE_SIZE) {
                        val sy = ((y + 0.5f) * ratio - 0.5f).coerceIn(0f, INPUT_SIZE - 1f)
                        val y0 = sy.toInt()
                        val y1 = minOf(y0 + 1, INPUT_SIZE - 1)
                        val dy = sy - y0
                        val row = y * GUIDE_SIZE
                        for (x in 0 until GUIDE_SIZE) {
                            val sx = ((x + 0.5f) * ratio - 0.5f).coerceIn(0f, INPUT_SIZE - 1f)
                            val x0 = sx.toInt()
                            val x1 = minOf(x0 + 1, INPUT_SIZE - 1)
                            val dx = sx - x0
                            maskP[row + x] =
                                mask256[y0 * INPUT_SIZE + x0] * (1 - dx) * (1 - dy) +
                                mask256[y0 * INPUT_SIZE + x1] * dx * (1 - dy) +
                                mask256[y1 * INPUT_SIZE + x0] * (1 - dx) * dy +
                                mask256[y1 * INPUT_SIZE + x1] * dx * dy
                        }
                    }
                }

                // ---------- 4. COLOR (RGB) FAST GUIDED FILTER ----------
                val gf = colorGuidedFilter(gR, gG, gB, maskP, GUIDE_SIZE, GUIDE_SIZE, GF_RADIUS, GF_EPS)
                val meanAr = gf.aR
                val meanAg = gf.aG
                val meanAb = gf.aB
                val meanB = gf.b

                // ---------- 5. APPLY AT FULL RESOLUTION, ROW-BY-ROW ----------
                val out = source.copy(Bitmap.Config.ARGB_8888, true)
                out.setHasAlpha(true)
                val rowPixels = IntArray(w)
                val gMax = GUIDE_SIZE - 1f

                for (y in 0 until h) {
                    out.getPixels(rowPixels, 0, w, 0, y, w, 1)
                    // Pixel-center-aligned mapping into guide coordinates.
                    val srcY = ((y + 0.5f) * gScale + gPadY - 0.5f).coerceIn(0f, gMax)
                    val y0 = srcY.toInt()
                    val y1 = minOf(y0 + 1, GUIDE_SIZE - 1)
                    val dy = srcY - y0
                    val row0 = y0 * GUIDE_SIZE
                    val row1 = y1 * GUIDE_SIZE

                    for (x in 0 until w) {
                        val srcX = ((x + 0.5f) * gScale + gPadX - 0.5f).coerceIn(0f, gMax)
                        val x0 = srcX.toInt()
                        val x1 = minOf(x0 + 1, GUIDE_SIZE - 1)
                        val dx = srcX - x0

                        val w00 = (1 - dx) * (1 - dy)
                        val w10 = dx * (1 - dy)
                        val w01 = (1 - dx) * dy
                        val w11 = dx * dy

                        val aR = meanAr[row0 + x0] * w00 + meanAr[row0 + x1] * w10 +
                                 meanAr[row1 + x0] * w01 + meanAr[row1 + x1] * w11
                        val aG = meanAg[row0 + x0] * w00 + meanAg[row0 + x1] * w10 +
                                 meanAg[row1 + x0] * w01 + meanAg[row1 + x1] * w11
                        val aB = meanAb[row0 + x0] * w00 + meanAb[row0 + x1] * w10 +
                                 meanAb[row1 + x0] * w01 + meanAb[row1 + x1] * w11
                        val bC = meanB[row0 + x0] * w00 + meanB[row0 + x1] * w10 +
                                 meanB[row1 + x0] * w01 + meanB[row1 + x1] * w11

                        val pxColor = rowPixels[x]

                        // Guided filter output: q = a · I_highres + b
                        val q = aR * (((pxColor shr 16) and 0xFF) / 255f) +
                                aG * (((pxColor shr 8) and 0xFF) / 255f) +
                                aB * ((pxColor and 0xFF) / 255f) +
                                bC

                        // Gentle shaping: kill noise below ALPHA_LOW, saturate
                        // above ALPHA_HIGH, smooth translucent ramp between —
                        // hair wisps live in that ramp.
                        val alphaFloat = smoothstep(ALPHA_LOW, ALPHA_HIGH, q)
                        val finalAlpha = (alphaFloat * 255f + 0.5f).toInt().coerceIn(0, 255)

                        rowPixels[x] = if (finalAlpha == 0) {
                            0
                        } else {
                            // New alpha, original RGB preserved (prevents dark halos).
                            (finalAlpha shl 24) or (pxColor and 0x00FFFFFF)
                        }
                    }
                    out.setPixels(rowPixels, 0, w, 0, y, w, 1)
                }
                out
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM during background removal (${source.width}x${source.height})", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Background removal failed", e)
                null
            } finally {
                interpreter?.close()
                gpuDelegate?.close()
            }
        }

    // ---------------------------------------------------------------------
    // Color guided filter
    // ---------------------------------------------------------------------

    private class GuidedCoeffs(
        val aR: FloatArray,
        val aG: FloatArray,
        val aB: FloatArray,
        val b: FloatArray,
    )

    /**
     * Color Guided Filter (He, Sun, Tang — "Guided Image Filtering", Eq. 19–21).
     * For each window: a = (Sigma + eps*I)^-1 * cov(I, p);  b = mean_p - a·mean_I.
     * Returns the box-smoothed coefficient maps mean_a (3 planes) and mean_b,
     * ready to be evaluated as q = a·I + b at any resolution.
     */
    private fun colorGuidedFilter(
        r: FloatArray, g: FloatArray, b: FloatArray, p: FloatArray,
        w: Int, h: Int, radius: Int, eps: Float,
    ): GuidedCoeffs {
        val n = w * h

        val meanR = boxFilter(r, w, h, radius)
        val meanG = boxFilter(g, w, h, radius)
        val meanB = boxFilter(b, w, h, radius)
        val meanP = boxFilter(p, w, h, radius)

        // Second-moment terms (reuse one scratch buffer for the products).
        val scratch = FloatArray(n)

        fun moment(x: FloatArray, y: FloatArray): FloatArray {
            for (i in 0 until n) scratch[i] = x[i] * y[i]
            return boxFilter(scratch, w, h, radius)
        }

        val mRR = moment(r, r); val mRG = moment(r, g); val mRB = moment(r, b)
        val mGG = moment(g, g); val mGB = moment(g, b); val mBB = moment(b, b)
        val mRP = moment(r, p); val mGP = moment(g, p); val mBP = moment(b, p)

        val aR = FloatArray(n)
        val aG = FloatArray(n)
        val aB = FloatArray(n)
        val bArr = FloatArray(n)

        for (i in 0 until n) {
            // Covariance matrix of the guide (symmetric 3x3) + eps on the diagonal.
            val vRR = mRR[i] - meanR[i] * meanR[i] + eps
            val vRG = mRG[i] - meanR[i] * meanG[i]
            val vRB = mRB[i] - meanR[i] * meanB[i]
            val vGG = mGG[i] - meanG[i] * meanG[i] + eps
            val vGB = mGB[i] - meanG[i] * meanB[i]
            val vBB = mBB[i] - meanB[i] * meanB[i] + eps

            // cov(I, p)
            val cR = mRP[i] - meanR[i] * meanP[i]
            val cG = mGP[i] - meanG[i] * meanP[i]
            val cB = mBP[i] - meanB[i] * meanP[i]

            // Invert the symmetric 3x3 via cofactors.
            val c00 = vGG * vBB - vGB * vGB
            val c01 = vGB * vRB - vRG * vBB
            val c02 = vRG * vGB - vGG * vRB
            var det = vRR * c00 + vRG * c01 + vRB * c02
            if (det == 0f) det = 1e-12f
            val inv = 1f / det

            val c11 = vRR * vBB - vRB * vRB
            val c12 = vRG * vRB - vRR * vGB
            val c22 = vRR * vGG - vRG * vRG

            val ar = (c00 * cR + c01 * cG + c02 * cB) * inv
            val ag = (c01 * cR + c11 * cG + c12 * cB) * inv
            val ab = (c02 * cR + c12 * cG + c22 * cB) * inv

            aR[i] = ar
            aG[i] = ag
            aB[i] = ab
            bArr[i] = meanP[i] - ar * meanR[i] - ag * meanG[i] - ab * meanB[i]
        }

        // Final smoothing of the coefficient maps (this is what makes the
        // filter's output halo-free).
        return GuidedCoeffs(
            aR = boxFilter(aR, w, h, radius),
            aG = boxFilter(aG, w, h, radius),
            aB = boxFilter(aB, w, h, radius),
            b = boxFilter(bArr, w, h, radius),
        )
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Same three-tier fallback as FaceSegmentationDetector: GPU -> NNAPI -> CPU. */
    private fun buildInterpreter(model: MappedByteBuffer): Pair<Interpreter, GpuDelegate?> {
        val compatList = CompatibilityList()
        var gpu: GpuDelegate? = null

        val interpreter = if (compatList.isDelegateSupportedOnThisDevice) {
            try {
                gpu = GpuDelegate(compatList.bestOptionsForThisDevice)
                Interpreter(model, Interpreter.Options().addDelegate(gpu))
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate failed, falling back to NNAPI", e)
                gpu?.close(); gpu = null
                try {
                    Interpreter(model, Interpreter.Options().setUseNNAPI(true).setNumThreads(4))
                } catch (e2: Exception) {
                    Log.w(TAG, "NNAPI failed, falling back to CPU", e2)
                    Interpreter(model, Interpreter.Options().setNumThreads(4))
                }
            }
        } else {
            try {
                Interpreter(model, Interpreter.Options().setUseNNAPI(true).setNumThreads(4))
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI failed, falling back to CPU", e)
                Interpreter(model, Interpreter.Options().setNumThreads(4))
            }
        }
        return interpreter to gpu
    }

    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        val assetFd = context.assets.openFd(filename)
        return FileInputStream(assetFd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFd.startOffset,
            assetFd.declaredLength
        )
    }

    /** Cubic ease used to feather the mask edge instead of a hard threshold. */
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Scale-to-fit, centre on black canvas of [size] x [size]. */
    private fun letterboxBitmap(src: Bitmap, scaledW: Int, scaledH: Int, size: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val letterboxed = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        canvas.drawBitmap(scaled, (size - scaledW) / 2f, (size - scaledH) / 2f, null)
        if (scaled !== src) scaled.recycle()
        return letterboxed
    }

    /**
     * Mean filter with a true O(N) sliding window (running sum) — the cost is
     * independent of the radius, unlike the previous O(N*r) inner loops,
     * which matters now that we filter at 512x512 with ~17 passes.
     * Windows are clamped at the borders (normalized by actual pixel count).
     */
    private fun boxFilter(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val dest = FloatArray(w * h)
        val temp = FloatArray(w * h)

        // Horizontal pass (running sum along each row)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            // Prime the window for x = 0: covers [0, r]
            val prime = minOf(r, w - 1)
            for (kx in 0..prime) sum += src[row + kx]
            var lo = 0          // inclusive left edge of window
            var hi = prime      // inclusive right edge of window
            for (x in 0 until w) {
                temp[row + x] = sum / (hi - lo + 1)
                // Slide window right for next x
                val newHi = x + 1 + r
                if (newHi < w) { sum += src[row + newHi]; hi = newHi }
                val newLo = x + 1 - r
                if (newLo > 0) { sum -= src[row + newLo - 1]; lo = newLo }
            }
        }

        // Vertical pass (running sum along each column)
        for (x in 0 until w) {
            var sum = 0f
            val prime = minOf(r, h - 1)
            for (ky in 0..prime) sum += temp[ky * w + x]
            var lo = 0
            var hi = prime
            for (y in 0 until h) {
                dest[y * w + x] = sum / (hi - lo + 1)
                val newHi = y + 1 + r
                if (newHi < h) { sum += temp[newHi * w + x]; hi = newHi }
                val newLo = y + 1 - r
                if (newLo > 0) { sum -= temp[(newLo - 1) * w + x]; lo = newLo }
            }
        }
        return dest
    }
}
