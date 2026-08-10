package com.example.passportphotomaker.presentation.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.passportphotomaker.domain.model.CropRatio
import com.example.passportphotomaker.domain.model.CropBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropAlignScreen(
    viewModel: EditorViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToOutput: (String) -> Unit,
    isStandaloneEdit: Boolean = false,
    onEscapeBatchMode: (() -> Unit)? = null,
    // Fix 4: explicit standalone completion callback so the Done button has a
    // clean, direct route back to ProjectDetails without relying on the
    // non-standalone onNavigateToOutput path.
    onStandaloneComplete: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // â”€â”€ Batch mode state (Requirement 4) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    val isBatchMode      by viewModel.isBatchMode.collectAsState()
    val printBatches     by viewModel.printBatches.collectAsState()
    val batchImageNumber = printBatches.size + 1

    val projectState = viewModel.projectState.collectAsState().value
    val activeTool = viewModel.activeTool.collectAsState().value
    val currentGrid = viewModel.currentGrid.collectAsState().value

    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var imageIntrinsicSize by remember { mutableStateOf(Size.Zero) }

    var cropBox by remember { mutableStateOf<CropBox?>(null) }
    var settledCropBox by remember { mutableStateOf<CropBox?>(null) }
    var isDraggingBox by remember { mutableStateOf(false) }
    var lastInitializedRatio by remember { mutableStateOf<CropRatio?>(null) }

    var isCropping by remember { mutableStateOf(false) }

    // The crop frame is anchored to a FIXED box: the un-rotated image fitted into
    // the canvas. Rotation must NOT resize this box — the exporter maps imageRect
    // 1:1 onto the rotated bitmap, and the crop box is re-initialised whenever
    // imageRect changes. Swapping width/height while "sideways" broke both.
    val imageRect = remember(canvasSize, imageIntrinsicSize) {
        if (canvasSize.width <= 0f || imageIntrinsicSize.width <= 0f) {
            Rect.Zero
        } else {
            val origW = imageIntrinsicSize.width
            val origH = imageIntrinsicSize.height

            val baseScale = minOf(canvasSize.width / origW, canvasSize.height / origH)
            val fittedW = origW * baseScale
            val fittedH = origH * baseScale

            val left = (canvasSize.width - fittedW) / 2f
            val top = (canvasSize.height - fittedH) / 2f
            Rect(left, top, left + fittedW, top + fittedH)
        }
    }

    LaunchedEffect(projectState?.cropRatio, imageRect) {
        if (isDraggingBox || projectState == null) return@LaunchedEffect
        if (imageRect.width <= 0f || imageRect.height <= 0f || canvasSize.width <= 0f) {
            return@LaunchedEffect
        } 

        val currentRatio = projectState.cropRatio
        if (cropBox != null && lastInitializedRatio == currentRatio) {
            // Only trust the saved cropBox if it still lies fully within the
            // current imageRect. When returning from RetouchScreen the composable
            // may recompose with different imageRect dimensions (different screen
            // state / density), making the old absolute coordinates stale and
            // potentially out-of-bounds. In that case fall through to recalculate
            // and re-center the crop box from scratch.
            val box = cropBox!!
            if (box.left >= imageRect.left && box.top >= imageRect.top &&
                box.right <= imageRect.right && box.bottom <= imageRect.bottom) {
                return@LaunchedEffect
            }
            // Stale / out-of-bounds â€” reset so the re-centering logic below runs.
            lastInitializedRatio = null
        }

        // The image is natively padded, so the crop box max limits are 100% of the imageRect!
        val maxW = imageRect.width
        val maxH = imageRect.height

        var w = maxW
        var h = maxH

        val isNotFreeCrop = currentRatio.name != "Free Crop"
        val hasValidRatio = currentRatio.widthRatio > 0 && currentRatio.heightRatio > 0

        if (isNotFreeCrop && hasValidRatio) {
            val r = currentRatio.widthRatio / currentRatio.heightRatio
            if (w / r > maxH) {
                h = maxH
                w = h * r
            } else {
                w = maxW
                h = w / r
            }
        }

        val l = imageRect.left + (imageRect.width - w) / 2f
        val t = imageRect.top + (imageRect.height - h) / 2f

        val newBox = CropBox(l, t, l + w, t + h)
        cropBox = newBox
        settledCropBox = newBox
        lastInitializedRatio = currentRatio
        viewModel.updateCropBox(newBox)
    }

    val targetScale = remember(settledCropBox, imageRect) {
        if (settledCropBox != null && imageRect.width > 0) {
            val maxW = canvasSize.width * 0.95f // Auto-zoom beautifully to 95% of padded screen size
            val maxH = canvasSize.height * 0.95f
            val boxW = settledCropBox!!.width
            val boxH = settledCropBox!!.height
            minOf(maxW / boxW, maxH / boxH).coerceIn(1f, 4f)
        } else {
            1f
        }
    }

    val targetCenterX = settledCropBox?.centerX ?: (canvasSize.width / 2f)
    val targetCenterY = settledCropBox?.centerY ?: (canvasSize.height / 2f)

    val animatedScale by animateFloatAsState(targetValue = targetScale, animationSpec = tween(400), label = "zoom")
    val animatedCenterX by animateFloatAsState(targetValue = targetCenterX, animationSpec = tween(400), label = "centerX")
    val animatedCenterY by animateFloatAsState(targetValue = targetCenterY, animationSpec = tween(400), label = "centerY")

    val animatedPanX = if (canvasSize.width > 0) {
        (canvasSize.width / 2f - animatedCenterX) * animatedScale 
    } else {
        0f
    }
    val animatedPanY = if (canvasSize.height > 0) {
        (canvasSize.height / 2f - animatedCenterY) * animatedScale 
    } else {
        0f
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isBatchMode) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Crop & Align")
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
                    } else { Text("Crop & Align") }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { 
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back") 
                    }
                },
                actions = {
                    // Batch escape hatch â€” clearCurrentSession + popBackStack is
                    // handled entirely in the NavHost lambda (onEscapeBatchMode).
                    if (isBatchMode) {
                        IconButton(onClick = { onEscapeBatchMode?.invoke() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel batch")
                        }
                    }
                    IconButton(onClick = { viewModel.toggleNextGrid() }) {
                        GridIcon() 
                    }
                    IconButton(onClick = { 
                        projectState?.let { state ->
                            if (cropBox != null && imageRect.width > 0f && !isCropping) {
                                isCropping = true 

                                coroutineScope.launch {
                                    // Fix: standalone edits decode from the saved/flattened file,
                                    // not the original gallery URI (which may be a different image).
                                    val rawPath = if (isStandaloneEdit) {
                                        state.stagingImagePath ?: state.savedImagePath ?: state.originalImagePath!!
                                    } else {
                                        state.originalImagePath!!
                                    }
                                    val safeUriString = if (rawPath.startsWith("/")) "file://$rawPath" else rawPath
                                    val uri = Uri.parse(safeUriString)

                                    val cropped = withContext(Dispatchers.IO) {
                                        try {
                                            val resolver = context.contentResolver
                                            val options = BitmapFactory.Options().apply { 
                                                inJustDecodeBounds = true 
                                            }
                                            resolver.openInputStream(uri)?.use { 
                                                BitmapFactory.decodeStream(it, null, options) 
                                            }

                                            var sampleSize = 1
                                            val maxDim = maxOf(options.outWidth, options.outHeight)
                                            while (maxDim / sampleSize > 3500) { 
                                                sampleSize *= 2 
                                            }

                                            var bitmap: Bitmap? = null
                                            var needsExifRotation = false

                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                                val source = android.graphics.ImageDecoder.createSource(resolver, uri)
                                                bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                                                    decoder.setTargetSampleSize(sampleSize)
                                                    decoder.isMutableRequired = true
                                                }
                                                needsExifRotation = false
                                            } else {
                                                val loadOptions = BitmapFactory.Options().apply { 
                                                    inSampleSize = sampleSize 
                                                }
                                                bitmap = resolver.openInputStream(uri)?.use { 
                                                    BitmapFactory.decodeStream(it, null, loadOptions) 
                                                }
                                                needsExifRotation = true
                                            }

                                            if (bitmap == null) return@withContext null

                                            var exifRotation = 0f
                                            if (needsExifRotation) {
                                                resolver.openInputStream(uri)?.use { stream ->
                                                    val exif = android.media.ExifInterface(stream)
                                                    val orientation = exif.getAttributeInt(
                                                        android.media.ExifInterface.TAG_ORIENTATION, 
                                                        android.media.ExifInterface.ORIENTATION_NORMAL
                                                    )
                                                    exifRotation = when (orientation) {
                                                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                                                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                                                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                                                        else -> 0f
                                                    }
                                                }
                                            }

                                            // Export (Done handler)
                                            // Step 1: bake EXIF orientation first, so the pixels
                                            // match what Coil already shows in the preview.
                                            // (Folding it into the user rotation used the PRE-EXIF
                                            // width/height for the cover-scale, which is wrong
                                            // whenever EXIF swaps the axes.)
                                            if (exifRotation % 360f != 0f) {
                                                val em = android.graphics.Matrix().apply { postRotate(exifRotation) }
                                                val oriented = Bitmap.createBitmap(
                                                    bitmap!!, 0, 0, bitmap!!.width, bitmap!!.height, em, true
                                                )
                                                if (oriented !== bitmap) {
                                                    bitmap!!.recycle()
                                                    bitmap = oriented
                                                }
                                            }

                                            // Step 2: apply the user's rotation with the same
                                            // cover-scale as the preview, keeping the target canvas
                                            // at the EXIF-corrected size so it maps 1:1 to imageRect.
                                            val userRotation = state.rotationDegrees
                                            if (userRotation % 360f != 0f) {
                                                val srcW = bitmap!!.width.toFloat()
                                                val srcH = bitmap!!.height.toFloat()
                                                val cx = srcW / 2f
                                                val cy = srcH / 2f

                                                val rad = Math.toRadians(userRotation.toDouble())
                                                val cos = Math.abs(Math.cos(rad)).toFloat()
                                                val sin = Math.abs(Math.sin(rad)).toFloat()
                                                val scaleFactor = maxOf(
                                                    (srcW * cos + srcH * sin) / srcW,
                                                    (srcH * cos + srcW * sin) / srcH
                                                ).coerceAtLeast(1f)

                                                val m = android.graphics.Matrix()
                                                m.postScale(scaleFactor, scaleFactor, cx, cy)
                                                m.postRotate(userRotation, cx, cy)

                                                val rotatedBmp = Bitmap.createBitmap(
                                                    bitmap!!.width, bitmap!!.height, Bitmap.Config.ARGB_8888
                                                )
                                                android.graphics.Canvas(rotatedBmp).drawBitmap(
                                                    bitmap!!, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                                                )
                                                bitmap!!.recycle()
                                                bitmap = rotatedBmp
                                            }

                                            // imageRect is a direct scaled representation of the
                                            // rotated bitmap â€” its width/height map 1:1 to
                                            // bitmap!!.width/height after rotation. Apply the crop
                                            // fractions directly onto the rotated bitmap's pixel
                                            // dimensions; no offset arithmetic is needed.
                                            val leftFrac   = ((cropBox!!.left   - imageRect.left) / imageRect.width ).coerceIn(0f, 1f)
                                            val topFrac    = ((cropBox!!.top    - imageRect.top ) / imageRect.height).coerceIn(0f, 1f)
                                            val rightFrac  = ((cropBox!!.right  - imageRect.left) / imageRect.width ).coerceIn(0f, 1f)
                                            val bottomFrac = ((cropBox!!.bottom - imageRect.top ) / imageRect.height).coerceIn(0f, 1f)

                                            val cropX  = (leftFrac   * bitmap!!.width ).toInt().coerceIn(0, bitmap!!.width)
                                            val cropY  = (topFrac    * bitmap!!.height).toInt().coerceIn(0, bitmap!!.height)
                                            val cropX2 = (rightFrac  * bitmap!!.width ).toInt().coerceIn(cropX, bitmap!!.width)
                                            val cropY2 = (bottomFrac * bitmap!!.height).toInt().coerceIn(cropY, bitmap!!.height)
                                            val cropW  = (cropX2 - cropX).coerceAtLeast(1)
                                            val cropH  = (cropY2 - cropY).coerceAtLeast(1)

                                            val finalCropped = Bitmap.createBitmap(bitmap!!, cropX, cropY, cropW, cropH)
                                            if (finalCropped !== bitmap) bitmap!!.recycle()

                                            finalCropped
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            null
                                        }
                                    }

                                    if (cropped != null) {
                                        viewModel.setCroppedBitmap(cropped)
                                        if (isStandaloneEdit) {
                                            viewModel.saveStagingBitmapToPrivateFile(context)
                                            withContext(Dispatchers.Main) {
                                                onStandaloneComplete()
                                            }
                                        } else {
                                            onNavigateToOutput(state.id)
                                        }
                                    } else {
                                        // Only clear the loading overlay if cropping actually failed — on
                                        // success this screen is about to be popped anyway, so leaving the
                                        // overlay up prevents a one-frame flash of the raw crop UI (grid box,
                                        // Ratio/Rotate controls) bleeding through during the pop transition.
                                        isCropping = false
                                    }
                                }
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Check, contentDescription = "Done")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                    if (activeTool == ActiveTool.NONE) {
                        Row(
                            modifier = Modifier.fillMaxSize(), 
                            horizontalArrangement = Arrangement.SpaceEvenly, 
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { viewModel.setActiveTool(ActiveTool.RATIO) }) { 
                                Text("Ratio", style = MaterialTheme.typography.titleMedium) 
                            }
                            TextButton(onClick = { viewModel.setActiveTool(ActiveTool.ROTATE) }) { 
                                Text("Rotate", style = MaterialTheme.typography.titleMedium) 
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), 
                                horizontalArrangement = Arrangement.SpaceBetween, 
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { viewModel.setActiveTool(ActiveTool.NONE) }) { 
                                    Icon(Icons.Filled.Close, contentDescription = "Close") 
                                }
                                Text(
                                    text = if (activeTool == ActiveTool.RATIO) "Aspect Ratio" else "Rotation", 
                                    style = MaterialTheme.typography.titleMedium, 
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(onClick = { viewModel.setActiveTool(ActiveTool.NONE) }) { 
                                    Icon(Icons.Filled.Check, contentDescription = "Apply") 
                                }
                            }

                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (activeTool == ActiveTool.RATIO) {
                                    RatioSelectorPanel(
                                        currentRatio = projectState?.cropRatio ?: CropRatio.RATIO_35_45, 
                                        onRatioSelected = { viewModel.updateCropRatio(it) }
                                    )
                                } else if (activeTool == ActiveTool.ROTATE) {
                                    RotationControls(
                                        currentRotation = projectState?.rotationDegrees ?: 0f,
                                        onRotate = { newRotation ->
                                            viewModel.updateRotation(newRotation)
                                            viewModel.saveCurrentState()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (projectState == null) {
                CircularProgressIndicator()
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp) // THE MAGIC FIX: PERFECT PADDING FOR BOTH RENDERING AND MATH
                        .onSizeChanged { 
                            canvasSize = Size(it.width.toFloat(), it.height.toFloat()) 
                        }
                        .graphicsLayer {
                            scaleX = animatedScale
                            scaleY = animatedScale
                            translationX = animatedPanX
                            translationY = animatedPanY
                        }
                ) {
                    // Fix: standalone edits must show the saved/flattened output image,
                    // not the original raw gallery URI which may differ from the final edit.
                    val cropDisplayUri = if (isStandaloneEdit) {
                        (projectState.stagingImagePath ?: projectState.savedImagePath ?: projectState.originalImagePath)?.let { Uri.parse(it) }
                    } else {
                        projectState.originalImagePath?.let { Uri.parse(it) }
                    }
                    AsyncImage(
                        model = cropDisplayUri,
                        contentDescription = "Selected Photo",
                        contentScale = ContentScale.Fit,
                        onSuccess = { success -> 
                            imageIntrinsicSize = success.painter.intrinsicSize 
                        },
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            val rotation = projectState.rotationDegrees
                            rotationZ = rotation
                            // Scale so the rotated image still fully COVERS the fixed
                            // imageRect (no empty corners inside the crop frame).
                            // Use the on-screen fitted size of the image — that is what
                            // this layer actually draws — and mirror the exporter exactly.
                            val dispW = imageRect.width
                            val dispH = imageRect.height
                            if (dispW > 0f && dispH > 0f) {
                                val rad = Math.toRadians(rotation.toDouble())
                                val cos = Math.abs(Math.cos(rad)).toFloat()
                                val sin = Math.abs(Math.sin(rad)).toFloat()
                                val scaleFactor = maxOf(
                                    (dispW * cos + dispH * sin) / dispW,
                                    (dispH * cos + dispW * sin) / dispH
                                ).coerceAtLeast(1f)
                                scaleX = scaleFactor
                                scaleY = scaleFactor
                            }
                        }
                    )

                    if (cropBox != null) {
                        DraggableCropOverlay(
                            ratio = projectState.cropRatio,
                            currentGrid = currentGrid,
                            cropBox = cropBox!!,
                            imageRect = imageRect,
                            onCropBoxChanged = { 
                                cropBox = it
                                viewModel.updateCropBox(it) 
                            },
                            onDragStart = { isDraggingBox = true },
                            onDragEnd = { 
                                isDraggingBox = false
                                settledCropBox = cropBox 
                            },
                            canvasSize = canvasSize,
                            currentScale = animatedScale
                        )
                    }
                }

                if (activeTool == ActiveTool.ROTATE) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp)
                            .background(Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "${projectState.rotationDegrees}°", 
                            color = Color.White, 
                            fontWeight = FontWeight.Bold, 
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            if (isCropping) {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), 
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun GridIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier = modifier.size(24.dp).padding(2.dp)) {
        val sw = 2.dp.toPx()
        drawRect(color, style = Stroke(sw))
        drawLine(color, Offset(size.width/3, 0f), Offset(size.width/3, size.height), sw)
        drawLine(color, Offset(size.width*2/3, 0f), Offset(size.width*2/3, size.height), sw)
        drawLine(color, Offset(0f, size.height/3), Offset(size.width, size.height/3), sw)
        drawLine(color, Offset(0f, size.height*2/3), Offset(size.width, size.height*2/3), sw)
    }
}

@Composable
fun RotationControls(
    currentRotation: Float,
    onRotate: (Float) -> Unit
) {
    val currentRotationState by rememberUpdatedState(currentRotation)
    val onRotateState by rememberUpdatedState(onRotate)
    var isHoldingLeft by remember { mutableStateOf(false) }
    var isHoldingRight by remember { mutableStateOf(false) }

    LaunchedEffect(isHoldingLeft) {
        if (isHoldingLeft) {
            onRotateState((currentRotationState - 0.5f).coerceIn(-180f, 180f))
            delay(500)
            while (isHoldingLeft) { 
                onRotateState((currentRotationState - 0.5f).coerceIn(-180f, 180f))
                delay(30) 
            }
        }
    }

    LaunchedEffect(isHoldingRight) {
        if (isHoldingRight) {
            onRotateState((currentRotationState + 0.5f).coerceIn(-180f, 180f))
            delay(500)
            while (isHoldingRight) { 
                onRotateState((currentRotationState + 0.5f).coerceIn(-180f, 180f))
                delay(30) 
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), 
        horizontalArrangement = Arrangement.SpaceBetween, 
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { onRotate((currentRotation - 90f).coerceIn(-180f, 180f)) }, 
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) { 
            Text("-90°") 
        }
        Box(
            modifier = Modifier.size(48.dp).pointerInput(Unit) { 
                detectTapGestures(onPress = { 
                    isHoldingLeft = true
                    tryAwaitRelease()
                    isHoldingLeft = false 
                }) 
            }, 
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PlayArrow, 
                contentDescription = "-0.5°", 
                modifier = Modifier.graphicsLayer(rotationZ = 180f), 
                tint = if (isHoldingLeft) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
        TextButton(onClick = { onRotate(0f) }, modifier = Modifier.width(80.dp)) {
            Text(
                text = "${currentRotation}°", 
                style = MaterialTheme.typography.titleMedium, 
                fontWeight = FontWeight.Bold, 
                textAlign = TextAlign.Center
            )
        }
        Box(
            modifier = Modifier.size(48.dp).pointerInput(Unit) { 
                detectTapGestures(onPress = { 
                    isHoldingRight = true
                    tryAwaitRelease()
                    isHoldingRight = false 
                }) 
            }, 
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PlayArrow, 
                contentDescription = "+0.5°", 
                tint = if (isHoldingRight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
        OutlinedButton(
            onClick = { onRotate((currentRotation + 90f).coerceIn(-180f, 180f)) }, 
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) { 
            Text("+90°") 
        }
    }
}

enum class DragHandle { 
    TOP_LEFT, TOP_CENTER, TOP_RIGHT, CENTER_LEFT, CENTER_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT, MOVE, NONE 
}

@Composable
fun DraggableCropOverlay(
    ratio: CropRatio, currentGrid: GridType, cropBox: CropBox, imageRect: Rect,
    onCropBoxChanged: (CropBox) -> Unit, onDragStart: () -> Unit, onDragEnd: () -> Unit, 
    canvasSize: Size, currentScale: Float
) {
    val density = LocalDensity.current
    var activeHandle by remember { mutableStateOf(DragHandle.NONE) }

    val ratioState by rememberUpdatedState(ratio)
    val cropBoxState by rememberUpdatedState(cropBox)
    val imageRectState by rememberUpdatedState(imageRect)
    val scaleState by rememberUpdatedState(currentScale)
    val onCropBoxChangedState by rememberUpdatedState(onCropBoxChanged)
    val onDragStartState by rememberUpdatedState(onDragStart)
    val onDragEndState by rememberUpdatedState(onDragEnd)

    val baseMinSize = with(density) { 60.dp.toPx() }
    val baseHandleRadius = with(density) { 32.dp.toPx() } 

    fun hitHandle(x: Float, y: Float, b: CropBox, currentScl: Float): DragHandle {
        val hr = baseHandleRadius / currentScl

        val onLeftEdge = abs(x - b.left) < hr
        val onRightEdge = abs(x - b.right) < hr
        val onTopEdge = abs(y - b.top) < hr
        val onBottomEdge = abs(y - b.bottom) < hr

        val withinY = y in (b.top - hr)..(b.bottom + hr)
        val withinX = x in (b.left - hr)..(b.right + hr)

        if (onLeftEdge && onTopEdge) return DragHandle.TOP_LEFT
        if (onRightEdge && onTopEdge) return DragHandle.TOP_RIGHT
        if (onLeftEdge && onBottomEdge) return DragHandle.BOTTOM_LEFT
        if (onRightEdge && onBottomEdge) return DragHandle.BOTTOM_RIGHT

        if (onLeftEdge && withinY) return DragHandle.CENTER_LEFT
        if (onRightEdge && withinY) return DragHandle.CENTER_RIGHT
        if (onTopEdge && withinX) return DragHandle.TOP_CENTER
        if (onBottomEdge && withinX) return DragHandle.BOTTOM_CENTER

        if (x > b.left + hr && x < b.right - hr && y > b.top + hr && y < b.bottom - hr) return DragHandle.MOVE

        return DragHandle.NONE
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { 
                detectDragGestures(
                    onDragStart = { offset -> 
                        activeHandle = hitHandle(offset.x, offset.y, cropBoxState, scaleState)
                        if (activeHandle != DragHandle.NONE) {
                            onDragStartState()
                        }
                    },
                    onDragEnd = { 
                        activeHandle = DragHandle.NONE
                        onDragEndState() 
                    },
                    onDragCancel = { 
                        activeHandle = DragHandle.NONE
                        onDragEndState() 
                    },
                    onDrag = { change, drag ->
                        change.consume()

                        val b = cropBoxState
                        val bounds = imageRectState
                        val currentRatio = ratioState
                        val currentMinSize = baseMinSize / scaleState

                        val dx = drag.x
                        val dy = drag.y

                        if (activeHandle == DragHandle.MOVE) {
                            val maxLeftDrag = bounds.left - b.left
                            val maxRightDrag = bounds.right - b.right

                            val clampedDx = if (maxLeftDrag <= maxRightDrag) {
                                dx.coerceIn(maxLeftDrag, maxRightDrag)
                            } else {
                                0f
                            }

                            val maxTopDrag = bounds.top - b.top
                            val maxBottomDrag = bounds.bottom - b.bottom

                            val clampedDy = if (maxTopDrag <= maxBottomDrag) {
                                dy.coerceIn(maxTopDrag, maxBottomDrag)
                            } else {
                                0f
                            }

                            val b2 = b.copy(
                                left = b.left + clampedDx, 
                                right = b.right + clampedDx, 
                                top = b.top + clampedDy, 
                                bottom = b.bottom + clampedDy
                            )
                            onCropBoxChangedState(b2)
                            return@detectDragGestures
                        }

                        var newLeft = b.left
                        var newTop = b.top
                        var newRight = b.right
                        var newBottom = b.bottom

                        when (activeHandle) {
                            DragHandle.TOP_LEFT -> { 
                                newLeft += dx
                                newTop += dy 
                            }
                            DragHandle.TOP_CENTER -> { 
                                newTop += dy 
                            }
                            DragHandle.TOP_RIGHT -> { 
                                newRight += dx
                                newTop += dy 
                            }
                            DragHandle.CENTER_LEFT -> { 
                                newLeft += dx 
                            }
                            DragHandle.CENTER_RIGHT -> { 
                                newRight += dx 
                            }
                            DragHandle.BOTTOM_LEFT -> { 
                                newLeft += dx
                                newBottom += dy 
                            }
                            DragHandle.BOTTOM_CENTER -> { 
                                newBottom += dy 
                            }
                            DragHandle.BOTTOM_RIGHT -> { 
                                newRight += dx
                                newBottom += dy 
                            }
                            else -> {}
                        }

                        if (newRight - newLeft < currentMinSize) {
                            if (activeHandle in listOf(DragHandle.TOP_LEFT, DragHandle.CENTER_LEFT, DragHandle.BOTTOM_LEFT)) {
                                newLeft = newRight - currentMinSize
                            } else {
                                newRight = newLeft + currentMinSize
                            }
                        }

                        if (newBottom - newTop < currentMinSize) {
                            if (activeHandle in listOf(DragHandle.TOP_LEFT, DragHandle.TOP_CENTER, DragHandle.TOP_RIGHT)) {
                                newTop = newBottom - currentMinSize
                            } else {
                                newBottom = newTop + currentMinSize
                            }
                        }

                        val isNotFreeCrop = currentRatio.name != "Free Crop"
                        val hasValidRatio = currentRatio.widthRatio > 0f && currentRatio.heightRatio > 0f
                        val r = if (isNotFreeCrop && hasValidRatio) {
                            currentRatio.widthRatio / currentRatio.heightRatio
                        } else {
                            0f
                        }

                        if (r > 0f) {
                            var pw = newRight - newLeft
                            var ph = newBottom - newTop

                            when (activeHandle) {
                                DragHandle.TOP_CENTER, DragHandle.BOTTOM_CENTER -> {
                                    pw = ph * r
                                }
                                DragHandle.CENTER_LEFT, DragHandle.CENTER_RIGHT -> {
                                    ph = pw / r
                                }
                                else -> { 
                                    if (abs(dx) > abs(dy)) {
                                        ph = pw / r 
                                    } else {
                                        pw = ph * r
                                    }
                                }
                            }

                            if (pw < currentMinSize) { 
                                pw = currentMinSize
                                ph = pw / r 
                            }
                            if (ph < currentMinSize) { 
                                ph = currentMinSize
                                pw = ph * r 
                            }

                            when (activeHandle) {
                                DragHandle.TOP_LEFT -> { 
                                    newLeft = b.right - pw
                                    newTop = b.bottom - ph 
                                }
                                DragHandle.TOP_CENTER -> { 
                                    newLeft = b.centerX - pw/2f
                                    newRight = b.centerX + pw/2f
                                    newTop = b.bottom - ph 
                                }
                                DragHandle.TOP_RIGHT -> { 
                                    newRight = b.left + pw
                                    newTop = b.bottom - ph 
                                }
                                DragHandle.CENTER_LEFT -> { 
                                    newLeft = b.right - pw
                                    newTop = b.centerY - ph/2f
                                    newBottom = b.centerY + ph/2f 
                                }
                                DragHandle.CENTER_RIGHT -> { 
                                    newRight = b.left + pw
                                    newTop = b.centerY - ph/2f
                                    newBottom = b.centerY + ph/2f 
                                }
                                DragHandle.BOTTOM_LEFT -> { 
                                    newLeft = b.right - pw
                                    newBottom = b.top + ph 
                                }
                                DragHandle.BOTTOM_CENTER -> { 
                                    newLeft = b.centerX - pw/2f
                                    newRight = b.centerX + pw/2f
                                    newBottom = b.top + ph 
                                }
                                DragHandle.BOTTOM_RIGHT -> { 
                                    newRight = b.left + pw
                                    newBottom = b.top + ph 
                                }
                                else -> {}
                            }
                        }

                        if (newLeft < bounds.left || newTop < bounds.top || newRight > bounds.right || newBottom > bounds.bottom) { 
                            return@detectDragGestures 
                        }

                        onCropBoxChangedState(CropBox(newLeft, newTop, newRight, newBottom))
                    }
                )
            }
    ) {
        val b = cropBox
        val sw = 1.dp.toPx() / currentScale
        val bracketLen = 18.dp.toPx() / currentScale
        val bracketStroke = Stroke(width = 3.dp.toPx() / currentScale)
        val sqHalf = 5.dp.toPx() / currentScale

        clipRect(left = b.left, top = b.top, right = b.right, bottom = b.bottom, clipOp = ClipOp.Difference) {
            drawRect(
                color = Color.Black.copy(alpha = 0.8f), 
                topLeft = Offset(-size.width, -size.height), 
                size = Size(size.width * 3, size.height * 3)
            ) 
        }

        drawRect(
            color = Color.White, 
            topLeft = Offset(b.left, b.top), 
            size = Size(b.width, b.height), 
            style = Stroke(width = 2.dp.toPx() / currentScale)
        )

        listOf(
            b.left to b.top, 
            b.right to b.top, 
            b.left to b.bottom, 
            b.right to b.bottom
        ).forEachIndexed { i, (cx, cy) ->
            val hSign = if (i % 2 == 0) 1f else -1f  
            val vSign = if (i < 2) 1f else -1f   

            val p = Path().apply { 
                moveTo(cx + hSign * bracketLen, cy)
                lineTo(cx, cy)
                lineTo(cx, cy + vSign * bracketLen) 
            }

            drawPath(p, color = Color.White, style = bracketStroke)
        }

        listOf(
            Offset(b.centerX, b.top), 
            Offset(b.centerX, b.bottom), 
            Offset(b.left, b.centerY), 
            Offset(b.right, b.centerY)
        ).forEach { pt ->
            drawRect(
                Color.White, 
                topLeft = Offset(pt.x - sqHalf, pt.y - sqHalf), 
                size = Size(sqHalf * 2, sqHalf * 2)
            )
        }

        val gridColor = Color.White.copy(alpha = 0.6f)
        if (currentGrid == GridType.CENTER_LINES) {
            drawLine(gridColor, Offset(b.centerX, b.top), Offset(b.centerX, b.bottom), sw)
            drawLine(gridColor, Offset(b.left, b.centerY), Offset(b.right, b.centerY), sw)
        }
        if (currentGrid == GridType.RULE_OF_THIRDS) {
            for (frac in listOf(1f/3, 2f/3)) {
                drawLine(gridColor, Offset(b.left + b.width * frac, b.top), Offset(b.left + b.width * frac, b.bottom), sw)
                drawLine(gridColor, Offset(b.left, b.top + b.height * frac), Offset(b.right, b.top + b.height * frac), sw)
            }
        }
        if (currentGrid == GridType.PASSPORT_GUIDE) {
            val ow = b.width * 0.6f
            val oh = b.height * 0.5f
            drawOval(
                color = gridColor, 
                topLeft = Offset(b.left + (b.width - ow) / 2, b.top + b.height * 0.15f), 
                size = Size(ow, oh), 
                style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f)))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatioSelectorPanel(currentRatio: CropRatio, onRatioSelected: (CropRatio) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), 
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(CropRatio.allPresets) { ratio ->
            FilterChip(
                selected = currentRatio.name == ratio.name, 
                onClick = { onRatioSelected(ratio) }, 
                label = { Text(ratio.name) }, 
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}