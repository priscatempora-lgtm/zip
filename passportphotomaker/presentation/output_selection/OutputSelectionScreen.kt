package com.example.passportphotomaker.presentation.output_selection

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.passportphotomaker.domain.model.PaperSize
import com.example.passportphotomaker.domain.util.ImageExporter
import com.example.passportphotomaker.presentation.editor.EditorViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputSelectionScreen(
    viewModel: EditorViewModel,
    seedImageUri: String? = null,
    targetDpi: Int = 300,
    onNavigateBack: () -> Unit,
    onPrintLayoutSelected: (String, String?) -> Unit,
    /**
     * Called after a digital copy saves successfully.  The caller (AppNavigation) clears
     * the editor backstack so pressing "Back" after save goes straight to HomeScreen
     * instead of returning to the (now stale) editing pipeline.
     */
    onSaveComplete: () -> Unit = {}
) {
    val context = LocalContext.current
    var printReadyImagePath by remember { mutableStateOf<String?>(null) }
    // Resolve even when the standard pipeline has no seed URI. Otherwise the
    // user can reach Print Layout before the text-flattened first-image path is
    // ready and the raw path gets seeded into the first batch slot.
    var isResolvingPrintReadyImage by remember { mutableStateOf(true) }
    
    // ── RESOLVE THE FINAL IMAGE FOR EXPORT ──
    // Text is editable metadata until this point. resolvePrintReadyImagePath()
    // creates a flattened file when needed, which is also the correct seed for
    // the first image in a print layout.
    LaunchedEffect(seedImageUri) {
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (!seedImageUri.isNullOrEmpty()) {
                    val uri = android.net.Uri.parse(seedImageUri)
                    val bmp = if (seedImageUri.startsWith("content://") || seedImageUri.startsWith("file://")) {
                        android.graphics.ImageDecoder.decodeBitmap(
                            android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                        ) { decoder, _, _ -> decoder.isMutableRequired = true }
                    } else {
                        android.graphics.BitmapFactory.decodeFile(seedImageUri)
                    }
                    bmp?.let {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            viewModel.setCroppedBitmap(it)
                        }
                        viewModel.scaleBitmapToDpi(targetDpi)
                    }
                }

                printReadyImagePath = viewModel.resolvePrintReadyImagePath(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isResolvingPrintReadyImage = false
        }
    }

    val finalBitmap by viewModel.finalCroppedBitmap.collectAsState()
    // Fix 2: observe isSaving from ViewModel so the dialog stays up until ALL
    // IO (gallery write + private file + Room insert) has finished on the IO thread.
    val isSaving by viewModel.isSaving.collectAsState()

    var isPrintOptionsExpanded by remember { mutableStateOf(false) }
    
    // CUSTOM EXPORT CONFIGURATION STATES
    var selectedFormat by remember { mutableStateOf(ImageExporter.ExportFormat.JPG) }
    var enableSizeLimit by remember { mutableStateOf(false) }
    var targetSizeSliderValue by remember { mutableStateOf(100f) } // Default 100 KB target choice

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Output Format") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                
                Text(
                    text = "How would you like to save this photo?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // CARD 1: EXPORT DIGITAL COPY
                OutputOptionCard(
                    title = "Save Digital Copy",
                    description = "Export a single photo customized for online visa applications, electronic registration files, and official e-forms.",
                    icon = Icons.Filled.Share,
                    isExpanded = !isPrintOptionsExpanded,
                    onClick = { isPrintOptionsExpanded = false },
                    actionContent = {
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            
                            // FORMAT CHIP SELECTOR ROW
                            Text("Select Target Extension File Format:", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ImageExporter.ExportFormat.values().forEach { format ->
                                    FilterChip(
                                        selected = selectedFormat == format,
                                        onClick = { selectedFormat = format },
                                        label = { Text(format.name) }
                                    )
                                }
                            }

                            // TARGET FILE SIZE LIMIT SECTION
                            if (selectedFormat == ImageExporter.ExportFormat.JPG || selectedFormat == ImageExporter.ExportFormat.WEBP) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Checkbox(checked = enableSizeLimit, onCheckedChange = { enableSizeLimit = it })
                                    Text("Apply Custom Compressed Size Target (KB Limit)", style = MaterialTheme.typography.bodyMedium)
                                }

                                if (enableSizeLimit) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                        Text(
                                            text = "Maximum File Weight Limit: ${targetSizeSliderValue.roundToInt()} KB",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Slider(
                                            value = targetSizeSliderValue,
                                            onValueChange = { targetSizeSliderValue = it },
                                            valueRange = 20f..500f,
                                            steps = 48
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    finalBitmap?.let { bitmap ->
                                        // Fix 2: delegate to ViewModel which runs ALL IO
                                        // (gallery write + private file + Room insert) strictly
                                        // on Dispatchers.IO before switching to Main for navigation.
                                        val sizeLimit = if (
                                            enableSizeLimit &&
                                            (selectedFormat == ImageExporter.ExportFormat.JPG ||
                                             selectedFormat == ImageExporter.ExportFormat.WEBP)
                                        ) targetSizeSliderValue.roundToInt() else null

                                        viewModel.saveDigitalCopy(
                                            context    = context,
                                            bitmap     = bitmap,
                                            format     = selectedFormat,
                                            sizeLimit  = sizeLimit,
                                            onSuccess  = {
                                                Toast.makeText(context, "Saved successfully to public storage!", Toast.LENGTH_LONG).show()
                                                onSaveComplete()
                                            },
                                            onFailure  = {
                                                Toast.makeText(context, "Failed to save file configuration asset parameters.", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                },
                                enabled = !isSaving && !isResolvingPrintReadyImage && finalBitmap != null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (isSaving) "Compiling Asset Parameters..." else "Execute Export Save")
                            }
                        }
                    }
                )

                // CARD 2: PRINT SHEET SELECTOR
                OutputOptionCard(
                    title = "Create Print Layout",
                    description = "Tile multiple copies of your photo onto a standard sheet for incredibly cheap commercial printing.",
                    icon = Icons.Filled.List,
                    isExpanded = isPrintOptionsExpanded,
                    onClick = { isPrintOptionsExpanded = true },
                    actionContent = {
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                // Pass a default ID to satisfy the NavHost. 
                                // The user can now change this dynamically on the next screen!
                                 onClick = {
                                     onPrintLayoutSelected(
                                         PaperSize.PHOTO_4X6.id,
                                         printReadyImagePath ?: seedImageUri
                                     )
                                 },
                                 enabled = !isResolvingPrintReadyImage && printReadyImagePath != null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                 Text(
                                     if (isResolvingPrintReadyImage) "Preparing image…"
                                     else "Proceed to Print Layout",
                                     fontWeight = FontWeight.Bold
                                 )
                            }
                        }
                    }
                )
            }

        if (isSaving) {
            AlertDialog(
                onDismissRequest = { /* non-dismissible while saving */ },
                title = { Text("Saving...") },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Saving high-resolution image...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {}
            )
        }
        }
    }
}

@Composable
fun OutputOptionCard(
    title: String,
    description: String,
    icon: ImageVector,
    isExpanded: Boolean,
    onClick: () -> Unit,
    actionContent: @Composable () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isExpanded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp).padding(end = 8.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                Box(modifier = Modifier.padding(top = 8.dp)) { actionContent() }
            }
        }
    }
}
