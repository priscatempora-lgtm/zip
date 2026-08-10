package com.example.passportphotomaker.presentation.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.passportphotomaker.data.repository.FaceAnalysisRepository
import com.example.passportphotomaker.domain.model.CropBox
import com.example.passportphotomaker.domain.model.CropRatio
import com.example.passportphotomaker.domain.model.FaceData
import com.example.passportphotomaker.domain.model.ProjectState
import com.example.passportphotomaker.domain.model.ProjectType
import com.example.passportphotomaker.domain.repository.ProjectRepository
import com.example.passportphotomaker.domain.model.PrintProjectDraft
import com.example.passportphotomaker.domain.repository.PrintProjectRepository
import com.example.passportphotomaker.domain.model.PassportSizePreset
import com.example.passportphotomaker.domain.model.PrintBatch
import com.example.passportphotomaker.domain.model.TextFontFamily
import com.example.passportphotomaker.domain.model.TextLayer
import com.example.passportphotomaker.domain.util.ImageExporter
import com.example.passportphotomaker.nativebridge.NativeBeautyEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ActiveTool { NONE, RATIO, ROTATE }

enum class GridType(val title: String) {
    RULE_OF_THIRDS("Rule of Thirds"),
    CENTER_LINES("Center Lines"),
    PASSPORT_GUIDE("Passport Guide"),
    NONE("None")
}

