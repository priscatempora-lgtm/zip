package com.example.passportphotomaker.presentation.output_selection

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.example.passportphotomaker.domain.model.PaperSize
import com.example.passportphotomaker.domain.model.ProjectState
import com.example.passportphotomaker.domain.model.PrintBatch
import com.example.passportphotomaker.domain.util.PrintSheetGenerator
import com.example.passportphotomaker.presentation.editor.EditorViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// --- NEW: Enum for Tabbed Controls ---
enum class ControlTab { SIZE, GAP, COPIES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSizeAdjustmentScreen(
    viewModel: EditorViewModel,
    paperSizeId: String,
    seedImageUri: String? = null,
    studioProjects: List<ProjectState> = emptyList(),
    onNavigateBack: () -> Unit,
    onNavigateToPreview: (String, Int) -> Unit,
    onRestartForNewImage: () -> Unit
) {
    val context = LocalContext.current
    val activePreset by viewModel.selectedPreset.collectAsState()
    val photoBitmap by viewModel.finalCroppedBitmap.collectAsState()
    val batches by viewModel.printBatches.collectAsState()
    
    // FIX 1A: Pull the securely saved paper ID from the ViewModel
    val currentBatchPaperId by viewModel.batchPaperSizeId.collectAsState()

    val totalRamGb = remember { 
    val actManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val memInfo = android.app.ActivityManager.MemoryInfo()
    actManager.getMemoryInfo(memInfo)
    memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
    }
    var showDpiDialog by remember { mutableStateOf(false) }
    var selectedDpi by remember { mutableStateOf(300) }
    var isScaling by remember { mutableStateOf(false) }

    
    var targetPaper by remember(currentBatchPaperId) { 
        mutableStateOf(PaperSize.all.find { it.id == (currentBatchPaperId ?: paperSizeId) } ?: PaperSize.PHOTO_4X6) 
    }


    // Listen for the explicit commit signal from the editor pipeline.
    // This replaces the old eager LaunchedEffect(photoBitmap) that caused ghost
    // entries — now the batch list is only updated when the editor explicitly
    // calls commitBatchImage(), not on every recomposition.
    // NOTE: ensureCurrentBitmapInBatches is intentionally NOT called here.
    // bakeAndProceed() in BackgroundScreen already calls it directly with the
    // correct freshly-saved path. Calling it again from this collector would use
    // the stale projectState.savedImagePath (not updated for 2nd+ batch photos),
    // creating duplicate or corrupted batch entries.
    LaunchedEffect(Unit) {
        viewModel.batchEditSuccessEvent.collect { /* path committed — batch list already updated by bakeAndProceed */ }
    }
    // ── Seed image (Requirement 2 / "Print" from ProjectDetails) ─────────────
    // If opened with a seed URI, decode it and load it as the initial bitmap
    // so the screen never opens empty from the standalone path.
    LaunchedEffect(seedImageUri) {
        if (seedImageUri.isNullOrEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val uri = android.net.Uri.parse(seedImageUri)
                val bmp = if (seedImageUri.startsWith("content://") || seedImageUri.startsWith("file://")) {
                    android.graphics.ImageDecoder.decodeBitmap(
                        android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                    ) { decoder, _, _ -> decoder.isMutableRequired = true }
                } else {
                    android.graphics.BitmapFactory.decodeFile(seedImageUri)
                }
                bmp?.let {
                    viewModel.setCroppedBitmap(it)
                    // CHANGED: call ensureCurrentBitmapInBatches directly instead of
                    // going through commitBatchImage/batchEditSuccessEvent. That event's
                    // collector no longer calls ensureCurrentBitmapInBatches (removed
                    // earlier to fix a batch-mode duplicate-card bug) — this single-image
                    // "Print" flow never runs through bakeAndProceed, so it has no other
                    // path to actually populate _printBatches without this direct call.
                    val preset = viewModel.selectedPreset.value
                    val ratio = if (it.height > 0) it.width.toFloat() / it.height.toFloat()
                                else preset.widthMm / preset.heightMm
                    viewModel.ensureCurrentBitmapInBatches(seedImageUri, preset.widthMm, preset.widthMm / ratio, ratio)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    var selectedIndex by remember(batches.size) { mutableStateOf(if (batches.isNotEmpty()) batches.size - 1 else 0) }
    if (selectedIndex >= batches.size && batches.isNotEmpty()) selectedIndex = batches.size - 1

    val activeBatch = batches.getOrNull(selectedIndex)

    // --- NEW: Tab State ---
    var currentTab by remember { mutableStateOf(ControlTab.SIZE) }

    // ── "Add Another Image" dialog state (Requirement 3) ─────────────────────
    var showAddImageDialog     by remember { mutableStateOf(false) }
    var showStudioPickerDialog by remember { mutableStateOf(false) }

    val layoutPlan = remember(batches, targetPaper) { PrintSheetGenerator.buildLayoutPlan(targetPaper, batches) }

    val maxAvailableCopies = remember(batches, selectedIndex, targetPaper) {
        if (batches.isEmpty() || selectedIndex !in batches.indices) return@remember 1
        val testBatches = batches.toMutableList()
        testBatches[selectedIndex] = testBatches[selectedIndex].copy(copies = 500) 
        PrintSheetGenerator.buildLayoutPlan(targetPaper, testBatches).count { it.batchIndex == selectedIndex }
    }

    // Small on-screen-only bitmap cache for the live layout preview. Keyed by
    // imagePath and rebuilt whenever the batch list changes — NOT tied to
    // PrintBatch's lifetime, so it never grows unbounded the way the old
    // eager PrintBatch.bitmap field did. Canvas/DrawScope can't await Coil
    // mid-draw, so this loads ahead of time via the same ImageLoader Coil
    // uses everywhere else in the app.
    val previewBitmaps = remember { mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap>() }

    LaunchedEffect(batches) {
        val currentPaths = batches.map { it.imagePath }.toSet()
        // Drop cached bitmaps for images that were removed from the sheet
        previewBitmaps.keys.retainAll(currentPaths)

        batches.map { it.imagePath }.distinct().forEach { path ->
            if (previewBitmaps.containsKey(path)) return@forEach
            val request = ImageRequest.Builder(context)
                .data(path)
                .size(256, 256)
                .allowHardware(false) // need a software bitmap to read into Compose's ImageBitmap
                .build()
            val result = context.imageLoader.execute(request)
            (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let {
                previewBitmaps[path] = it.asImageBitmap()
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            
            // 1. Securely lock in the batch paper size
            viewModel.setBatchMode(targetPaper.id)
            
            // 2. We will handle the ghost images safely inside this function instead!
            viewModel.resetForNewBatchImage(uri.toString())
            
            onRestartForNewImage()
        }
    }

    // ── Source-chooser dialog ─────────────────────────────────────────────────
    if (showAddImageDialog) {
        AlertDialog(
            onDismissRequest = { showAddImageDialog = false },
            title = { Text("Add Another Image") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Action A — Choose from Studio: observes the shared repo Flow
                    OutlinedButton(
                        onClick  = { showStudioPickerDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Choose from Studio") }
                    // Action B — New Photo from Gallery: starts full edit pipeline
                    Button(
                        onClick = {
                            showAddImageDialog = false
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("New Photo from Gallery") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddImageDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ── Studio project picker dialog ──────────────────────────────────────────
    // Observes the exact same shared Flow as HomeScreen (Requirement 2).
    if (showStudioPickerDialog) {
        AlertDialog(
            onDismissRequest = { showStudioPickerDialog = false },
            title = { Text("Choose from Studio") },
            text = {
                if (studioProjects.isEmpty()) {
                    Text(
                        "No saved projects yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier            = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(studioProjects) { project ->
                            val imageSource = project.savedImagePath ?: project.originalImagePath
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (imageSource != null) {
                                            // Appends directly to the layout — skips the edit pipeline
                                            viewModel.addBitmapFromUri(context, imageSource)
                                        }
                                        showStudioPickerDialog = false
                                        showAddImageDialog     = false
                                    }
                                    .padding(8.dp),
                                verticalAlignment   = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Image(
                                    painter            = rememberAsyncImagePainter(imageSource),
                                    contentDescription = null,
                                    modifier           = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentScale = ContentScale.Crop
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text  = "Project ${project.id.take(8)}…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text  = project.projectType.name
                                            .lowercase()
                                            .replaceFirstChar { it.uppercaseChar() }
                                            .let { if (it == "Unknown") "In progress" else it },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showStudioPickerDialog = false }) { Text("Back") }
            }
        )
    }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Layout Config") }, 
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Filled.ArrowBack, null) } },
                actions = {
                    // Batch indicator pill — only visible when 2+ images are queued.
                    // Gives the user immediate awareness of how many photos are on the
                    // sheet without opening the thumbnail carousel.
                    if (batches.size > 1) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                text = "${batches.size} Images",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    // Guides toggle
                    if (activeBatch != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                            Text("Guides", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 4.dp))
                            Switch(
                                checked = activeBatch.drawGuides, 
                                onCheckedChange = { viewModel.updateBatch(selectedIndex, activeBatch.copy(drawGuides = it)) },
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                    }
                }
            ) 
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
                    OutlinedButton(
                        onClick  = { showAddImageDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("+ Add Another Image To This Sheet", fontWeight = FontWeight.Bold) }
                    Spacer(modifier = Modifier.height(8.dp))
                           Button(
                onClick = { 
                    viewModel.setBatchMode(targetPaper.id)
                    showDpiDialog = true // <-- Shows dialog instead of navigating instantly
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Finish & Generate Print File", fontWeight = FontWeight.Bold)
            }
                }
            }
        }
        ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Sheet Size:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    items(PaperSize.all) { paper ->
                        FilterChip(
                            selected = targetPaper.id == paper.id,
                            onClick = { 
                                targetPaper = paper 
                                // SYNC WITH VIEWMODEL: Update the global state so 
                                // the rest of the app knows the paper size changed!
                                viewModel.setBatchMode(paper.id) 
                            },
                            label = { Text(paper.name) }
                        )
                    }
                }
            }
            // --- TOP: LIVE CANVAS (Now using weight(1f) to grab maximum vertical space!) ---
            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFFE5E5E5)).padding(16.dp), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.fillMaxHeight().aspectRatio(targetPaper.widthMm / targetPaper.heightMm).background(Color.White).border(1.dp, Color.LightGray)) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val scale = size.width / targetPaper.widthMm
                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

                        for (slot in layoutPlan) {
                            val batch = batches[slot.batchIndex]
                            val xPx = slot.xMm * scale; val yPx = slot.yMm * scale
                            val wPx = slot.wMm * scale; val hPx = slot.hMm * scale
                            val isSelected = slot.batchIndex == selectedIndex
                            
                            if (batch.drawGuides) {
                                drawRect(color = if (isSelected) Color.Blue else Color.Gray, topLeft = Offset(xPx, yPx), size = Size(wPx, hPx), style = Stroke(width = if (isSelected) 2.dp.toPx() else 1.dp.toPx(), pathEffect = dashEffect))
                            }
                            drawRect(color = Color.Black, topLeft = Offset(xPx + 1f * scale, yPx + 1f * scale), size = Size(wPx - 2f * scale, hPx - 2f * scale), style = Stroke(1.dp.toPx()))

                            // Compute the largest aspect-ratio-correct rect that fits
                            // inside the slot's inner area (2-px inset on each side).
                            val slotL = xPx + 2f * scale; val slotT = yPx + 2f * scale
                            val slotW = wPx - 4f * scale;  val slotH = hPx - 4f * scale
                            val bmpAspect  = batch.aspectRatio   // stored on PrintBatch — no bitmap needed for layout math
                            val slotAspect = slotW / slotH
                            val drawW: Float
                            val drawH: Float
                            if (bmpAspect > slotAspect) {
                                drawW = slotW; drawH = slotW / bmpAspect
                            } else {
                                drawH = slotH; drawW = slotH * bmpAspect
                            }
                            val drawL = slotL + (slotW - drawW) / 2f
                            val drawT = slotT + (slotH - drawH) / 2f

                            previewBitmaps[batch.imagePath]?.let { img ->
                                drawImage(
                                    image     = img,
                                    dstOffset = IntOffset(drawL.toInt(), drawT.toInt()),
                                    dstSize   = IntSize(drawW.toInt(), drawH.toInt())
                                )
                            }
                        }
                    }
                }
            }

