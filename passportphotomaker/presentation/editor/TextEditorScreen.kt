package com.example.passportphotomaker.presentation.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.passportphotomaker.domain.model.TextFontFamily
import com.example.passportphotomaker.domain.model.TextLayer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    viewModel: EditorViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToNext: () -> Unit,
    isStandaloneEdit: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val bitmap by viewModel.finalCroppedBitmap.collectAsState()
    LaunchedEffect(isStandaloneEdit) {
        // Standalone studio edits must use this project's persisted image. The
        // step_3 checkpoint is shared by the normal pipeline and may belong to
        // the last image that passed through BackgroundScreen.
        if (isStandaloneEdit) {
            viewModel.loadImageForStandaloneEdit(context, forceReload = true)
        } else {
            viewModel.loadTextSourceCheckpoint(context)
        }
        viewModel.loadStagedTextLayers()
    }
    val textLayers by viewModel.textLayers.collectAsState()
    val selectedId by viewModel.selectedTextLayerId.collectAsState()

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var isBaking by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf<String?>(null) } // layer id currently in a text-edit dialog

    val selectedLayer = textLayers.find { it.id == selectedId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Text") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (!isBaking) {
                                isBaking = true
                                coroutineScope.launch {
                                    if (isStandaloneEdit) {
                                        viewModel.saveStandaloneTextEdit(context)
                                    } else {
                                        viewModel.saveTextLayersForNextStep()
                                    }
                                    onNavigateToNext()
                                }
                            }
                        },
                        enabled = !isBaking
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    // Layer chips
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            AssistChip(
                                onClick = { viewModel.addTextLayer() },
                                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text("Add Text") }
                            )
                        }
                        items(textLayers, key = { it.id }) { layer ->
                            FilterChip(
                                selected = layer.id == selectedId,
                                onClick = { viewModel.selectTextLayer(layer.id) },
                                label = { Text(layer.text.take(12)) }
                            )
                        }
                    }

                    // Selected-layer controls
                    if (selectedLayer != null) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editingText = selectedLayer.id }, modifier = Modifier.weight(1f)) {
                                Text("Edit Text", maxLines = 1)
                            }
                            IconButton(onClick = { viewModel.removeTextLayer(selectedLayer.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete layer")
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text("Size: ${selectedLayer.fontSizeSp.toInt()}sp", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = selectedLayer.fontSizeSp,
                            onValueChange = { newSize ->
                                viewModel.updateTextLayer(selectedLayer.id) { it.copy(fontSizeSp = newSize) }
                            },
                            valueRange = 10f..80f
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            listOf(
                                Color.White, Color.Black, Color.Red, Color.Yellow,
                                Color(0xFF2E75CC), Color(0xFFFFD700)
                            ).forEach { c ->
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .border(
                                            width = if (selectedLayer.colorArgb == c.toArgb()) 3.dp else 1.dp,
                                            color = if (selectedLayer.colorArgb == c.toArgb()) MaterialTheme.colorScheme.primary else Color.Gray,
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            viewModel.updateTextLayer(selectedLayer.id) { it.copy(colorArgb = c.toArgb()) }
                                        }
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextFontFamily.values().forEach { family ->
                                FilterChip(
                                    selected = selectedLayer.fontFamily == family,
                                    onClick = { viewModel.updateTextLayer(selectedLayer.id) { it.copy(fontFamily = family) } },
                                    label = { Text(family.name.replace("_", " "), style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                            FilterChip(
                                selected = selectedLayer.isBold,
                                onClick = { viewModel.updateTextLayer(selectedLayer.id) { it.copy(isBold = !it.isBold) } },
                                label = { Text("Bold") }
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
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { canvasSize = it }
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )

                    textLayers.forEach { layer ->
                        val px = layer.xFrac * canvasSize.width
                        val py = layer.yFrac * canvasSize.height
                        val isSelected = layer.id == selectedId

                        Text(
                            text = layer.text,
                            color = Color(layer.colorArgb),
                            fontSize = layer.fontSizeSp.sp,
                            fontWeight = if (layer.isBold) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = when (layer.fontFamily) {
                                TextFontFamily.SANS_SERIF -> FontFamily.SansSerif
                                TextFontFamily.SERIF -> FontFamily.Serif
                                TextFontFamily.MONOSPACE -> FontFamily.Monospace
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .offset(
                                    x = with(density) { px.toDp() } - 60.dp,
                                    y = with(density) { py.toDp() } - 12.dp
                                )
                                .width(120.dp)
                                .then(
                                    if (isSelected) Modifier.border(1.dp, Color.White) else Modifier
                                )
                                .pointerInput(layer.id) {
                                    detectTapGestures(onTap = { viewModel.selectTextLayer(layer.id) })
                                }
                                .pointerInput(layer.id) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        if (canvasSize.width > 0 && canvasSize.height > 0) {
                                            viewModel.updateTextLayer(layer.id) {
                                                it.copy(
                                                    xFrac = (it.xFrac + drag.x / canvasSize.width).coerceIn(0f, 1f),
                                                    yFrac = (it.yFrac + drag.y / canvasSize.height).coerceIn(0f, 1f)
                                                )
                                            }
                                        }
                                    }
                                }
                        )
                    }
                }
            } else {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            if (isBaking) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    // Rename dialog
    editingText?.let { layerId ->
        val layer = textLayers.find { it.id == layerId }
        if (layer != null) {
            var draft by remember(layerId) { mutableStateOf(layer.text) }
            AlertDialog(
                onDismissRequest = { editingText = null },
                title = { Text("Edit Text") },
                text = {
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true)
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.updateTextLayer(layerId) { it.copy(text = draft) }
                        editingText = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { editingText = null }) { Text("Cancel") }
                }
            )
        }
    }
}