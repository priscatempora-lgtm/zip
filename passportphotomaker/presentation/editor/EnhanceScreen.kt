package com.example.passportphotomaker.presentation.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.BitmapShader
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.passportphotomaker.domain.util.BackgroundRemover
import com.example.passportphotomaker.domain.util.FaceEnhancer
import com.example.passportphotomaker.domain.util.ImageUpscaler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class StudioTab { BACKGROUND, UPSCALE, ADJUST, FACE }
enum class MaskMode  { ERASE, RESTORE }
enum class AdjustMode { BRIGHTNESS, CONTRAST, SATURATION, WARMTH }


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhanceScreen(
    viewModel: EditorViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToNext: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val rawInitialBitmap by viewModel.finalCroppedBitmap.collectAsState()

    var workingInitialBitmap by remember(rawInitialBitmap) { mutableStateOf(rawInitialBitmap) }
    var displayBitmap        by remember(rawInitialBitmap) { mutableStateOf(rawInitialBitmap) }

    var isProcessing  by remember { mutableStateOf(false) }
    val isScanningFace by viewModel.isScanningFace.collectAsState()
    var currentTab    by remember { mutableStateOf(StudioTab.BACKGROUND) }
    var isManualMasking by remember { mutableStateOf(false) }

    val standardColors = listOf(Color.Transparent, Color.White, Color(0xFFE6F2FF), Color(0xFFF0F0F0), Color(0xFF003399), Color(0xFFCC0000))
    var selectedBgColor by remember { mutableStateOf(Color.Transparent) }
    var bgShade         by remember { mutableStateOf(0f) }
    var bgImageBitmap   by remember { mutableStateOf<Bitmap?>(null) }

    // THE FIX: Reset stale state if user goes back from PrintScreen to prevent ghost OOM caching
    LaunchedEffect(rawInitialBitmap) {
        selectedBgColor = Color.Transparent
        bgShade = 0f
        bgImageBitmap = null
        isManualMasking = false
    }

    // Start / cancel the beauty session whenever the Face tab is entered or left
    LaunchedEffect(currentTab) {
        if (currentTab == StudioTab.FACE) {
            viewModel.startBeautySession(context)
        } else {
            viewModel.cancelBeautySession()
        }
    }

    val finalBgColor = remember(selectedBgColor, bgShade) {
        when {
            selectedBgColor == Color.Transparent -> Color.Transparent
            bgShade > 0f  -> lerp(selectedBgColor, Color.White, bgShade)
            else          -> lerp(selectedBgColor, Color.Black, -bgShade)
        }
    }

    val checkerboardBrush = remember {
        val size = 40
        val bitmap = Bitmap.createBitmap(size * 2, size * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint1 = Paint().apply { color = android.graphics.Color.parseColor("#E0E0E0") }
        val paint2 = Paint().apply { color = android.graphics.Color.parseColor("#FFFFFF") }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint1)
        canvas.drawRect(size.toFloat(), size.toFloat(), size * 2f, size * 2f, paint1)
        canvas.drawRect(size.toFloat(), 0f, size * 2f, size.toFloat(), paint2)
        canvas.drawRect(0f, size.toFloat(), size.toFloat(), size * 2f, paint2)
        ShaderBrush(BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT))
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let {
            bgImageBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, it)) { decoder, _, _ -> decoder.isMutableRequired = true }
            } else {
                @Suppress("DEPRECATION")
                context.contentResolver.openInputStream(it)?.use { stream -> BitmapFactory.decodeStream(stream) }
            }
        }
    }

    var upscaleFactor  by remember { mutableStateOf(2f) }
    var upscaleSharpen by remember { mutableStateOf(0.6f) }
    var upscaleNoise   by remember { mutableStateOf(0.4f) }
    var upscaleGrain   by remember { mutableStateOf(0.0f) }

    // ── Face state ──
    var skinBrightness by remember { mutableStateOf(0f) }
    var blemishReduction by remember { mutableStateOf(0f) }
    var underEyeReduction by remember { mutableStateOf(0f) }
    var eyeBrightening by remember { mutableStateOf(0f) }
    var teethWhitening by remember { mutableStateOf(0f) }
    var faceSharpening by remember { mutableStateOf(0f) }
    var eyebrowDefinition by remember { mutableStateOf(0f) }

    var maskMode      by remember { mutableStateOf(MaskMode.ERASE) }
    var activeMaskParam by remember { mutableStateOf(0) }
    var brushSize     by remember { mutableStateOf(50f) }
    var brushOffset   by remember { mutableStateOf(100f) }
    var isSliderActive by remember { mutableStateOf(false) }

    var scale         by remember { mutableStateOf(1f) }
    var pan           by remember { mutableStateOf(Offset.Zero) }
    var strokes       by remember { mutableStateOf(listOf<MaskStroke>()) }
    var currentPath   by remember { mutableStateOf<Path?>(null) }
    var currentTouchPos by remember { mutableStateOf<Offset?>(null) }

    var imageDrawWidthPx by remember { mutableStateOf(1f) }

    val restoreBrush = remember(workingInitialBitmap, imageDrawWidthPx) {
        workingInitialBitmap?.let { bmp ->
            val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            if (imageDrawWidthPx > 1f && bmp.width > 0) {
                val sc = imageDrawWidthPx / bmp.width.toFloat()
                shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(sc, sc) })
            }
            ShaderBrush(shader)
        }
    }

    var brightness by remember { mutableStateOf(0f) }
    var contrast   by remember { mutableStateOf(1f) }
    var saturation by remember { mutableStateOf(1f) }
    var warmth     by remember { mutableStateOf(0f) }

    val adjustmentMatrix = remember(brightness, contrast, saturation, warmth) {
        val m = android.graphics.ColorMatrix()
        m.set(floatArrayOf(contrast, 0f, 0f, 0f, brightness, 0f, contrast, 0f, 0f, brightness, 0f, 0f, contrast, 0f, brightness, 0f, 0f, 0f, 1f, 0f))
        m.postConcat(android.graphics.ColorMatrix().apply { setSaturation(saturation) })
        m.postConcat(android.graphics.ColorMatrix(floatArrayOf(1f + warmth, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f - warmth, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        ColorMatrix(m.array)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isManualMasking) "Manual Masking" else "Studio Editor") },
                navigationIcon = {
                    IconButton(onClick = { if (isManualMasking) isManualMasking = false else onNavigateBack() }) {
                        Icon(if (isManualMasking) Icons.Filled.Close else Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (isManualMasking) {
                        TextButton(onClick = { isManualMasking = false }) { Text("Done", fontWeight = FontWeight.Bold) }
                    } else {
                        TextButton(onClick = {
                            val initial = workingInitialBitmap ?: return@TextButton
                            val display = displayBitmap ?: return@TextButton

                            // THE FIX: The Smart Memory Bypass. If you made ZERO edits, skip baking immediately!
                            val hasEdits = bgImageBitmap != null || finalBgColor != Color.Transparent || strokes.isNotEmpty() || brightness != 0f || contrast != 1f || saturation != 1f || warmth != 0f || display !== initial
                            if (!hasEdits) {
                                viewModel.setCroppedBitmap(display)
                                onNavigateToNext()
                                return@TextButton
                            }

                            isProcessing = true
                            coroutineScope.launch {
                                val baked = withContext(Dispatchers.Default) {
                                    var finalBmp: Bitmap? = null
                                    var scaleD = 1f
                                    val w = initial.width
                                    val h = initial.height

                                    // THE FIX: The Dynamic Memory Fallback. If it runs out of RAM, it auto-scales down to survive instead of crashing!
                                    while (finalBmp == null && scaleD > 0.1f) {
                                        try { finalBmp = Bitmap.createBitmap((w * scaleD).toInt(), (h * scaleD).toInt(), Bitmap.Config.ARGB_8888) } 
                                        catch (e: OutOfMemoryError) { scaleD -= 0.2f }
                                    }
                                    if (finalBmp == null) return@withContext display // Absolute fallback

                                    val rCanvas = Canvas(finalBmp)
                                    val outW = finalBmp.width
                                    val outH = finalBmp.height

                                    if (bgImageBitmap != null) {
                                        val bg = bgImageBitmap!!
                                        val bgRatio = bg.width.toFloat() / bg.height.toFloat()
                                        val destRatio = outW.toFloat() / outH.toFloat()
                                        var srcW = bg.width.toFloat()
                                        var srcH = bg.height.toFloat()
                                        if (bgRatio > destRatio) { srcW = srcH * destRatio } else { srcH = srcW / destRatio }
                                        val srcX = (bg.width - srcW) / 2f
                                        val srcY = (bg.height - srcH) / 2f

                                        rCanvas.drawBitmap(bg, android.graphics.Rect(srcX.toInt(), srcY.toInt(), (srcX + srcW).toInt(), (srcY + srcH).toInt()), android.graphics.Rect(0, 0, outW, outH), null)
                                    } else if (finalBgColor != Color.Transparent) {
                                        rCanvas.drawColor(android.graphics.Color.argb((finalBgColor.alpha * 255).toInt(), (finalBgColor.red * 255).toInt(), (finalBgColor.green * 255).toInt(), (finalBgColor.blue * 255).toInt()))
                                    }

                                    // Merges Color Filter directly on Canvas without new allocations
                                    val colorFilterPaint = Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(adjustmentMatrix.values) }
                                    rCanvas.drawBitmap(Bitmap.createScaledBitmap(display, outW, outH, true), 0f, 0f, colorFilterPaint)

                                    val scaleToBitmap = outW.toFloat() / imageDrawWidthPx
                                    rCanvas.save()
                                    rCanvas.clipRect(0f, 0f, outW.toFloat(), outH.toFloat())
                                    rCanvas.scale(scaleToBitmap, scaleToBitmap)

                                    strokes.forEach { stroke ->
                                        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                            style = Paint.Style.STROKE; strokeWidth = stroke.width; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                                            if (stroke.isErase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                                            else {
                                                shader = BitmapShader(initial, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                                                colorFilter = android.graphics.ColorMatrixColorFilter(adjustmentMatrix.values)
                                            }
                                        }
                                        rCanvas.drawPath(stroke.path.asAndroidPath(), p)
                                    }
                                    rCanvas.restore()   
                                    finalBmp
                                }
                                viewModel.setCroppedBitmap(baked)
                                isProcessing = false
                                onNavigateToNext()
                            }
                        }) { Text("Next", fontWeight = FontWeight.Bold) }
                    }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 16.dp, color = MaterialTheme.colorScheme.surface) {
                if (isManualMasking) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            FilterChip(selected = maskMode == MaskMode.ERASE, onClick = { maskMode = MaskMode.ERASE }, label = { Text("Erase") })
                            FilterChip(selected = maskMode == MaskMode.RESTORE, onClick = { maskMode = MaskMode.RESTORE }, label = { Text("Restore") })
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { FilterChip(selected = activeMaskParam == 0, onClick  = { activeMaskParam = 0 }, label = { Text("Brush Size") }) }
                            item { FilterChip(selected = activeMaskParam == 1, onClick  = { activeMaskParam = 1 }, label = { Text("Brush Offset") }) }
                        }
                        Spacer(Modifier.height(8.dp))
                        when (activeMaskParam) {
                            0 -> ChipSliderRow("Size", brushSize, 10f..500f, onValueChange = { brushSize = it; isSliderActive = true }, onValueChangeFinished = { isSliderActive = false })
                            1 -> ChipSliderRow("Offset", brushOffset, 0f..300f, onValueChange = { brushOffset = it; isSliderActive = true }, onValueChangeFinished = { isSliderActive = false })
                        }
                    }
                } else {
                    Column {
                        Box(modifier = Modifier.fillMaxWidth().wrapContentHeight().background(MaterialTheme.colorScheme.surfaceVariant)) {
                            when (currentTab) {
                                StudioTab.BACKGROUND -> BackgroundControls(
                                    colors = standardColors, selectedColor = selectedBgColor, onColorSelect = { selectedBgColor = it; bgImageBitmap = null },
                                    shade = bgShade, onShadeChange = { bgShade = it },
                                    onImagePick = { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    onAutoRemove = {
                                    val bmp = workingInitialBitmap ?: return@BackgroundControls
                                    isProcessing = true
                                    coroutineScope.launch {
                                        displayBitmap = withContext(Dispatchers.Default) {
                                            BackgroundRemover.removeBackground(context, bmp)   // was: (bmp)
                                        }
                                        isProcessing = false
                                    }
                                },
                                    onManualMask = { isManualMasking = true }
                                )
                                StudioTab.UPSCALE -> UpscaleControls(
                                    scale = upscaleFactor, onScaleChange = { upscaleFactor = it },
                                    sharpen = upscaleSharpen, onSharpenChange = { upscaleSharpen = it },
                                    noise = upscaleNoise, onNoiseChange = { upscaleNoise = it },
                                    grain = upscaleGrain, onGrainChange = { upscaleGrain = it },
                                    onApply = {
                                        val display = displayBitmap ?: return@UpscaleControls
                                        val initial = workingInitialBitmap ?: return@UpscaleControls
                                        isProcessing = true
                                        coroutineScope.launch {
                                            val (newDisplay, newInitial) = withContext(Dispatchers.Default) {
                                                val maxOut = if (upscaleFactor >= 8f) 4800 else 4000
                                                fun upscale(src: Bitmap) = ImageUpscaler.upscale(src, upscaleFactor, maxOut, upscaleSharpen, upscaleNoise, upscaleGrain).bitmap
                                                val upDisplay = upscale(display)
                                                val upInitial = if (display !== initial) upscale(initial) else upDisplay
                                                Pair(upDisplay, upInitial)
                                            }
                                            displayBitmap = newDisplay
                                            workingInitialBitmap = newInitial
                                            strokes = emptyList()
                                            isProcessing = false
                                        }
                                    }
                                )
                                StudioTab.ADJUST -> AdjustmentControls(brightness, { brightness = it }, contrast, { contrast = it }, saturation, { saturation = it }, warmth, { warmth = it })
                                StudioTab.FACE -> FaceControls(
                                    skinBrightness = skinBrightness,
                                    onSkinBrightnessChange = {
                                        skinBrightness = it
                                        viewModel.updateBeautyLive(it, blemishReduction, underEyeReduction, eyeBrightening, teethWhitening, faceSharpening, eyebrowDefinition)
                                    },
                                    blemishReduction = blemishReduction,
                                    onBlemishReductionChange = {
                                        blemishReduction = it
                                        viewModel.updateBeautyLive(skinBrightness, it, underEyeReduction, eyeBrightening, teethWhitening, faceSharpening, eyebrowDefinition)
                                    },
                                    underEyeReduction = underEyeReduction,
                                    onUnderEyeReductionChange = {
                                        underEyeReduction = it
                                        viewModel.updateBeautyLive(skinBrightness, blemishReduction, it, eyeBrightening, teethWhitening, faceSharpening, eyebrowDefinition)
                                    },
                                    eyeBrightening = eyeBrightening,
                                    onEyeBrighteningChange = {
                                        eyeBrightening = it
                                        viewModel.updateBeautyLive(skinBrightness, blemishReduction, underEyeReduction, it, teethWhitening, faceSharpening, eyebrowDefinition)
                                    },
                                    teethWhitening = teethWhitening,
                                    onTeethWhiteningChange = {
                                        teethWhitening = it
                                        viewModel.updateBeautyLive(skinBrightness, blemishReduction, underEyeReduction, eyeBrightening, it, faceSharpening, eyebrowDefinition)
                                    },
                                    faceSharpening = faceSharpening,
                                    onFaceSharpeningChange = {
                                        faceSharpening = it
                                        viewModel.updateBeautyLive(skinBrightness, blemishReduction, underEyeReduction, eyeBrightening, teethWhitening, it, eyebrowDefinition)
                                    },
                                    eyebrowDefinition = eyebrowDefinition,
                                    onEyebrowDefinitionChange = {
                                        eyebrowDefinition = it
                                        viewModel.updateBeautyLive(skinBrightness, blemishReduction, underEyeReduction, eyeBrightening, teethWhitening, faceSharpening, it)
                                    },
                                    onApply = {
                                        viewModel.commitBeautySession()
                                    }
                                )
                            }
                        }
                        HorizontalDivider()
                        Row(modifier = Modifier.fillMaxWidth().height(64.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            StudioTabButton("Background", Icons.Filled.Build, currentTab == StudioTab.BACKGROUND) { currentTab = StudioTab.BACKGROUND }
                            StudioTabButton("Upscale", Icons.Filled.KeyboardArrowUp, currentTab == StudioTab.UPSCALE) { currentTab = StudioTab.UPSCALE }
                            StudioTabButton("Adjust", Icons.Filled.Settings, currentTab == StudioTab.ADJUST) { currentTab = StudioTab.ADJUST }
                            StudioTabButton("Face", Icons.Filled.Face, currentTab == StudioTab.FACE) { currentTab = StudioTab.FACE }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            val display = displayBitmap

            if (display != null && restoreBrush != null) {

                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize()
                        .background(
                            if (bgImageBitmap != null) Color.Transparent 
                            else if (finalBgColor != Color.Transparent) finalBgColor 
                            else Color.Transparent
                        )
                        .then(if (bgImageBitmap == null && finalBgColor == Color.Transparent) Modifier.background(checkerboardBrush) else Modifier)
                ) {
                    val density = LocalDensity.current
                    val screenWidthPx = with(density) { maxWidth.toPx() }
                    val screenHeightPx = with(density) { maxHeight.toPx() }

                    val imgRatio = display.width.toFloat() / display.height.toFloat()
                    val boundsRatio = screenWidthPx / screenHeightPx

                    val drawW = if (imgRatio > boundsRatio) screenHeightPx * imgRatio else screenWidthPx
                    val drawH = if (imgRatio > boundsRatio) screenHeightPx else screenWidthPx / imgRatio

                    LaunchedEffect(drawW) { imageDrawWidthPx = drawW }

                    val drawLeft = (screenWidthPx - drawW) / 2f
                    val drawTop = (screenHeightPx - drawH) / 2f

                    val isManualMaskingState by rememberUpdatedState(isManualMasking)
                    val maskModeState by rememberUpdatedState(maskMode)
                    val brushSizeState by rememberUpdatedState(brushSize)
                    val brushOffsetState by rememberUpdatedState(brushOffset)

                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            // THE FIX: Binding BOTH isManualMasking and displayBitmap ensures a bulletproof reset!
                            .pointerInput(isManualMasking, display) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    if (!isManualMaskingState) return@awaitEachGesture

                                    var isDrawing = false
                                    var isTransforming = false

                                    var activeScale = scale
                                    var activePan = pan

                                    do {
                                        val event = awaitPointerEvent()
                                        val activePointers = event.changes.filter { it.pressed }

                                        if (activePointers.size >= 2) {
                                            if (!isTransforming) {
                                                isTransforming = true
                                                if (isDrawing) {
                                                    currentPath = null
                                                    currentTouchPos = null
                                                    isDrawing = false
                                                }
                                            }

                                            val zoomChange = event.calculateZoom()
                                            val panChange = event.calculatePan()
                                            val centroid = event.calculateCentroid(useCurrent = false)

                                            val newScale = (activeScale * zoomChange).coerceIn(1f, 12f)
                                            val actualZoom = newScale / activeScale

                                            activePan = (activePan + panChange - centroid) * actualZoom + centroid
                                            activeScale = newScale

                                            scale = activeScale
                                            pan = activePan

                                            event.changes.forEach { if (it.positionChanged()) it.consume() }

                                        } else if (activePointers.size == 1 && !isTransforming) {
                                            val change = activePointers.first()
                                            val pointerX = change.position.x
                                            val pointerY = change.position.y

                                            val offsetPx = with(density) { brushOffsetState.dp.toPx() }
                                            val effOffset1x = offsetPx / activeScale

                                            val screen1x = (pointerX - activePan.x) / activeScale
                                            val screen1y = (pointerY - activePan.y) / activeScale

                                            val imageX = screen1x - drawLeft
                                            val imageY = screen1y - drawTop - effOffset1x

                                            if (!isDrawing) {
                                                isDrawing = true
                                                currentPath = Path().apply { moveTo(imageX, imageY) }
                                                currentTouchPos = Offset(pointerX, pointerY - offsetPx)
                                            } else {
                                                currentPath?.lineTo(imageX, imageY)
                                                currentTouchPos = Offset(pointerX, pointerY - offsetPx)
                                            }
                                            if (change.positionChanged()) change.consume()
                                        }
                                    } while (activePointers.isNotEmpty())

                                    if (isDrawing) {
                                        currentPath?.let { path ->
                                            val sizePx = with(density) { brushSizeState.dp.toPx() }
                                            strokes = strokes + MaskStroke(path, sizePx / activeScale, maskModeState == MaskMode.ERASE)
                                        }
                                        currentPath = null
                                        currentTouchPos = null
                                    }
                                }
                            }
                    ) {
                        withTransform({
                            translate(pan.x, pan.y)
                            scale(scale, scale, pivot = Offset.Zero)
                        }) {
                            clipRect(left = drawLeft, top = drawTop, right = drawLeft + drawW, bottom = drawTop + drawH) {
                                bgImageBitmap?.let { bg ->
                                    val bgRatio = bg.width.toFloat() / bg.height.toFloat()
                                    val destRatio = drawW / drawH
                                    var srcW = bg.width.toFloat()
                                    var srcH = bg.height.toFloat()
                                    if (bgRatio > destRatio) { srcW = srcH * destRatio } else { srcH = srcW / destRatio }
                                    val srcX = (bg.width - srcW) / 2f
                                    val srcY = (bg.height - srcH) / 2f

                                    drawImage(image = bg.asImageBitmap(), srcOffset = IntOffset(srcX.toInt(), srcY.toInt()), srcSize = IntSize(srcW.toInt(), srcH.toInt()), dstOffset = IntOffset(drawLeft.toInt(), drawTop.toInt()), dstSize = IntSize(drawW.toInt(), drawH.toInt()))
                                }

                                drawImage(image = display.asImageBitmap(), dstOffset = IntOffset(drawLeft.toInt(), drawTop.toInt()), dstSize = IntSize(drawW.toInt(), drawH.toInt()), colorFilter = ColorFilter.colorMatrix(adjustmentMatrix))

                                translate(left = drawLeft, top = drawTop) {
                                    strokes.forEach { stroke ->
                                        if (stroke.isErase) { drawPath(stroke.path, color = Color.Transparent, style = Stroke(stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Clear) } 
                                        else { drawPath(stroke.path, brush = restoreBrush, style = Stroke(stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Src) }
                                    }

                                    currentPath?.let { path ->
                                        val activeSizePx = with(density) { brushSizeState.dp.toPx() }
                                        if (maskModeState == MaskMode.ERASE) { drawPath(path, color = Color.Transparent, style = Stroke(activeSizePx / scale, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Clear) } 
                                        else { drawPath(path, brush = restoreBrush, style = Stroke(activeSizePx / scale, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Src) }
                                    }
                                }
                            }
                        }

                        if (isManualMaskingState) {
                            val activeSizePx = with(density) { brushSizeState.dp.toPx() }
                            val activeOffsetPx = with(density) { brushOffsetState.dp.toPx() }
                            val radius = activeSizePx / 2f
                            val cursorColor = Color(0xFF6750A4)

                            if (currentTouchPos != null) {
                                drawCircle(color = cursorColor, radius = radius, center = currentTouchPos!!, style = Stroke(width = 2.dp.toPx()))
                            } else if (isSliderActive) {
                                val centerX = size.width / 2f
                                val centerY = size.height / 2f
                                val brushCenterY = centerY - activeOffsetPx

                                drawCircle(color = cursorColor, radius = 4.dp.toPx(), center = Offset(centerX, centerY))
                                drawCircle(color = cursorColor.copy(alpha = 0.1f), radius = radius, center = Offset(centerX, brushCenterY))
                                drawCircle(color = cursorColor, radius = radius, center = Offset(centerX, brushCenterY), style = Stroke(width = 2.dp.toPx()))
                            }
                        }
                    }
                }
            }
            if (isProcessing || isScanningFace) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun BackgroundControls(
    colors: List<Color>, selectedColor: Color, onColorSelect: (Color) -> Unit,
    shade: Float, onShadeChange: (Float) -> Unit,
    onImagePick: () -> Unit, onAutoRemove: () -> Unit, onManualMask: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { AssistChip(onClick = onAutoRemove, label = { Text("Auto Remove") }) }
            item { AssistChip(onClick = onManualMask, label = { Text("Manual Brush") }) }
            item { AssistChip(onClick = onImagePick, label = { Text("Gallery BG") }) }
            items(colors) { c ->
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(if (c == Color.Transparent) Color.LightGray else c).border(width  = if (selectedColor == c) 3.dp else 1.dp, color  = if (selectedColor == c) MaterialTheme.colorScheme.primary else Color.Gray, shape  = CircleShape).clickable { onColorSelect(c) },
                    contentAlignment = Alignment.Center
                ) {
                    if (c == Color.Transparent) Text("✕", fontWeight = FontWeight.Bold, color = Color.DarkGray)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ChipSliderRow(label = if (shade >= 0f) "Lighten  ${(shade * 100).toInt()}%" else "Darken  ${(-shade * 100).toInt()}%", value = shade, range = -1f..1f, onValueChange = onShadeChange)
    }
}

@Composable
fun UpscaleControls(
    scale: Float, onScaleChange: (Float) -> Unit,
    sharpen: Float, onSharpenChange: (Float) -> Unit,
    noise: Float, onNoiseChange: (Float) -> Unit,
    grain: Float, onGrainChange: (Float) -> Unit,
    onApply: () -> Unit
) {
    var activeParam by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        val scales = listOf(2f to "2×", 3f to "3×", 4f to "4×", 6f to "6×", 8f to "HD")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(scales) { (s, label) -> FilterChip(selected = scale == s, onClick = { onScaleChange(s) }, label = { Text(label) }) }
            item { FilterChip(selected = activeParam == 0, onClick = { activeParam = 0 }, label = { Text("Sharpen") }) }
            item { FilterChip(selected = activeParam == 1, onClick = { activeParam = 1 }, label = { Text("Denoise") }) }
            item { FilterChip(selected = activeParam == 2, onClick = { activeParam = 2 }, label = { Text("Film Grain") }) }
        }

        Spacer(Modifier.height(8.dp))
        when (activeParam) {
            0 -> ChipSliderRow("Sharpen", sharpen, 0f..2f,  onSharpenChange)
            1 -> ChipSliderRow("Denoise", noise, 0f..1f,  onNoiseChange)
            2 -> ChipSliderRow("Film Grain", grain, 0f..1f,  onGrainChange)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Apply Upscale", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AdjustmentControls(
    brightness: Float, onBrightnessChange: (Float) -> Unit,
    contrast: Float, onContrastChange: (Float) -> Unit,
    saturation: Float, onSaturationChange: (Float) -> Unit,
    warmth: Float, onWarmthChange: (Float) -> Unit
) {
    var activeMode by remember { mutableStateOf(AdjustMode.BRIGHTNESS) }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = activeMode == AdjustMode.BRIGHTNESS, onClick = { activeMode = AdjustMode.BRIGHTNESS }, label = { Text("Brightness") }) }
            item { FilterChip(selected = activeMode == AdjustMode.CONTRAST,   onClick = { activeMode = AdjustMode.CONTRAST   }, label = { Text("Contrast")   }) }
            item { FilterChip(selected = activeMode == AdjustMode.SATURATION, onClick = { activeMode = AdjustMode.SATURATION }, label = { Text("Saturation") }) }
            item { FilterChip(selected = activeMode == AdjustMode.WARMTH,     onClick = { activeMode = AdjustMode.WARMTH     }, label = { Text("Warmth")     }) }
        }
        Spacer(Modifier.height(8.dp))
        when (activeMode) {
            AdjustMode.BRIGHTNESS -> ChipSliderRow("Brightness", brightness, -100f..100f, onBrightnessChange, displayValue = "${brightness.toInt()}")
            AdjustMode.CONTRAST   -> ChipSliderRow("Contrast",   contrast,   0.5f..2.0f,  onContrastChange,   displayValue = "%.2f".format(contrast))
            AdjustMode.SATURATION -> ChipSliderRow("Saturation", saturation, 0.0f..2.0f,  onSaturationChange, displayValue = "%.2f".format(saturation))
            AdjustMode.WARMTH     -> ChipSliderRow("Warmth",     warmth,     -0.5f..0.5f, onWarmthChange,     displayValue = "%.2f".format(warmth))
        }
    }
}

@Composable
fun FaceControls(
    skinBrightness: Float, onSkinBrightnessChange: (Float) -> Unit,
    blemishReduction: Float, onBlemishReductionChange: (Float) -> Unit,
    underEyeReduction: Float, onUnderEyeReductionChange: (Float) -> Unit,
    eyeBrightening: Float, onEyeBrighteningChange: (Float) -> Unit,
    teethWhitening: Float, onTeethWhiteningChange: (Float) -> Unit,
    faceSharpening: Float, onFaceSharpeningChange: (Float) -> Unit,
    eyebrowDefinition: Float, onEyebrowDefinitionChange: (Float) -> Unit,
    onApply: () -> Unit
) {
    var activeParam by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = activeParam == 0, onClick = { activeParam = 0 }, label = { Text("Skin") }) }
            item { FilterChip(selected = activeParam == 1, onClick = { activeParam = 1 }, label = { Text("Blemishes") }) }
            item { FilterChip(selected = activeParam == 2, onClick = { activeParam = 2 }, label = { Text("Under-Eye") }) }
            item { FilterChip(selected = activeParam == 3, onClick = { activeParam = 3 }, label = { Text("Eyes") }) }
            item { FilterChip(selected = activeParam == 4, onClick = { activeParam = 4 }, label = { Text("Teeth") }) }
            item { FilterChip(selected = activeParam == 5, onClick = { activeParam = 5 }, label = { Text("Sharpen") }) }
            item { FilterChip(selected = activeParam == 6, onClick = { activeParam = 6 }, label = { Text("Eyebrows") }) }
        }
        Spacer(Modifier.height(8.dp))
        when (activeParam) {
            0 -> ChipSliderRow("Brightness", skinBrightness, 0f..1f, onSkinBrightnessChange)
            1 -> ChipSliderRow("Blemishes", blemishReduction, 0f..1f, onBlemishReductionChange)
            2 -> ChipSliderRow("Eye Bags", underEyeReduction, 0f..1f, onUnderEyeReductionChange)
            3 -> ChipSliderRow("Brighten Eyes", eyeBrightening, 0f..1f, onEyeBrighteningChange)
            4 -> ChipSliderRow("Whiten Teeth", teethWhitening, 0f..1f, onTeethWhiteningChange)
            5 -> ChipSliderRow("Sharpen", faceSharpening, 0f..1f, onFaceSharpeningChange)
            6 -> ChipSliderRow("Eyebrows", eyebrowDefinition, 0f..1f, onEyebrowDefinitionChange)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Face, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Apply Face Retouch", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ChipSliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit, onValueChangeFinished: (() -> Unit)? = null,
    displayValue: String = "%.1f".format(value)
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(label, modifier = Modifier.width(110.dp), style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onValueChange, onValueChangeFinished = onValueChangeFinished, valueRange = range, modifier = Modifier.weight(1f))
        Text(displayValue, modifier  = Modifier.width(44.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun RowScope.StudioTabButton(
    title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, isSelected: Boolean, onClick: () -> Unit
) {
    Column(modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = title, tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray, modifier = Modifier.size(24.dp))
        Text(title, style = MaterialTheme.typography.labelSmall, color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
    }
}