            // --- MIDDLE: THUMBNAIL CAROUSEL ---
            if (batches.isNotEmpty()) {
                LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(batches) { index, batch ->
                        Box(modifier = Modifier.size(64.dp)) {
                            Box(modifier = Modifier.size(56.dp).align(Alignment.BottomStart).border(width = if (selectedIndex == index) 3.dp else 1.dp, color = if (selectedIndex == index) MaterialTheme.colorScheme.primary else Color.Gray, shape = RoundedCornerShape(8.dp)).clickable { selectedIndex = index }) {
                                AsyncImage(
                                    model = batch.imagePath,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            
                            // --- FIX 2: Replaced bulky IconButton with precise clickable Box for the Red Cross ---
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .background(Color.Red, CircleShape)
                                    .clickable { viewModel.removeBatch(index) },
                                contentAlignment = Alignment.Center
                            ) { 
                                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(12.dp)) 
                            }
                        }
                    }
                }
            }

            // --- BOTTOM: TABBED ACTIVE BATCH CONTROLS ---
            if (activeBatch != null) {
                // Uses wrapContentHeight to shrink to fit just the active tab
                Column(modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    
                    // --- FIX 1: The Parallel Tab Row ---
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { currentTab = ControlTab.SIZE }) { Text("Size", fontWeight = if (currentTab == ControlTab.SIZE) FontWeight.Bold else FontWeight.Normal) }
                        TextButton(onClick = { currentTab = ControlTab.GAP }) { Text("Gap", fontWeight = if (currentTab == ControlTab.GAP) FontWeight.Bold else FontWeight.Normal) }
                        TextButton(onClick = { currentTab = ControlTab.COPIES }) { Text("Copies", fontWeight = if (currentTab == ControlTab.COPIES) FontWeight.Bold else FontWeight.Normal) }
                    }
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                                        // Tab Content Switching
                    when (currentTab) {
                        ControlTab.SIZE -> {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Cut Size:", fontWeight = FontWeight.Medium)
                                Text("${activeBatch.widthMm.roundToInt()} x ${activeBatch.heightMm.roundToInt()} mm", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            val maxSliderW = minOf(targetPaper.widthMm - 12f, (targetPaper.heightMm - 12f) * activeBatch.aspectRatio)

                            // Locked to 48.dp height
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                                Text("W:", fontWeight = FontWeight.Medium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { viewModel.updateBatch(selectedIndex, activeBatch.copy(widthMm = activeBatch.widthMm - 1f, heightMm = (activeBatch.widthMm - 1f) / activeBatch.aspectRatio)) }) { Text("-") }
                                    Slider(
                                        value = activeBatch.widthMm.coerceIn(20f, maxSliderW),
                                        onValueChange = { viewModel.updateBatch(selectedIndex, activeBatch.copy(widthMm = it, heightMm = it / activeBatch.aspectRatio)) },
                                        valueRange = 20f..maxSliderW,
                                        modifier = Modifier.width(160.dp)
                                    )
                                    IconButton(onClick = { viewModel.updateBatch(selectedIndex, activeBatch.copy(widthMm = activeBatch.widthMm + 1f, heightMm = (activeBatch.widthMm + 1f) / activeBatch.aspectRatio)) }) { Icon(Icons.Filled.Add, null) }
                                }
                            }
                        }
                        
                        ControlTab.GAP -> {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Border Gap:", fontWeight = FontWeight.Medium)
                                Text("${activeBatch.gapMm.roundToInt()} mm", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Locked to 48.dp height
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                                Text("Gap:", fontWeight = FontWeight.Medium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { 
                                        viewModel.updateBatch(selectedIndex, activeBatch.copy(gapMm = (activeBatch.gapMm - 1f).coerceAtLeast(0f))) 
                                    }) { Text("-") }
                                    
                                    Slider(
                                        value = activeBatch.gapMm,
                                        onValueChange = { viewModel.updateBatch(selectedIndex, activeBatch.copy(gapMm = it)) },
                                        valueRange = 0f..20f,
                                        modifier = Modifier.width(160.dp)
                                    )
                                    
                                    IconButton(onClick = { 
                                        viewModel.updateBatch(selectedIndex, activeBatch.copy(gapMm = (activeBatch.gapMm + 1f).coerceAtMost(20f))) 
                                    }) { Icon(Icons.Filled.Add, null) }
                                }
                            }
                        }
                        
                        ControlTab.COPIES -> {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Copies to Print:", fontWeight = FontWeight.Medium)
                                Text("Grid Capacity: $maxAvailableCopies", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            // FIX 1: Reduced spacer from 16.dp down to 8.dp to match the other tabs perfectly
                            Spacer(modifier = Modifier.height(8.dp)) 
                            
                            // FIX 2: Locked the Row to 48.dp height so it perfectly mimics the Slider rows
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().height(48.dp), 
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item { 
                                    FilterChip(
                                        selected = activeBatch.copies == maxAvailableCopies, 
                                        onClick = { viewModel.updateBatch(selectedIndex, activeBatch.copy(copies = maxAvailableCopies)) }, 
                                        label = { Text("Fill Max ($maxAvailableCopies)") }
                                    ) 
                                }
                                items(count = maxAvailableCopies) { index ->
                                    val copies = index + 1
                                    FilterChip(
                                        selected = activeBatch.copies == copies, 
                                        onClick = { viewModel.updateBatch(selectedIndex, activeBatch.copy(copies = copies)) }, 
                                        label = { Text("$copies") }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1.1f).fillMaxSize(), contentAlignment = Alignment.Center) { Text("No images added. Add an image to begin.") }
            }
        }
    }
 if (showDpiDialog) {
        val dpiOptions = listOf(300 to "300 DPI", 350 to "350 DPI", 450 to "450 DPI", 600 to "600 DPI", 1080 to "HD (1080p)")
        AlertDialog(
            onDismissRequest = { showDpiDialog = false },
            title = { Text("Select Output Resolution") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dpiOptions.forEach { (dpi, label) ->
                        FilterChip(
                            selected = selectedDpi == dpi,
                            onClick = { selectedDpi = dpi },
                            label = { Text(label) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        var actualTargetDpi = selectedDpi
                        // FIX 3 IN ACTION: Changed 5.5 to 3.8 to allow 6GB phones to print 1080p!
                        if (actualTargetDpi == 1080 && totalRamGb <= 3.8) {
                            actualTargetDpi = 600
                            android.widget.Toast.makeText(context, "Low RAM! Optimizing to 600 DPI.", android.widget.Toast.LENGTH_LONG).show()
                        }
                        showDpiDialog = false
                        onNavigateToPreview(targetPaper.id, actualTargetDpi) // Pass dynamic DPI to Preview!
                    }
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showDpiDialog = false }) { Text("Cancel") } }
        )
    }
}
