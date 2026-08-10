package com.example.passportphotomaker.presentation.output_selection

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.passportphotomaker.domain.model.PaperSize
import com.example.passportphotomaker.domain.util.ImageExporter
import com.example.passportphotomaker.domain.util.PrintSheetGenerator
import com.example.passportphotomaker.presentation.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintPreviewScreen(
    viewModel: EditorViewModel,
    paperSizeId: String,
    targetDpi: Int = 300,
    onNavigateBack: () -> Unit,
    onExportComplete: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val batches by viewModel.printBatches.collectAsState()
    
    val selectedPaper = remember(paperSizeId) { PaperSize.all.find { it.id == paperSizeId } ?: PaperSize.PHOTO_4X6 }

    // 1. Maintain TWO states to bypass the OpenGL Texture limit
    var generatedSheet by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var previewSheet by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(batches, selectedPaper) {
        if (batches.isNotEmpty()) {
            val sheet = withContext(Dispatchers.Default) {
                PrintSheetGenerator.generateMultiBatchSheet(
                    paperSize = selectedPaper,
                    batches = batches,
                    targetDpi = targetDpi,
                    context = context
                )
            }
            
            if (sheet != null) {
                generatedSheet = sheet // The massive file for saving
                
                // 2. Safely downscale the preview for the UI
                previewSheet = withContext(Dispatchers.Default) {
                    val maxDim = 2048f // Ultra-safe texture limit for all budget phones
                    val scale = minOf(maxDim / sheet.width, maxDim / sheet.height, 1f)
                    if (scale < 1f) {
                        android.graphics.Bitmap.createScaledBitmap(
                            sheet, 
                            (sheet.width * scale).toInt(), 
                            (sheet.height * scale).toInt(), 
                            true
                        )
                    } else {
                        sheet // Image is already small enough
                    }
                }
            } else {
                // Safety fallback if generating the high-res file actually caused an OOM
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Out of Memory! Try a lower resolution.", Toast.LENGTH_LONG).show()
                    onNavigateBack()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Print Sheet Preview") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(
                        onClick = {
                            generatedSheet?.let { sheet ->
                                isSaving = true
                                coroutineScope.launch {
                                    val success = withContext(Dispatchers.IO) {
                                        // Save using the MASSIVE file, not the preview
                                        ImageExporter.saveDigitalPhotoToGallery(context, sheet, ImageExporter.ExportFormat.JPG, null)
                                    }
                                    isSaving = false
                                    if (success) {
                                    viewModel.publishPrintExport()
                                    Toast.makeText(context, "Print sheet saved!", Toast.LENGTH_LONG).show()
                                    onExportComplete()
                                  } else Toast.makeText(context, "Failed to save print.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = generatedSheet != null && !isSaving
                    ) { Icon(Icons.Filled.Check, contentDescription = "Save Sheet Layout") }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).background(Color(0xFF1E1E1E)), contentAlignment = Alignment.Center) {
            
            // 3. UI now waits for the 'previewSheet' to be ready
            if (previewSheet == null) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Card(modifier = Modifier.padding(bottom = 16.dp)) { Text("Format Target: ${selectedPaper.name} Layout", modifier = Modifier.padding(16.dp, 8.dp), fontWeight = FontWeight.Bold) }
                    
                    // 4. Render the safe, scaled-down preview to bypass the GPU crash
                    Image(bitmap = previewSheet!!.asImageBitmap(), contentDescription = "Layout", contentScale = ContentScale.Fit, modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp))
                    
                    Text("Ready to save high-res file for printing.", color = Color.Gray, modifier = Modifier.padding(top = 16.dp), textAlign = TextAlign.Center)
                }
            }
            if (isSaving) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}