class EditorViewModel(
    private val repository: ProjectRepository,
    private val projectId: String
) : ViewModel() {


    private var currentBatchPhotoProjectId: String? = null

    private var currentBatchGroupId: String? = null

    // =========================================================================
    // BEAUTY SESSION STATE
    // =========================================================================

    /**
     * Full-resolution pristine copy of the bitmap when the beauty session
     * started. Never written to by live-slider rendering â€” only used as the
     * source for the one-time full-res bake on "Next" and for saving
     * [STEP1_FILENAME].
     */
    private var originalBeautySource: Bitmap? = null

    /**
     * Downscaled copy of [originalBeautySource], capped at [PREVIEW_MAX_DIM]
     * on each side. All live-slider renders target this so the C++ engine
     * never processes a full 12 MP image during interactive editing.
     */
    private var previewBitmap: Bitmap? = null

    /**
     * Face-scan result whose mask arrays are dimensioned for [previewBitmap].
     * Cleared on "Back" from BackgroundScreen so a fresh scan runs against the
     * restored pristine image.
     */
    private var cachedFaceData: FaceData? = null

    /**
     * Live-preview output exposed to RetouchScreen. Updated by every completed
     * render pass; never the same object as [previewBitmap].
     */
    private val _beautyPreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val beautyPreviewBitmap: StateFlow<Bitmap?> = _beautyPreviewBitmap.asStateFlow()

    /** Active coroutine job for live preview rendering. */
    private var beautyRenderJob: Job? = null

    /**
     * Active coroutine job for the ML face scan. Stored so it can be
     * explicitly cancelled when the user navigates back before the scan
     * finishes, preventing reads on a recycled [previewBitmap].
     */
    private var faceScanJob: Job? = null

    /**
     * Serialises native render passes. Because [NativeBeautyEngine.processImage]
     * is a blocking JNI call with no suspension points, coroutine [cancel] cannot
     * interrupt it mid-execution. The mutex ensures only one render runs at a time
     * and that [_beautyPreviewBitmap] is swapped and recycled atomically, preventing
     * the double-recycle race condition caused by concurrent coroutines.
     */
    private val renderMutex = Mutex()

    /**
     * Monotonically increasing counter used to discard results from render
     * passes that were superseded by a newer slider movement before they
     * finished.
     *
     * MUST be @Volatile: it is written on the main thread (inside
     * [renderBeautyPreview]) and read on [Dispatchers.Default] inside the
     * render coroutine.  Without @Volatile, the JVM/CPU is free to serve the
     * background thread a stale cached value, causing the stale-drop check
     * (`currentRequestId != renderCounter`) to silently pass for two concurrent
     * renders â€” both proceed, both recycle the same old bitmap, and the app
     * crashes with a double-recycle SIGSEGV.
     */
    @Volatile
    private var renderCounter = 0

    private val _isScanningFace = MutableStateFlow(false)
    val isScanningFace: StateFlow<Boolean> = _isScanningFace.asStateFlow()

    // Latest confirmed slider values â€” used by [bakeFullResBeauty] so the
    // full-res pass applies exactly what the user saw in the preview.
    private var lastSkinBrightness    = 0f
    private var lastBlemishReduction  = 0f
    private var lastUnderEyeReduction = 0f
    private var lastEyeBrightening    = 0f
    private var lastTeethWhitening    = 0f
    private var lastFaceSharpening    = 0f
    private var lastEyebrowDefinition = 0f

    // =========================================================================
    // PROJECT / TOOL STATE
    // =========================================================================

    private val _projectState = MutableStateFlow<ProjectState?>(null)
    val projectState: StateFlow<ProjectState?> = _projectState.asStateFlow()

    private val _activeTool = MutableStateFlow(ActiveTool.NONE)
    val activeTool: StateFlow<ActiveTool> = _activeTool.asStateFlow()

    private val _currentGrid = MutableStateFlow(GridType.RULE_OF_THIRDS)
    val currentGrid: StateFlow<GridType> = _currentGrid.asStateFlow()

    private val _currentCropBox = MutableStateFlow<CropBox?>(null)
    val currentCropBox: StateFlow<CropBox?> = _currentCropBox.asStateFlow()

    private val _finalCroppedBitmap = MutableStateFlow<Bitmap?>(null)
    val finalCroppedBitmap: StateFlow<Bitmap?> = _finalCroppedBitmap.asStateFlow()

    private val _textLayers = MutableStateFlow<List<TextLayer>>(emptyList())
    val textLayers: StateFlow<List<TextLayer>> = _textLayers.asStateFlow()

    private val _selectedTextLayerId = MutableStateFlow<String?>(null)
    val selectedTextLayerId: StateFlow<String?> = _selectedTextLayerId.asStateFlow()

    // Fix 2: expose saving progress so the UI can show a blocking dialog while
    // ALL IO (gallery write + private file + Room insert) runs on Dispatchers.IO.
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    // Fix 3: persist the processed background cutout across back-navigation so
    // BackgroundScreen never re-runs the heavy ML engine when the user presses Back.
    private val _processedCutoutBitmap = MutableStateFlow<Bitmap?>(null)
    val processedCutoutBitmap: StateFlow<Bitmap?> = _processedCutoutBitmap.asStateFlow()

    fun storeProcessedCutout(bitmap: Bitmap) {
        _processedCutoutBitmap.value = bitmap
    }

   // Vault to protect the pre-background image from being overwritten
    private val _pristineBackgroundBitmap = MutableStateFlow<Bitmap?>(null)
    val pristineBackgroundBitmap: StateFlow<Bitmap?> = _pristineBackgroundBitmap.asStateFlow()

    fun capturePristineBackground(bitmap: Bitmap) {
        // Only lock the image in if the vault is empty. 
        // This makes it immune to the blue background overwrite later!
        if (_pristineBackgroundBitmap.value == null || _pristineBackgroundBitmap.value?.isRecycled == true) {
            _pristineBackgroundBitmap.value = bitmap
        }
    }


    // =========================================================================
    // PRESET / PRINT TRACKING
    // =========================================================================

    private val _selectedPreset = MutableStateFlow(PassportSizePreset.SCHENGEN_VISA)
    val selectedPreset: StateFlow<PassportSizePreset> = _selectedPreset.asStateFlow()

    private val _customPrintWidthMm = MutableStateFlow<Float?>(null)
    val customPrintWidthMm: StateFlow<Float?> = _customPrintWidthMm.asStateFlow()

    private val _customPrintHeightMm = MutableStateFlow<Float?>(null)
    val customPrintHeightMm: StateFlow<Float?> = _customPrintHeightMm.asStateFlow()

    private val _requestedCopies = MutableStateFlow<Int?>(null)
    val requestedCopies: StateFlow<Int?> = _requestedCopies.asStateFlow()

    private val _customGapMm = MutableStateFlow(10f)
    val customGapMm: StateFlow<Float> = _customGapMm.asStateFlow()

    private val _showCuttingGuides = MutableStateFlow(true)
    val showCuttingGuides: StateFlow<Boolean> = _showCuttingGuides.asStateFlow()

    private val _printBatches = MutableStateFlow<List<PrintBatch>>(emptyList())
    val printBatches: StateFlow<List<PrintBatch>> = _printBatches.asStateFlow()

    private val _batchPaperSizeId = MutableStateFlow<String?>(null)
    val batchPaperSizeId: StateFlow<String?> = _batchPaperSizeId.asStateFlow()

    /** True while the user is editing a new image to add to an existing batch on LayoutConfig. */
    private val _isBatchMode = MutableStateFlow(false)
    val isBatchMode: StateFlow<Boolean> = _isBatchMode.asStateFlow()

    /**
     * Fires exactly once (per edit) when the user successfully completes a full
     * Crop â†’ Retouch â†’ Background cycle and is about to return to LayoutConfig.
     * [PhotoSizeAdjustmentScreen] collects this event to append the new image â€”
     * never automatically on [finalCroppedBitmap] change â€” preventing ghost
     * images from aborted sessions from appearing in the layout.
     */
    private val _batchEditSuccessEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val batchEditSuccessEvent: SharedFlow<String> = _batchEditSuccessEvent.asSharedFlow()

    // =========================================================================
    // CONSTANTS
    // =========================================================================

    private companion object {
        /** Cached un-baked crop; reloaded on "Back" from BackgroundScreen. */
        const val STEP1_FILENAME  = "step_1_crop.webp"

        /** Cached beauty-only full-res bake; written when the user taps "Next". */
        const val STEP2_FILENAME  = "step_2_beauty.webp"

        /** Cached background result; Text overlays must never replace this source image. */
        const val STEP3_FILENAME  = "step_3_background.webp"

        /**
         * Maximum width or height of the downscaled preview bitmap.
         * Keeps the C++ engine working on â‰¤ 2 MP instead of 12 MP during
         * interactive editing.
         */
        const val PREVIEW_MAX_DIM = 1080
    }

    init {
        loadProject()
    }

    private fun loadProject() {
        viewModelScope.launch {
            _projectState.value = repository.getProjectById(projectId)?.let { persisted ->
                // Standalone edits keep their ratio in staging-only metadata
                // until export, so discard can still restore the old project.
                persisted.copy(cropRatio = persisted.stagingCropRatio ?: persisted.cropRatio)
            }
        }
    }

    fun setActiveTool(tool: ActiveTool) {
        _activeTool.value = tool
    }

    /**
     * Restores text overlays that were saved by a standalone Text edit.
     * The project load is asynchronous, so wait for it before reading the
     * persisted metadata.
     */
    suspend fun loadStagedTextLayers() {
        val state = _projectState.value ?: _projectState.filterNotNull().first()
        _textLayers.value = state.stagingTextLayers ?: emptyList()
    }

    fun addTextLayer(text: String = "Tap to edit") {
        val layer = TextLayer(text = text)
        _textLayers.value = _textLayers.value + layer
        _selectedTextLayerId.value = layer.id
    }

    fun updateTextLayer(id: String, transform: (TextLayer) -> TextLayer) {
        _textLayers.value = _textLayers.value.map {
            if (it.id == id) transform(it) else it
        }
    }

    fun removeTextLayer(id: String) {
        _textLayers.value = _textLayers.value.filterNot { it.id == id }
        if (_selectedTextLayerId.value == id) {
            _selectedTextLayerId.value = null
        }
    }

    fun selectTextLayer(id: String?) {
        _selectedTextLayerId.value = id
    }

    fun clearTextLayers() {
        _textLayers.value = emptyList()
        _selectedTextLayerId.value = null
    }

    /**
     * Text editing always starts from the background result, just like the
     * earlier editor tools start from their own checkpoint. This prevents a
     * previously baked export from becoming the next editable source.
     */
    suspend fun loadTextSourceCheckpoint(context: Context): Boolean {
        // BackgroundScreen has already installed the current image in the
        // ViewModel before navigating here. Reusing it avoids replacing and
        // recycling a bitmap that Compose or the batch hand-off is drawing.
        val existing = _finalCroppedBitmap.value
        if (existing != null && !existing.isRecycled) return true

        val cached = loadFromCache(context, STEP3_FILENAME) ?: return false
        withContext(Dispatchers.Main) {
            setCroppedBitmap(cached)
        }
        return true
    }

    private fun bakeTextLayersOntoBitmap(
        source: Bitmap,
        layers: List<TextLayer>,
        context: Context
    ): Bitmap {
        if (layers.isEmpty()) return source
        val out = source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(out)
        val density = context.resources.displayMetrics.density
        val previewWidth = context.resources.displayMetrics.widthPixels
            .coerceAtLeast(1)
            .toFloat()
        val imageScale = (out.width / previewWidth).coerceAtLeast(1f)

        layers.forEach { layer ->
            val paint = android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG
            ).apply {
                color = layer.colorArgb
                textSize = layer.fontSizeSp * density * imageScale
                typeface = android.graphics.Typeface.create(
                    when (layer.fontFamily) {
                        TextFontFamily.SANS_SERIF -> android.graphics.Typeface.SANS_SERIF
                        TextFontFamily.SERIF -> android.graphics.Typeface.SERIF
                        TextFontFamily.MONOSPACE -> android.graphics.Typeface.MONOSPACE
                    },
                    if (layer.isBold) {
                        android.graphics.Typeface.BOLD
                    } else {
                        android.graphics.Typeface.NORMAL
                    }
                )
                textAlign = android.graphics.Paint.Align.CENTER
            }

            val x = layer.xFrac * out.width
            val y = layer.yFrac * out.height
            canvas.save()
            canvas.rotate(layer.rotationDegrees, x, y)
            canvas.drawText(layer.text, x, y, paint)
            canvas.restore()
        }
        return out
    }

    /**
     * Standalone Text "Done": persist editable metadata without changing the
     * staged bitmap. This lets the user reopen Text and continue editing.
     */
    suspend fun saveStagedTextLayers() = withContext(Dispatchers.IO) {
        val current = repository.getProjectById(projectId)
            ?: _projectState.value
            ?: return@withContext
        val updated = current.copy(
            stagingTextLayers = _textLayers.value.ifEmpty { null }
        )
        _projectState.value = updated
        repository.saveProject(updated)
    }

    /**
     * Creates a print/export bitmap without changing the editable source
     * bitmap. The source remains the cached Background result.
     */
    suspend fun createPrintReadyBitmap(context: Context): Bitmap? =
        withContext(Dispatchers.IO) {
            val source = _finalCroppedBitmap.value
                ?: return@withContext null
            if (source.isRecycled) return@withContext null
            val state = _projectState.value ?: _projectState.filterNotNull().first()
            val layers = state.stagingTextLayers ?: _textLayers.value
            withContext(Dispatchers.Default) {
                bakeTextLayersOntoBitmap(source, layers, context)
            }
        }

    /**
     * Standalone and standard Text "Done": persist editable metadata only.
     * Text is committed later, at the terminal Digital/Print action.
     */
    suspend fun saveTextLayersForNextStep() {
        saveStagedTextLayers()
    }

    /**
     * Standalone My Studio Text "Done" is a terminal edit for the current
     * studio session. Flatten the overlays into the staged image so the
     * Project Details thumbnail and later exports use the edited pixels.
     * Metadata is cleared because the text is now part of the image.
     */
    suspend fun saveStandaloneTextEdit(context: Context): String? =
        withContext(Dispatchers.IO) {
            val source = _finalCroppedBitmap.value
                ?: return@withContext null
            if (source.isRecycled) return@withContext null

            val layers = _textLayers.value
            if (layers.isEmpty()) {
                saveStagedTextLayers()
                return@withContext null
            }

            val baked = withContext(Dispatchers.Default) {
                bakeTextLayersOntoBitmap(source, layers, context)
            }
            val file = File(
                context.filesDir,
                "${projectId}_text_staging_${java.util.UUID.randomUUID()}.png"
            )
            file.outputStream().use { out ->
                baked.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val persisted = repository.getProjectById(projectId)
                ?: _projectState.value
                ?: return@withContext null
            val updated = persisted.copy(
                stagingImagePath = file.absolutePath,
                stagingTextLayers = null
            )
            _projectState.value = updated
            repository.saveProject(updated)
            _textLayers.value = emptyList()
            _selectedTextLayerId.value = null
            file.absolutePath
        }

    /**
     * Batch Print "Done": bake text into the current batch image, replace the
     * existing batch path, and update its existing History row in place.
     * With no layers this is a no-op, so an untouched batch photo is not
     * duplicated or lost.
     */
    suspend fun bakeTextForBatch(context: Context): String? =
        withContext(Dispatchers.IO) {
            try {
                val layers = _textLayers.value
                val currentPath = _printBatches.value.lastOrNull()?.imagePath
                    ?: _projectState.value?.savedImagePath
                    ?: return@withContext null

                if (layers.isEmpty()) {
                    clearTextLayers()
                    return@withContext currentPath
                }

                /*
                 * Prefer the batch's persisted path over the live Compose
                 * bitmap. The live bitmap can be replaced/recycled during the
                 * Text -> Layout navigation transition; decoding the already
                 * saved batch image gives this operation its own ownership.
                 */
                val decodedSource = decodeBitmapFromUriString(context, currentPath)
                val source = decodedSource
                    ?: _finalCroppedBitmap.value?.takeUnless { it.isRecycled }
                    ?: return@withContext currentPath

                val baked = withContext(Dispatchers.Default) {
                    bakeTextLayersOntoBitmap(source, layers, context)
                }
                // Only recycle the bitmap decoded by this operation. If the
                // persisted path was unavailable, source is the live bitmap
                // owned by the editor and must remain available to Compose.
                if (decodedSource != null && decodedSource !== baked && !decodedSource.isRecycled) {
                    decodedSource.recycle()
                }

                val file = java.io.File(
                    context.filesDir,
                    "${projectId}_batch_text_${java.util.UUID.randomUUID()}.png"
                )
                file.outputStream().use { out ->
                    check(baked.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        "Could not write batch text bitmap"
                    }
                }
                val bakedPath = file.absolutePath

                val targetId = currentBatchPhotoProjectId ?: _projectState.value?.id
                val existing = targetId?.let { repository.getProjectById(it) }
                if (existing != null) {
                    repository.saveProject(
                        existing.copy(
                            originalImagePath = bakedPath,
                            savedImagePath = bakedPath,
                            stagingTextLayers = null
                        )
                    )
                }

                _projectState.update { current ->
                    current?.copy(
                        savedImagePath = bakedPath,
                        stagingTextLayers = null
                    )
                }
                _printBatches.update { batches ->
                    batches.map { batch ->
                        if (batch.imagePath == currentPath) {
                            batch.copy(imagePath = bakedPath)
                        } else {
                            batch
                        }
                    }
                }
                clearTextLayers()
                bakedPath
            } catch (t: Throwable) {
                // A failed optional text bake must not crash the batch editor.
                // Keep the original path so the user can continue without
                // losing the current batch image.
                t.printStackTrace()
                _printBatches.value.lastOrNull()?.imagePath
                    ?: _projectState.value?.savedImagePath
            }
        }

    // =========================================================================
    // DISK CACHE HELPERS
    // =========================================================================

    /**
     * Compresses [bitmap] to lossless WebP and writes it to [filename] inside
     * the application cache directory.  Safe to call from any coroutine context.
     */
    suspend fun saveToCache(context: Context, bitmap: Bitmap, filename: String) =
        withContext(Dispatchers.IO) {
            try {
                val file = File(context.applicationContext.cacheDir, filename)
                file.outputStream().buffered().use { out ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, 100, out)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

         /**
     * Scales the current in-memory bitmap to match the requested DPI 
     * before navigating to the Print or Export screens.
     */
    suspend fun scaleBitmapToDpi(dpi: Int): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val currentBmp = _finalCroppedBitmap.value ?: return@withContext false
        if (currentBmp.isRecycled) return@withContext false

        // Get the physical width in mm (custom or preset)
        val physicalWidthMm = _customPrintWidthMm.value ?: _selectedPreset.value.widthMm

        // Calculate exact target pixels based on DPI
        val targetWidth = ((physicalWidthMm / 25.4f) * dpi).toInt().coerceAtLeast(1)
        val ratio = currentBmp.width.toFloat() / currentBmp.height.toFloat()
        val targetHeight = (targetWidth / ratio).toInt().coerceAtLeast(1)

        // If it already perfectly matches, skip scaling to save memory
        if (currentBmp.width == targetWidth && currentBmp.height == targetHeight) {
            return@withContext true
        }

        try {
            val scaledBmp = android.graphics.Bitmap.createScaledBitmap(currentBmp, targetWidth, targetHeight, true)

            // Safely update state on Main thread using our vault-aware function
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                setCroppedBitmap(scaledBmp)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Decodes [filename] from the application cache directory.
     * Returns **null** if the file does not exist or decoding fails.
     */
    suspend fun loadFromCache(context: Context, filename: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val file = File(context.applicationContext.cacheDir, filename)
            if (!file.exists()) null else runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        }

    // =========================================================================
    // BEAUTY ENGINE â€” SESSION START & LIVE PREVIEW
    // =========================================================================

    /**
     * Opens a beauty session. Call as soon as the user taps "Face Retouch".
     *
     * On the first call:
     * 1. Captures [originalBeautySource] â€” the full-resolution pristine copy.
     * 2. Creates [previewBitmap] â€” a downscaled copy (max [PREVIEW_MAX_DIM] px)
     *    that live-slider renders will target.
     * 3. Kicks off the ML face scan on [previewBitmap] so the resulting masks
     *    are already sized correctly for the preview render pipeline.
     *
     * Subsequent calls while the session is already live are no-ops.
     */
    fun startBeautySession(context: Context) {
        val currentBmp = _finalCroppedBitmap.value ?: return

        // Capture the full-res pristine source once per session.
        if (originalBeautySource == null || originalBeautySource?.isRecycled == true) {
            originalBeautySource = currentBmp.copy(currentBmp.config ?: Bitmap.Config.ARGB_8888, true)
        }

        // Build the downscaled preview bitmap once per session.
        if (previewBitmap == null || previewBitmap?.isRecycled == true) {
            previewBitmap = createPreviewBitmap(originalBeautySource!!)
        }

        // Scan the preview bitmap so mask arrays match its dimensions.
        if (cachedFaceData == null) {
            val scanTarget = previewBitmap!!
            faceScanJob = viewModelScope.launch(Dispatchers.Default) {
                // Guard: if the bitmap was recycled before the job started (e.g. rapid back
                // navigation), abort immediately instead of reading dead memory.
                if (scanTarget.isRecycled) return@launch
                _isScanningFace.value = true
                try {
                    val analyzer = FaceAnalysisRepository(context)
                    cachedFaceData = analyzer.analyse(scanTarget)
                    analyzer.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    _isScanningFace.value = false
                    faceScanJob = null
                }
            }
        }
    }

    /**
     * Returns a mutable downscaled copy of [src].  If [src] already fits
     * within [PREVIEW_MAX_DIM] on both axes the copy preserves the original
     * dimensions.
     */
    private fun createPreviewBitmap(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= PREVIEW_MAX_DIM && h <= PREVIEW_MAX_DIM) {
            return src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
        }
        val scale  = minOf(PREVIEW_MAX_DIM.toFloat() / w, PREVIEW_MAX_DIM.toFloat() / h)
        val sw     = (w * scale).toInt().coerceAtLeast(1)
        val sh     = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, sw, sh, true)
    }

    /**
     * Call whenever any face-retouch slider moves.
     *
     * Records the latest slider values (so [bakeFullResBeauty] can reproduce
     * the exact look at full resolution) and schedules a new preview render on
     * [previewBitmap].  If the face scan has not finished yet the call is
     * silently ignored â€” the render will begin as soon as [cachedFaceData] is
     * populated.
     */
    fun updateBeautyLive(
        skinBrightness: Float,
        blemishReduction: Float,
        underEyeReduction: Float,
        eyeBrightening: Float,
        teethWhitening: Float,
        faceSharpening: Float,
        eyebrowDefinition: Float
    ) {
        lastSkinBrightness    = skinBrightness
        lastBlemishReduction  = blemishReduction
        lastUnderEyeReduction = underEyeReduction
        lastEyeBrightening    = eyeBrightening
        lastTeethWhitening    = teethWhitening
        lastFaceSharpening    = faceSharpening
        lastEyebrowDefinition = eyebrowDefinition

        if (cachedFaceData == null) return

        renderBeautyPreview(
            skinBrightness, blemishReduction, underEyeReduction,
            eyeBrightening, teethWhitening, faceSharpening, eyebrowDefinition
        )
    }

    fun clearProcessedCutout() {
        _processedCutoutBitmap.value = null
        _pristineBackgroundBitmap.value = null // Wipe the vault too!
    }


    /**
     * Runs the C++ engine on [previewBitmap] and pushes the result to
     * [beautyPreviewBitmap].  Cancels any in-flight render before launching a
     * new one, and discards stale results via [renderCounter].
     *
     * [_finalCroppedBitmap] is never touched during live preview â€” it retains
     * the full-res value until the user confirms with "Next".
     */
    private fun renderBeautyPreview(
        skinBrightness: Float,
        blemishReduction: Float,
        underEyeReduction: Float,
        eyeBrightening: Float,
        teethWhitening: Float,
        faceSharpening: Float,
        eyebrowDefinition: Float
    ) {
        val sourceBmp = previewBitmap ?: return
        val faceData  = cachedFaceData ?: return

        beautyRenderJob?.cancel()
        val currentRequestId = ++renderCounter

        beautyRenderJob = viewModelScope.launch(Dispatchers.Default) {
            // The mutex serialises all render passes. Because NativeBeautyEngine.processImage is a
            // blocking JNI call that cannot be interrupted by coroutine cancellation, without this
            // lock multiple coroutines can run the native call concurrently â€” causing concurrent
            // getPixels reads on the same bitmap and a double-recycle race on the old preview.
            renderMutex.withLock {
                // Drop requests that were superseded while waiting to acquire the lock.
                if (currentRequestId != renderCounter) return@withLock
                // Guard: abort if the source bitmap was recycled (e.g. back navigation).
                if (sourceBmp.isRecycled) return@withLock

                val width       = sourceBmp.width
                val height      = sourceBmp.height
                val totalPixels = width * height

                val safeSkin    = skinBrightness.coerceIn(0f, 0.5f)

                val inputPixels = IntArray(totalPixels)
                sourceBmp.getPixels(inputPixels, 0, width, 0, 0, width, height)

                val outputPixels = NativeBeautyEngine.processImage(
                    pixels                  = inputPixels,
                    width                   = width,
                    height                  = height,
                    refinedMask             = faceData.refinedMask,
                    sharpMask               = faceData.sharpMask,
                    blemishMask             = faceData.blemishMask,
                    brightnessMask          = faceData.brightnessMask,
                    eyebrowMask             = faceData.eyebrowMask,
                    eyesMask                = faceData.eyesMask,
                    eyeBagsMask             = faceData.eyeBagsMask,
                    irisMask                = faceData.irisMask,
                    teethMask               = faceData.teethMask,
                    blemishStrength         = blemishReduction,
                    sharpenStrength         = faceSharpening,
                    eyebrowStrength         = eyebrowDefinition,
                    skinBrightnessStrength  = safeSkin,
                    underEyeStrength        = underEyeReduction,
                    eyeBrightnessStrength   = eyeBrightening,
                    teethWhiteningStrength  = teethWhitening
                )

                // Drop stale results superseded while the blocking JNI call was running.
                if (currentRequestId != renderCounter) return@withLock

                if (outputPixels.size == totalPixels) {
                    val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    outputBitmap.setPixels(outputPixels, 0, width, 0, 0, width, height)

                    val oldPreview = _beautyPreviewBitmap.value
                    _beautyPreviewBitmap.value = outputBitmap

                    // DEFERRED recycle â€” do NOT call oldPreview.recycle() here.
                    //
                    // Setting the StateFlow value schedules a Compose recomposition
                    // but does NOT wait for the current GPU frame to finish.  The
                    // Skia render thread is still rasterising that frame using
                    // oldPreview's pixel buffer.  Recycling immediately unmaps the
                    // buffer while the GPU is reading it â†’ "Canvas: trying to use a
                    // recycled bitmap" crash / native SIGSEGV in the compositor.
                    //
                    // We post the recycle to the main thread with a short delay
                    // (~2 frames at 60 Hz) so the GPU has committed the new frame
                    // before the old bitmap's memory is released.  This is the same
                    // pattern used in restoreRetouchCheckpoint.
                    if (oldPreview != null && oldPreview !== previewBitmap) {
                        viewModelScope.launch(Dispatchers.Main) {
                            delay(64L)
                            if (!oldPreview.isRecycled) oldPreview.recycle()
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // FULL-RES BAKE â€” called from RetouchScreen on "Next"
    // =========================================================================

    /**
     * Returns **true** when a face-retouch beauty session is currently active.
     * RetouchScreen uses this to decide whether to call [bakeFullResBeauty].
     */
    fun hasActiveBeautySession(): Boolean =
        originalBeautySource?.isRecycled == false

    /**
     * Runs the C++ beauty engine **once** on the full-resolution
     * [originalBeautySource] using the slider values that were active when the
     * user tapped "Next".
     *
     * Steps performed:
     * 1. Saves [originalBeautySource] as [STEP1_FILENAME] (the pristine crop
     *    that "Back" from BackgroundScreen will reload).
     * 2. Runs a fresh face scan at full resolution so masks match the source
     *    pixel dimensions exactly.
     * 3. Executes the C++ beauty pass â†’ produces the full-res baked bitmap.
     * 4. Saves the beauty-only bake as [STEP2_FILENAME].
     * 5. Returns the baked bitmap to the caller.
     *
     * The caller (RetouchScreen) is responsible for applying any colour-matrix
     * adjustments on top of the returned bitmap, then calling [setCroppedBitmap]
     * and [clearBeautySession].
     *
     * Returns **null** if no beauty session is active or if an error occurs;
     * the caller should fall back to [finalCroppedBitmap] in that case.
     */
    suspend fun bakeFullResBeauty(context: Context): Bitmap? {
        val fullResSrc = originalBeautySource ?: return null
        if (fullResSrc.isRecycled) return null

        // 1. Persist the pristine crop before any irreversible operation.
        saveToCache(context, fullResSrc, STEP1_FILENAME)

        return withContext(Dispatchers.Default) {
            try {
                // 2. Full-resolution face scan â€” masks must match source dimensions.
                val analyzer      = FaceAnalysisRepository(context)
                val fullResFaceData: FaceData? = analyzer.analyse(fullResSrc)
                analyzer.close()

                fullResFaceData ?: return@withContext null

                // 3. C++ beauty pass at full resolution.
                val width       = fullResSrc.width
                val height      = fullResSrc.height
                val totalPixels = width * height

                val safeSkin    = lastSkinBrightness.coerceIn(0f, 0.5f)
                val inputPixels = IntArray(totalPixels)
                fullResSrc.getPixels(inputPixels, 0, width, 0, 0, width, height)

                val outputPixels = NativeBeautyEngine.processImage(
                    pixels                  = inputPixels,
                    width                   = width,
                    height                  = height,
                    refinedMask             = fullResFaceData.refinedMask,
                    sharpMask               = fullResFaceData.sharpMask,
                    blemishMask             = fullResFaceData.blemishMask,
                    brightnessMask          = fullResFaceData.brightnessMask,
                    eyebrowMask             = fullResFaceData.eyebrowMask,
                    eyesMask                = fullResFaceData.eyesMask,
                    eyeBagsMask             = fullResFaceData.eyeBagsMask,
                    irisMask                = fullResFaceData.irisMask,
                    teethMask               = fullResFaceData.teethMask,
                    blemishStrength         = lastBlemishReduction,
                    sharpenStrength         = lastFaceSharpening,
                    eyebrowStrength         = lastEyebrowDefinition,
                    skinBrightnessStrength  = safeSkin,
                    underEyeStrength        = lastUnderEyeReduction,
                    eyeBrightnessStrength   = lastEyeBrightening,
                    teethWhiteningStrength  = lastTeethWhitening
                )

                if (outputPixels.size != totalPixels) return@withContext null

                val bakedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bakedBitmap.setPixels(outputPixels, 0, width, 0, 0, width, height)

                // 4. Cache the beauty-only bake for reference / forward navigation.
                saveToCache(context, bakedBitmap, STEP2_FILENAME)

                bakedBitmap

            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    // =========================================================================
    // BEAUTY SESSION â€” COMMIT / CANCEL / CLEAR
    // =========================================================================

    /**
     * Frees all beauty session resources.  Call AFTER [setCroppedBitmap] has
     * been given the final baked result so the ViewModel does not hold onto
     * large bitmaps unnecessarily.
     */
    fun clearBeautySession() {
        beautyRenderJob?.cancel()

        val oldPreview = _beautyPreviewBitmap.value
        _beautyPreviewBitmap.value = null
        if (oldPreview != null && oldPreview !== previewBitmap) {
            oldPreview.recycle()
        }

        previewBitmap?.recycle()
        previewBitmap = null

        originalBeautySource?.recycle()
        originalBeautySource = null

        cachedFaceData = null
    }

    /**
     * Accepts the current live-preview result as the permanent image.
     * Equivalent to [clearBeautySession] â€” the caller must already have set
     * [_finalCroppedBitmap] to the desired output before calling this.
     */
    fun commitBeautySession() {
        clearBeautySession()
    }

    /**
     * User tapped "Cancel" on the beauty sheet.
     * Restores [originalBeautySource] to [_finalCroppedBitmap] and releases
     * all session resources.
     *
     * **Safe deferred recycling:** This function MUST NOT recycle [previewBitmap]
     * or [_beautyPreviewBitmap] synchronously. The C++ JNI call inside
     * [renderBeautyPreview] cannot be interrupted by coroutine cancellation, so
     * a racing render coroutine may still hold a reference to those bitmaps.
     * Instead we:
     *  1. Snapshot [faceScanJob] before any async work.
     *  2. Restore the pristine crop to the UI immediately (safe â€” reads
     *     [originalBeautySource], not [previewBitmap], so no race with JNI).
     *  3. Launch an IO coroutine that waits for the scan to stop
     *     ([cancelAndJoin]) then acquires [renderMutex] to guarantee the JNI has
     *     finished, and only then recycles the bitmaps.
     */
    fun cancelBeautySession() {
        beautyRenderJob?.cancel()
        val currentScanJob = faceScanJob
        faceScanJob = null

        // Capture and clear the pristine source immediately so it can be
        // restored to the UI without waiting for the background cleanup.
        val pristine = originalBeautySource
        originalBeautySource = null
        cachedFaceData = null

        // Restore the UI to the un-retouched image right away.  This reads
        // from pristine (originalBeautySource), which is not touched by the
        // JNI render, so no race condition here.
        if (pristine != null && !pristine.isRecycled) {
            val old = _finalCroppedBitmap.value
            _finalCroppedBitmap.value = pristine.copy(pristine.config ?: Bitmap.Config.ARGB_8888, true)
            if (old != null && old !== pristine) old.recycle()
        }

        // Snapshot previewBitmap and clear the pointer immediately so that
        // any subsequent startBeautySession call gets a fresh bitmap.
        val snapshotPreview = previewBitmap
        previewBitmap = null

        viewModelScope.launch(Dispatchers.IO) {
            // 1. Force cleanup to WAIT for the face scan to fully stop before
            //    it can call getPixels on previewBitmap.
            currentScanJob?.cancelAndJoin()

            // 2. Force cleanup to WAIT for any active C++ JNI render to finish.
            //    Only inside this lock is it safe to recycle â€” no native or ML
            //    thread is reading any of these bitmaps at this point.
            renderMutex.withLock {
                pristine?.let { if (!it.isRecycled) it.recycle() }
                snapshotPreview?.let { if (!it.isRecycled) it.recycle() }
                _beautyPreviewBitmap.value?.let { if (!it.isRecycled) it.recycle() }
                _beautyPreviewBitmap.value = null
            }
        }
    }

    // =========================================================================
    // BACK NAVIGATION â€” Background â†’ Retouch
    // =========================================================================

    /**
     * Reloads [STEP1_FILENAME] from disk and resets the ViewModel to the
     * pre-beauty state so RetouchScreen opens on a clean slate with no
     * double-baking risk.
     *
     * **State-first swapping:** [_finalCroppedBitmap] and
     * [_beautyPreviewBitmap] are updated *before* any bitmap is recycled.
     * This guarantees that Compose recompositions triggered by the state
     * change will draw the restored bitmap, not the one about to be freed.
     *
     * **Delayed recycle:** A 500 ms delay lets the Compose exit animation for
     * BackgroundScreen finish before [Bitmap.recycle] is called on the old
     * bitmaps.  Without this guard the fading BackgroundScreen can attempt to
     * draw a recycled canvas and crash with
     * "Canvas: trying to use a recycled bitmap".
     *
     * [cachedFaceData] is cleared so the user can re-trigger a fresh face scan
     * on the restored pristine image.
     */
     fun restoreRetouchCheckpoint(context: Context) {
        // 🔥 1. SYNCHRONOUS WIPE BEFORE SUSPENDING
        // This cuts the UI's access to stale images immediately, completely preventing 
        // the RetouchScreen from grabbing the blue background during navigation.
        val orphanedCutout  = _processedCutoutBitmap.value
        val orphanedVault   = _pristineBackgroundBitmap.value
        val orphanedSource  = originalBeautySource
        val orphanedPrev    = previewBitmap
        val orphanedPreviewBmp = _beautyPreviewBitmap.value
        val orphanedFinal   = _finalCroppedBitmap.value

        _processedCutoutBitmap.value    = null
        _pristineBackgroundBitmap.value = null
        originalBeautySource            = null
        previewBitmap                   = null
        cachedFaceData                  = null
        _beautyPreviewBitmap.value      = null

        // Force RetouchScreen to wait for the disk read
        _finalCroppedBitmap.value       = null

        beautyRenderJob?.cancel()

        // NOW it is safe to suspend and read the backup from disk
        viewModelScope.launch {
            val restored = loadFromCache(context, STEP1_FILENAME)

            if (restored != null) {
                // The UI will now safely lock onto the pristine backup
                _finalCroppedBitmap.value = restored
            } else {
                // Failsafe: if no backup existed, restore the original image
                _finalCroppedBitmap.value = orphanedFinal
            }

            // Delayed memory release to prevent exit-animation crashes
            launch {
                delay(500L)
                if (restored != null && orphanedFinal != null && orphanedFinal !== restored) {
                    orphanedFinal.recycle()
                }
                orphanedPreviewBmp?.recycle()
                orphanedSource?.recycle()
                orphanedPrev?.recycle()
                orphanedCutout?.let { if (!it.isRecycled) it.recycle() }
                orphanedVault?.let { if (!it.isRecycled) it.recycle() } 
            }
        }
    }

    // =========================================================================
    // PRESET / CROP / ROTATION HELPERS
    // =========================================================================

    fun updateSizePreset(preset: PassportSizePreset) {
        _selectedPreset.value = preset
        val dynamicRatio = CropRatio(
            name        = preset.name,
            widthRatio  = preset.widthMm,
            heightRatio = preset.heightMm
        )
        updateCropRatio(dynamicRatio)
    }

    fun updateCropRatio(newRatio: CropRatio) {
        // In-memory only â€” no DB write here. The project is persisted by
        // updateProjectType() when the user actually finishes an export.
        _projectState.update { current ->
            current?.copy(cropRatio = newRatio)
        }
    }

    fun updateRotation(degrees: Float) {
        // In-memory only â€” same deferred-save policy as updateCropRatio.
        _projectState.update { current ->
            current?.copy(rotationDegrees = degrees)
        }
    }

    fun toggleNextGrid() {
        val values    = GridType.values()
        val nextIndex = (_currentGrid.value.ordinal + 1) % values.size
        _currentGrid.value = values[nextIndex]
    }

    fun updateCropBox(cropBox: CropBox) {
        _currentCropBox.value = cropBox
    }

    fun setCroppedBitmap(bitmap: Bitmap) {
        val oldBitmap = _finalCroppedBitmap.value
        // PROTECT THE VAULT: Do not recycle the old image if the vault is holding it.
        // This keeps the original image alive in memory so the Repair brush can use it!
        if (oldBitmap !== bitmap &&
            oldBitmap !== _pristineBackgroundBitmap.value &&
            oldBitmap != null &&
            !oldBitmap.isRecycled
        ) {
            oldBitmap?.recycle()
        }
        _finalCroppedBitmap.value = bitmap
    }

    /**
     * Marks the project as completed with [type] and optionally records the
     * URI of the image that was saved to the gallery.  Persists the updated
     * [ProjectState] via the repository so HomeScreen "Recent Projects" reflects
     * the correct type badge immediately.
     *
     * Called:
     *  - by [OutputSelectionScreen] after a successful digital-copy save   â†’ DIGITAL
     *  - by [PhotoSizeAdjustmentScreen] on its auto-save LaunchedEffect     â†’ PRINT
     */
    fun updateProjectType(type: ProjectType, savedPath: String? = null) {
        viewModelScope.launch {
            val current = _projectState.value ?: return@launch
            // Preserve an already-set savedImagePath when no explicit path is supplied.
            // Without this guard, calling updateProjectType(PRINT) after
            // saveEditedBitmapToPrivateFile would null-out the path it just wrote,
            // restoring the original gallery URI (or null) in the DB.
            val updated = current.copy(
                projectType   = type,
                savedImagePath = savedPath ?: current.savedImagePath
            )
            _projectState.value = updated
            repository.saveProject(updated)
        }
    }

    fun saveCurrentState() {
        _projectState.value?.let { saveProjectState(it) }
    }

    // =========================================================================
    // BATCH / PRINT HELPERS
    // =========================================================================

    fun setBatchMode(paperSizeId: String) {
        _batchPaperSizeId.value = paperSizeId
        _isBatchMode.value      = true
        if (currentBatchGroupId == null) {
            currentBatchGroupId = java.util.UUID.randomUUID().toString()
            // Retroactively publish the anchor (first) photo the moment this
            // actually becomes a batch. It was deliberately left unpublished
            // (UNKNOWN) while it was still just a single image, to avoid the
            // double-card bug — but once a 2nd photo joins, the original
            // "every batch photo is visible in History as it's added" behavior
            // needs to apply to the anchor too, not just batchGroupId tagging.
            val anchor = _projectState.value
            if (anchor != null) {
                val updated = anchor.copy(
                    batchGroupId = currentBatchGroupId,
                    projectType  = ProjectType.PRINT
                )
                _projectState.value = updated
                viewModelScope.launch { repository.saveProject(updated) }
            }
        }
    }

    /**
     * Signals LayoutConfig that the current image has been fully edited and is
     * ready to be appended.  Called by the NavHost immediately before popping
     * back to [PhotoSizeAdjustmentScreen] â€” never from inside the screen itself.
     */
    fun commitBatchImage(imagePath: String) {
    _batchEditSuccessEvent.tryEmit(imagePath)
    }

    fun ensureCurrentBitmapInBatches(imagePath: String, defaultWidth: Float, defaultHeight: Float, ratio: Float) {
        val validBatches = _printBatches.value.toMutableList()
        if (validBatches.none { it.imagePath == imagePath }) {
            val newBatch = PrintBatch(
                imagePath = imagePath, widthMm = defaultWidth, heightMm = defaultHeight,
                copies = 1, gapMm = 2f, drawGuides = true, aspectRatio = ratio
            )
            if (!_isBatchMode.value) validBatches.clear()
            validBatches.add(newBatch)
            _printBatches.value = validBatches
        } else {
            _printBatches.value = validBatches
        }
    }

    fun updateBatch(index: Int, batch: PrintBatch) {
        if (index in _printBatches.value.indices) {
            val list = _printBatches.value.toMutableList()
            list[index] = batch
            _printBatches.value = list
        }
    }

    fun removeBatch(index: Int) {
        if (index in _printBatches.value.indices) {
            val list = _printBatches.value.toMutableList()
            list.removeAt(index)
            _printBatches.value = list
        }
    }

    private fun saveProjectState(state: ProjectState) {
        viewModelScope.launch {
            repository.saveProject(state)
        }
    }

    fun resetForNewBatchImage(uri: String) {
        clearTextLayers()
        _finalCroppedBitmap.value = null
        _processedCutoutBitmap.value = null
        _pristineBackgroundBitmap.value = null
        _beautyPreviewBitmap.value = null
        originalBeautySource = null
        previewBitmap = null
        cachedFaceData = null
        currentBatchPhotoProjectId = null

        val currentProj = _projectState.value
        val isSubsequentBatchPhoto = _isBatchMode.value && _printBatches.value.isNotEmpty()

        _projectState.value = when {
            isSubsequentBatchPhoto -> ProjectState(
                originalImagePath = uri,
                projectType = ProjectType.UNKNOWN,
                batchGroupId = currentBatchGroupId
            )
            currentProj != null -> currentProj.copy(
                originalImagePath = uri,
                savedImagePath = null
            )
            else -> ProjectState(
                id = projectId,
                originalImagePath = uri,
                projectType = ProjectType.UNKNOWN
            )
        }
    }

    fun clearBatches() {
        _printBatches.value = emptyList()
        _batchPaperSizeId.value = null
        _isBatchMode.value = false
    }

    // =========================================================================
    // STANDALONE EDIT HELPERS (Requirement 1)
    // =========================================================================

    /**
     * Clears the current in-progress image state without touching [printBatches].
     * Called by the batch-mode escape hatch (Cancel 'X') so the user returns to
     * LayoutConfig with all previously-confirmed images still intact.
     */
       fun clearCurrentSession() {
        clearTextLayers()
        beautyRenderJob?.cancel()
        faceScanJob?.cancel()

        val oldPreview = _beautyPreviewBitmap.value
        _beautyPreviewBitmap.value = null
        viewModelScope.launch(Dispatchers.Main) {
            delay(100L)
            if (oldPreview != null && oldPreview !== previewBitmap && !oldPreview.isRecycled) {
                oldPreview.recycle()
            }
        }
        previewBitmap?.recycle()
        previewBitmap        = null
        originalBeautySource?.recycle()
        originalBeautySource = null
        cachedFaceData       = null

        // Grab and clear the vault
        val vaultBmp = _pristineBackgroundBitmap.value
        _pristineBackgroundBitmap.value = null

        // CHANGED: PrintBatch no longer holds a Bitmap, so "was this in-progress
        // bitmap accidentally added to the batch list" can't be checked by
        // reference anymore. Use the project's saved path instead — the same
        // identity concept, just expressed the way PrintBatch now stores it.
        val inProgressPath = _projectState.value?.savedImagePath
        if (inProgressPath != null) {
            _printBatches.value = _printBatches.value.filterNot { it.imagePath == inProgressPath }
        }

        val inProgressBmp = _finalCroppedBitmap.value
        if (inProgressBmp != null && !inProgressBmp.isRecycled) {
            inProgressBmp.recycle()
        }

        // Recycle the vault image securely
        vaultBmp?.let { if (!it.isRecycled) it.recycle() }

        _finalCroppedBitmap.value    = null
        _currentCropBox.value        = null
        _activeTool.value            = ActiveTool.NONE
        _isBatchMode.value           = false
        _processedCutoutBitmap.value = null
    }


    /**
     * Saves [finalCroppedBitmap] as a JPEG in the app's private files directory
     * and updates [ProjectState.savedImagePath] in the repository so that
     * [ProjectDetailsScreen] immediately reflects the new thumbnail when the
     * user pops back to it.
     *
     * Runs on [Dispatchers.IO].  Call inside a coroutine (all editor "Done" /
     * "Next" handlers are already coroutine-launched).
     */
    /**
     * Decodes and loads the project's persisted image (saved edit or original)
     * into [finalCroppedBitmap] so that Retouch and Background screens have
     * real content when launched as standalone edits from "My Studio" â€” i.e.
     * without the user having gone through the Crop screen first.
     *
     * Safe to call multiple times: returns immediately if a non-recycled bitmap
     * is already loaded.  Suspends until [projectState] is populated if the
     * initial DB load has not completed yet.
     */
    suspend fun loadImageForStandaloneEdit(
        context: android.content.Context,
        forceReload: Boolean = false
    ) =
        withContext(Dispatchers.IO) {
            // Don't overwrite an active in-progress edit
            val existing = _finalCroppedBitmap.value
            if (!forceReload && existing != null && !existing.isRecycled) {
                return@withContext
            }

            // Wait for the project state if still loading asynchronously
            val state = _projectState.value
                ?: _projectState.filterNotNull().first()
            val imagePath = state.stagingImagePath ?: state.savedImagePath ?: state.originalImagePath ?: return@withContext
            val bmp = decodeBitmapFromUriString(context, imagePath) ?: return@withContext
            withContext(Dispatchers.Main) {
                setCroppedBitmap(bmp)
                // Fix 1: the loaded image already has the previous rotation baked into its
                // pixels â€” reset the in-memory rotation to 0Â° so the crop slider starts
                // fresh and never double-applies the old value.
                _projectState.update { current -> current?.copy(rotationDegrees = 0f) }
            }
        }

    /**
     * Fix 2: Runs the ENTIRE digital-copy save pipeline on [Dispatchers.IO] so
     * the Main thread is never blocked.  Steps in order, all inside the IO block:
     *   1. Export to the user's gallery via [ImageExporter].
     *   2. Compress + write the private "thumbnail" file.
     *   3. Persist the updated [ProjectState] (type=DIGITAL, savedImagePath) to Room.
     * Switches back to [Dispatchers.Main] to clear [isSaving] and invoke the
     * appropriate callback â€” the caller's navigation code always runs on Main.
     */
    fun saveDigitalCopy(
        context: android.content.Context,
        bitmap: Bitmap,
        format: ImageExporter.ExportFormat,
        sizeLimit: Int?,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        _isSaving.value = true
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    // Text remains editable metadata until the terminal output
                    // action. Flatten it here as a final safety net, including
                    // the first image in the standard pipeline.
                    val state = _projectState.value
                    val layers = state?.stagingTextLayers ?: _textLayers.value
                    val exportBitmap = if (layers.isEmpty()) {
                        bitmap
                    } else {
                        withContext(Dispatchers.Default) {
                            bakeTextLayersOntoBitmap(bitmap, layers, context)
                        }
                    }
                    val exported = ImageExporter.saveDigitalPhotoToGallery(
                        context, exportBitmap, format, sizeLimit
                    )
                    if (!exported) return@withContext false

                    // CHANGED: unique filename + a brand-new ProjectState row per
                    // export, instead of overwriting this project's existing card.
                    // Every export is now its own History entry — the underlying
                    // project's own row (and its stagingImagePath) is left alone,
                    // so further standalone edits keep chaining off the right state.
                    val file = java.io.File(context.filesDir, "${projectId}_export_${java.util.UUID.randomUUID()}.png")
                    file.outputStream().use { out ->
                        exportBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    val path = file.absolutePath

                    // A digital export gets its own History/My Studio card. Keep
                    // the ratio selected in the editor when creating that card;
                    // omitting it makes Gson restore ProjectState's 35x45 default.
                    val sourceState = _projectState.value
                    val newCard = ProjectState(
                        originalImagePath = path,
                        savedImagePath    = path,
                        cropRatio         = sourceState?.cropRatio ?: CropRatio.RATIO_35_45,
                        projectType       = ProjectType.DIGITAL
                    )
                    repository.saveProject(newCard)

                    // CHANGED: the staged edit that fed this export is now published as
                    // its own card — clear it from the original project so re-opening
                    // that project stops showing this now-finished edit as if it were
                    // still in progress.
                    val anchor = _projectState.value
                    if (anchor != null && (
                            anchor.stagingImagePath != null ||
                            anchor.stagingTextLayers != null
                        )
                    ) {
                        // _projectState carries the staging ratio while the
                        // standalone export is being prepared. Read the
                        // persisted anchor before clearing staging so that the
                        // exported card gets the new ratio, but the original
                        // My Studio card keeps its published ratio.
                        val persistedAnchor = repository.getProjectById(anchor.id) ?: anchor
                        val clearedAnchor = persistedAnchor.copy(
                            stagingImagePath = null,
                            stagingCropRatio = null,
                            stagingTextLayers = null
                        )
                        _projectState.value = clearedAnchor
                        repository.saveProject(clearedAnchor)
                    }

                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
            withContext(Dispatchers.Main) {
                _isSaving.value = false
                if (success) onSuccess() else onFailure()
            }
        }
    }

    /**
     * Resolves the image used by the terminal output screens. Any staged text
     * metadata is baked once here, producing a file path that is safe to use
     * for both Digital export and PrintBatch.imagePath.
     */
    suspend fun resolvePrintReadyImagePath(context: Context): String? =
        withContext(Dispatchers.IO) {
            val bitmap = _finalCroppedBitmap.value
                ?: return@withContext null
            if (bitmap.isRecycled) return@withContext null

            val state = _projectState.value ?: _projectState.filterNotNull().first()
            val layers = state.stagingTextLayers ?: emptyList()
            if (layers.isEmpty()) {
                return@withContext state.stagingImagePath
                    ?: state.savedImagePath
                    ?: state.originalImagePath
            }

            val baked = withContext(Dispatchers.Default) {
                bakeTextLayersOntoBitmap(bitmap, layers, context)
            }

            val file = java.io.File(
                context.filesDir,
                "${projectId}_texted_${java.util.UUID.randomUUID()}.png"
            )
            file.outputStream().use { out ->
                baked.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        }

    suspend fun saveEditedBitmapToPrivateFile(
    context: android.content.Context,
    bitmapOverride: Bitmap? = null
): String? =
    withContext(Dispatchers.IO) {
        val bmp = bitmapOverride ?: _finalCroppedBitmap.value
            ?: return@withContext null

        if (bmp.isRecycled) return@withContext null

        try {
            val file = java.io.File(
                context.filesDir,
                "${projectId}_edited_${java.util.UUID.randomUUID()}.png"
            )

            file.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val path = file.absolutePath
            val isFirstBatchImage = _printBatches.value.isEmpty()

            if (!_isBatchMode.value || isFirstBatchImage) {
                val current = _projectState.value
                    ?: return@withContext path

                val updated = current.copy(
                    savedImagePath = path,
                    projectType = if (_isBatchMode.value) ProjectType.PRINT else current.projectType,
                    batchGroupId = if (_isBatchMode.value) currentBatchGroupId else null
                )

                _projectState.value = updated
                repository.saveProject(updated)
            } else {
                // RESTORED: every batch photo after the first needs its OWN card.
                // This branch was missing entirely — meaning photo 2+ in any batch
                // wrote its PNG to disk but never created a ProjectState row at all.
                val existingId = currentBatchPhotoProjectId
                val existing = existingId?.let { repository.getProjectById(it) }
                if (existing != null) {
                    // Second+ save for the SAME photo within one session (e.g. re-baking) —
                    // update in place rather than minting a duplicate.
                    repository.saveProject(existing.copy(savedImagePath = path, projectType = ProjectType.PRINT))
                } else {
                    val newCard = ProjectState(
                        originalImagePath = path,
                        savedImagePath    = path,
                        cropRatio        = _projectState.value?.cropRatio ?: CropRatio.RATIO_35_45,
                        projectType       = ProjectType.PRINT,
                        batchGroupId      = currentBatchGroupId
                    )
                    currentBatchPhotoProjectId = newCard.id
                    repository.saveProject(newCard)
                }
            }

            path
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    /**
     * Called by PrintPreviewScreen after a successful (non-batch) sheet save.
     * Publishes this project to History as PRINT — the deferred counterpart
     * to saveDigitalCopy, so a single-image edit only ever produces one card
     * for whichever method (Digital or Print) the user actually completed.
     * No-op if already tagged (e.g. batch photos, already PRINT from above).
     */
    fun publishPrintExport() {
        viewModelScope.launch {
            val current = _projectState.value ?: return@launch
            if (current.projectType == ProjectType.UNKNOWN) {
                val updated = current.copy(projectType = ProjectType.PRINT)
                _projectState.value = updated
                repository.saveProject(updated)
            }
        }
    }

    /**
     * Standalone-tool "Next" action. Persists the edit so the NEXT standalone
     * tool session can pick it up, without publishing it to History — that
     * only happens via explicit Export (saveDigitalCopy) or Print.
     */
    suspend fun saveStagingBitmapToPrivateFile(
        context: android.content.Context,
        bitmapOverride: Bitmap? = null
    ): String? =
        withContext(Dispatchers.IO) {
            val bmp = bitmapOverride ?: _finalCroppedBitmap.value ?: return@withContext null
            if (bmp.isRecycled) return@withContext null
            try {
                val file = java.io.File(context.filesDir, "${projectId}_staging_${java.util.UUID.randomUUID()}.png")
                file.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                val path = file.absolutePath

                // CHANGED: base the persisted write on the last PERSISTED project
                // state from the repository, not the live in-memory _projectState.
                // The in-memory copy can carry transient editor-only mutations
                // (cropRatio from updateCropRatio(), rotationDegrees from
                // updateRotation()) that were only ever meant to stay ephemeral
                // until a real export. Persisting them here made a discarded
                // standalone crop's ratio stick permanently — discard only ever
                // cleared stagingImagePath, with no "previous ratio" to restore,
                // because the wrong ratio had already overwritten it on disk.
                val persisted = repository.getProjectById(projectId) ?: _projectState.value ?: return@withContext path
                val updated = persisted.copy(
                    stagingImagePath = path,
                    stagingCropRatio = _projectState.value?.cropRatio ?: persisted.stagingCropRatio
                )
                _projectState.value = updated
                repository.saveProject(updated)
                path
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    /** Called when the user chooses "Save & Exit" on the pause-confirm dialog. */
    fun pauseBatchSession(context: Context, paperSizeId: String, printProjectRepository: PrintProjectRepository) {
        val groupId = currentBatchGroupId ?: return
        if (_printBatches.value.isEmpty()) return
        viewModelScope.launch {
            printProjectRepository.saveDraft(
                PrintProjectDraft(id = groupId, paperSizeId = paperSizeId, batches = _printBatches.value)
            )
        }
    }

    /** Called when a draft tile is tapped from Home to resume editing. */
    fun resumeBatchSession(draft: PrintProjectDraft) {
        // Idempotent — re-navigating back to Layout Config after adding another
        // photo recreates the composable and re-fires this call with the stale
        // on-disk draft. Without this guard it wipes _printBatches back to the
        // old list, silently discarding the photo you just added.
        if (currentBatchGroupId == draft.id) return
        currentBatchGroupId = draft.id
        _batchPaperSizeId.value = draft.paperSizeId
        _isBatchMode.value = true
        _printBatches.value = draft.batches
    }

    /** Called on successful export — batch is resolved, its draft (if any) is stale. */
    fun clearBatches(printProjectRepository: PrintProjectRepository? = null) {
        val groupId = currentBatchGroupId
        if (groupId != null && printProjectRepository != null) {
            viewModelScope.launch { printProjectRepository.deleteDraft(groupId) }
        }
        _printBatches.value = emptyList()
        _batchPaperSizeId.value = null
        _isBatchMode.value = false
        currentBatchGroupId = null
        currentBatchPhotoProjectId = null   // NEW
    }

    // =========================================================================
    // BATCH ADD FROM STUDIO (Requirement 3)
    // =========================================================================

    /**
     * Decodes the image at [imageUri] (content URI or absolute file path) and
     * appends it directly to [printBatches], skipping the edit pipeline.
     * Dimensions default to the currently selected [PassportSizePreset].
     *
     * Called from LayoutConfig's "Choose from Studio" picker.
     */
    fun addBitmapFromUri(context: android.content.Context, imageUri: String) {
        val preset = _selectedPreset.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (imageUri.startsWith("content://") || imageUri.startsWith("file://")) {
                    context.contentResolver.openInputStream(android.net.Uri.parse(imageUri))
                        ?.use { BitmapFactory.decodeStream(it, null, opts) }
                } else {
                    BitmapFactory.decodeFile(imageUri, opts)
                }
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return@launch
                val ratio = opts.outWidth.toFloat() / opts.outHeight.toFloat()

                val batch = PrintBatch(
                    imagePath = imageUri, widthMm = preset.widthMm, heightMm = preset.heightMm,
                    copies = 1, gapMm = 2f, drawGuides = true, aspectRatio = ratio
                )
                withContext(Dispatchers.Main) { _printBatches.value = _printBatches.value + batch }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    /** Decodes a bitmap from a content URI string or an absolute file path. */
    private fun decodeBitmapFromUriString(
        context: android.content.Context,
        uriString: String
    ): Bitmap? = try {
        if (uriString.startsWith("content://") || uriString.startsWith("file://")) {
            val uri = android.net.Uri.parse(uriString)
            // minSdk = 28 = P, so ImageDecoder is always available.
            android.graphics.ImageDecoder.decodeBitmap(
                android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            ) { decoder, _, _ -> decoder.isMutableRequired = true }
        } else {
            // Absolute file path (e.g. produced by saveEditedBitmapToPrivateFile)
            BitmapFactory.decodeFile(uriString)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    override fun onCleared() {
        super.onCleared()
        beautyRenderJob?.cancel()
        faceScanJob?.cancel()

        val oldPreview = _beautyPreviewBitmap.value
        _beautyPreviewBitmap.value = null
        if (oldPreview != null && oldPreview !== previewBitmap) oldPreview.recycle()

        previewBitmap?.recycle()
        previewBitmap = null

        originalBeautySource?.recycle()
        originalBeautySource = null

        _finalCroppedBitmap.value?.recycle()
        _finalCroppedBitmap.value = null

        _processedCutoutBitmap.value?.let { if (!it.isRecycled) it.recycle() }
        _processedCutoutBitmap.value = null

        // Add this to clear the vault if the app is forcefully closed
        _pristineBackgroundBitmap.value?.let { if (!it.isRecycled) it.recycle() }
        _pristineBackgroundBitmap.value = null

        clearBatches()
    }

    class Factory(
        private val repository: ProjectRepository,
        private val projectId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EditorViewModel(repository, projectId) as T
        }
    }
}