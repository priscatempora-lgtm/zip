package com.example.passportphotomaker.presentation.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Tabs ──────────────────────────────────────────────────────────────────────
enum class RetouchTab { FACE, COLOR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetouchScreen(
    viewModel: EditorViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToNext: () -> Unit,
    isStandaloneEdit: Boolean = false,
    onEscapeBatchMode: (() -> Unit)? = null
) {
    val context        = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // ── Batch mode state (Requirement 4) ─────────────────────────────────────
    val isBatchMode      by viewModel.isBatchMode.collectAsState()
    val printBatches     by viewModel.printBatches.collectAsState()
    val batchImageNumber = printBatches.size + 1

    // Bug 3: pre-load the project's persisted image when Retouch is opened
    // directly from "My Studio" (standalone edit) without going through Crop.
    // Waits for the DB load to complete before decoding, so the screen never
    // opens blank even when projectState is still loading.
    LaunchedEffect(isStandaloneEdit) {
        if (!isStandaloneEdit) return@LaunchedEffect
        viewModel.loadImageForStandaloneEdit(context)
    }

    // ── State from ViewModel ──────────────────────────────────────────────────
    val bitmap         by viewModel.finalCroppedBitmap.collectAsState()
    val beautyPreview  by viewModel.beautyPreviewBitmap.collectAsState()
    val isScanningFace by viewModel.isScanningFace.collectAsState()

    // Local loading flag — covers both the full-res beauty bake and the colour
    // matrix flatten that happen when the user taps "Next".
    var isBaking by remember { mutableStateOf(false) }

    // null = neutral (neither tab selected on launch — prevents eager ML scan)
    var activeTab by remember { mutableStateOf<RetouchTab?>(null) }

    // ── Face sliders ──────────────────────────────────────────────────────────
    var skinBrightness    by remember { mutableStateOf(0f) }
    var blemishReduction  by remember { mutableStateOf(0f) }
    var underEyeReduction by remember { mutableStateOf(0f) }
    var eyeBrightening    by remember { mutableStateOf(0f) }
    var teethWhitening    by remember { mutableStateOf(0f) }
    var faceSharpening    by remember { mutableStateOf(0f) }
    var eyebrowDefinition by remember { mutableStateOf(0f) }

    // ── Colour sliders ────────────────────────────────────────────────────────
    var brightness by remember { mutableStateOf(0f) }
    var contrast   by remember { mutableStateOf(1f) }
    var saturation by remember { mutableStateOf(1f) }
    var warmth     by remember { mutableStateOf(0f) }

    val adjustmentMatrix = remember(brightness, contrast, saturation, warmth) {
        val m = android.graphics.ColorMatrix()
        m.set(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))
        m.postConcat(android.graphics.ColorMatrix().apply { setSaturation(saturation) })
        m.postConcat(android.graphics.ColorMatrix(floatArrayOf(
            1f + warmth, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f - warmth, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
        ColorMatrix(m.array)
    }

    // ── Derived display bitmap ────────────────────────────────────────────────
    // Always prefer beautyPreview when it exists — it is the C++ beauty output
    // and must remain visible regardless of which tab is currently selected.
    // Switching to the Color tab must NOT revert to the raw bitmap; the color
    // ColorFilter is composed on top of whatever displayBitmap is shown, so
    // beauty + color edits stack correctly in both tabs.
    // Fall back to the full-res finalCroppedBitmap only when no beauty session
    // has produced output yet.
    val displayBitmap: Bitmap? = beautyPreview ?: bitmap

    // No LaunchedEffect here — ML scan only starts when the user explicitly
    // taps "Face Retouch" (see FilterChip onClick below).

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isBatchMode) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Retouch")
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = MaterialTheme.shapes.extraLarge,
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text     = "Batch: Image $batchImageNumber",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style    = MaterialTheme.typography.labelSmall,
                                    color    = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else { Text("Retouch") }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.cancelBeautySession()
                        onNavigateBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    // Batch escape hatch — clearCurrentSession + popBackStack handled in NavHost
                    if (isBatchMode) {
                        IconButton(onClick = { onEscapeBatchMode?.invoke() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel batch")
                        }
                    }
                    TextButton(
                        enabled = !isBaking && !isScanningFace,
                        onClick = {
                            isBaking = true
                            coroutineScope.launch {
                                // ── STEP 1: Full-res beauty bake (if face session active) ──
                                //
                                // bakeFullResBeauty also:
                                //   • saves step_1_crop.webp  (pristine crop for "Back")
                                //   • saves step_2_beauty.webp (beauty-only reference)
                                //
                                // If no face session, we save step_1 manually so "Back"
                                // from BackgroundScreen still has a checkpoint to restore.
                                val beautyBaked: Bitmap? = if (viewModel.hasActiveBeautySession()) {
                                    viewModel.bakeFullResBeauty(context)
                                } else {
                                    bitmap?.let {
                                        viewModel.saveToCache(context, it, "step_1_crop.webp")
                                    }
                                    null
                                }

                                // ── STEP 2: Flatten colour-matrix adjustments ──────────────
                                val src = beautyBaked ?: bitmap ?: run {
                                    isBaking = false
                                    return@launch
                                }

                                val isColorIdentity = brightness == 0f && contrast == 1f &&
                                        saturation == 1f && warmth == 0f

                                val finalBaked: Bitmap = withContext(Dispatchers.Default) {
                                    if (isColorIdentity) {
                                        // No colour changes — use a copy to avoid mutating src.
                                        src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
                                    } else {
                                        val out = Bitmap.createBitmap(
                                            src.width, src.height, Bitmap.Config.ARGB_8888
                                        )
                                        Canvas(out).drawBitmap(
                                            src, 0f, 0f,
                                            Paint().apply {
                                                colorFilter = android.graphics.ColorMatrixColorFilter(
                                                    adjustmentMatrix.values
                                                )
                                            }
                                        )
                                        out
                                    }
                                }

                                // Recycle the intermediate beauty-only bake now that we
                                // have the colour-flattened version; guard against recycling
                                // the same object we are about to hand to the ViewModel.
                                if (beautyBaked != null && finalBaked !== beautyBaked) {
                                    beautyBaked.recycle()
                                }

                                // ── STEP 3: Commit and navigate ───────────────────────────
                                
                                // 🔥 THE BULLETPROOF CHOKE POINT 🔥
                                // Vaporize the background caches right here! This guarantees 
                                // that no matter how you arrived at this screen, the Background 
                                // Screen will be forced to scan the new image.
                                viewModel.clearProcessedCutout()
                                
                                viewModel.setCroppedBitmap(finalBaked)
                                viewModel.clearBeautySession()
                                // Standalone edit: persist before popping back to ProjectDetails
                                if (isStandaloneEdit) {
                                    viewModel.saveStagingBitmapToPrivateFile(context)
                                }
                                isBaking = false
                                onNavigateToNext()
                            }
                        }
                    ) { Text("Next", fontWeight = FontWeight.Bold) }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                Column {
                    // ── Tool-selector row — neither chip selected on launch ────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = activeTab == RetouchTab.FACE,
                            onClick  = {
                                // Only trigger the heavy ML scan on first tap.
                                if (activeTab != RetouchTab.FACE) {
                                    activeTab = RetouchTab.FACE
                                    viewModel.startBeautySession(context)
                                }
                            },
                            label    = { Text("Face Retouch", maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = activeTab == RetouchTab.COLOR,
                            onClick  = { activeTab = RetouchTab.COLOR },
                            label    = { Text("Color Adjust", maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // ── Tool content area ─────────────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        when (activeTab) {
                            null -> {
                                // Neutral state — no sliders shown, no ML running
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 28.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text  = "Select a tool above to begin editing",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            RetouchTab.FACE -> RetouchFaceControls(
                                skinBrightness         = skinBrightness,
                                onSkinBrightnessChange = {
                                    skinBrightness = it
                                    viewModel.updateBeautyLive(
                                        it, blemishReduction, underEyeReduction,
                                        eyeBrightening, teethWhitening, faceSharpening, eyebrowDefinition
                                    )
                                },
                                blemishReduction         = blemishReduction,
                                onBlemishReductionChange = {
                                    blemishReduction = it
                                    viewModel.updateBeautyLive(
                                        skinBrightness, it, underEyeReduction,
                                        eyeBrightening, teethWhitening, faceSharpening, eyebrowDefinition
                                    )
                                },
                                underEyeReduction         = underEyeReduction,
                                onUnderEyeReductionChange = {
                                    underEyeReduction = it
                                    viewModel.updateBeautyLive(
                                        skinBrightness, blemishReduction, it,
                                        eyeBrightening, teethWhitening, faceSharpening, eyebrowDefinition
                                    )
                                },
                                eyeBrightening         = eyeBrightening,
                                onEyeBrighteningChange = {
                                    eyeBrightening = it
                                    viewModel.updateBeautyLive(
                                        skinBrightness, blemishReduction, underEyeReduction,
                                        it, teethWhitening, faceSharpening, eyebrowDefinition
                                    )
                                },
                                teethWhitening         = teethWhitening,
                                onTeethWhiteningChange = {
                                    teethWhitening = it
                                    viewModel.updateBeautyLive(
                                        skinBrightness, blemishReduction, underEyeReduction,
                                        eyeBrightening, it, faceSharpening, eyebrowDefinition
                                    )
                                },
                                faceSharpening         = faceSharpening,
                                onFaceSharpeningChange = {
                                    faceSharpening = it
                                    viewModel.updateBeautyLive(
                                        skinBrightness, blemishReduction, underEyeReduction,
                                        eyeBrightening, teethWhitening, it, eyebrowDefinition
                                    )
                                },
                                eyebrowDefinition         = eyebrowDefinition,
                                onEyebrowDefinitionChange = {
                                    eyebrowDefinition = it
                                    viewModel.updateBeautyLive(
                                        skinBrightness, blemishReduction, underEyeReduction,
                                        eyeBrightening, teethWhitening, faceSharpening, it
                                    )
                                },
                                isScanningFace = isScanningFace
                            )

                            RetouchTab.COLOR -> RetouchColorControls(
                                brightness = brightness, onBrightnessChange = { brightness = it },
                                contrast   = contrast,   onContrastChange   = { contrast   = it },
                                saturation = saturation, onSaturationChange = { saturation = it },
                                warmth     = warmth,     onWarmthChange     = { warmth     = it }
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            val bmp = displayBitmap
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap             = bmp.asImageBitmap(),
                    contentDescription = "Preview",
                    modifier           = Modifier.fillMaxSize(),
                    contentScale       = ContentScale.Fit,
                    // Apply the colour matrix as a Compose filter so it stays
                    // real-time without requiring a bake during interactive editing.
                    // During Face tab the beautyPreview is already the C++ output;
                    // the colour filter stacks on top visually.
                    colorFilter        = ColorFilter.colorMatrix(adjustmentMatrix)
                )
            }

            // Loading overlay — shown while the face scan runs or while the
            // full-res beauty bake + colour flatten is in progress on "Next".
            if (isBaking || isScanningFace) {
                Surface(
                    color  = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxSize()
                ) {}
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier            = Modifier.fillMaxSize()
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    if (isBaking) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text  = "Applying full-resolution edits…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }
}

// ── Face controls composable ───────────────────────────────────────────────────
@Composable
fun RetouchFaceControls(
    skinBrightness: Float,    onSkinBrightnessChange: (Float) -> Unit,
    blemishReduction: Float,  onBlemishReductionChange: (Float) -> Unit,
    underEyeReduction: Float, onUnderEyeReductionChange: (Float) -> Unit,
    eyeBrightening: Float,    onEyeBrighteningChange: (Float) -> Unit,
    teethWhitening: Float,    onTeethWhiteningChange: (Float) -> Unit,
    faceSharpening: Float,    onFaceSharpeningChange: (Float) -> Unit,
    eyebrowDefinition: Float, onEyebrowDefinitionChange: (Float) -> Unit,
    isScanningFace: Boolean
) {
    var activeParam by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        if (isScanningFace) {
            Row(
                modifier            = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment   = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Scanning face…", style = MaterialTheme.typography.labelMedium)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = activeParam == 0, onClick = { activeParam = 0 }, label = { Text("Skin")      }) }
            item { FilterChip(selected = activeParam == 1, onClick = { activeParam = 1 }, label = { Text("Blemishes") }) }
            item { FilterChip(selected = activeParam == 2, onClick = { activeParam = 2 }, label = { Text("Eye Bags")  }) }
            item { FilterChip(selected = activeParam == 3, onClick = { activeParam = 3 }, label = { Text("Eyes")      }) }
            item { FilterChip(selected = activeParam == 4, onClick = { activeParam = 4 }, label = { Text("Teeth")     }) }
            item { FilterChip(selected = activeParam == 5, onClick = { activeParam = 5 }, label = { Text("Sharpen")   }) }
            item { FilterChip(selected = activeParam == 6, onClick = { activeParam = 6 }, label = { Text("Eyebrows")  }) }
        }
        Spacer(Modifier.height(8.dp))
        when (activeParam) {
            0 -> RetouchSliderRow("Skin Brightness", skinBrightness,    0f..1f, onSkinBrightnessChange)
            1 -> RetouchSliderRow("Blemishes",       blemishReduction,  0f..1f, onBlemishReductionChange)
            2 -> RetouchSliderRow("Eye Bags",        underEyeReduction, 0f..1f, onUnderEyeReductionChange)
            3 -> RetouchSliderRow("Brighten Eyes",   eyeBrightening,    0f..1f, onEyeBrighteningChange)
            4 -> RetouchSliderRow("Whiten Teeth",    teethWhitening,    0f..1f, onTeethWhiteningChange)
            5 -> RetouchSliderRow("Sharpen",         faceSharpening,    0f..1f, onFaceSharpeningChange)
            6 -> RetouchSliderRow("Eyebrows",        eyebrowDefinition, 0f..1f, onEyebrowDefinitionChange)
        }
    }
}

// ── Colour-adjust controls composable ─────────────────────────────────────────
@Composable
fun RetouchColorControls(
    brightness: Float, onBrightnessChange: (Float) -> Unit,
    contrast:   Float, onContrastChange:   (Float) -> Unit,
    saturation: Float, onSaturationChange: (Float) -> Unit,
    warmth:     Float, onWarmthChange:     (Float) -> Unit
) {
    var activeParam by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = activeParam == 0, onClick = { activeParam = 0 }, label = { Text("Brightness") }) }
            item { FilterChip(selected = activeParam == 1, onClick = { activeParam = 1 }, label = { Text("Contrast")   }) }
            item { FilterChip(selected = activeParam == 2, onClick = { activeParam = 2 }, label = { Text("Saturation") }) }
            item { FilterChip(selected = activeParam == 3, onClick = { activeParam = 3 }, label = { Text("Warmth")     }) }
        }
        Spacer(Modifier.height(8.dp))
        when (activeParam) {
            0 -> RetouchSliderRow("Brightness", brightness, -100f..100f, onBrightnessChange, "${brightness.toInt()}")
            1 -> RetouchSliderRow("Contrast",   contrast,   0.5f..2.0f,  onContrastChange,   "%.2f".format(contrast))
            2 -> RetouchSliderRow("Saturation", saturation, 0.0f..2.0f,  onSaturationChange, "%.2f".format(saturation))
            3 -> RetouchSliderRow("Warmth",     warmth,    -0.5f..0.5f,  onWarmthChange,     "%.2f".format(warmth))
        }
    }
}

// ── Shared slider row ─────────────────────────────────────────────────────────
@Composable
fun RetouchSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    displayValue: String = "%.1f".format(value)
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier.fillMaxWidth().height(48.dp)
    ) {
        Text(label,        modifier = Modifier.width(120.dp), style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onValueChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(displayValue, modifier = Modifier.width(44.dp),  style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
    }
}
