package com.example.passportphotomaker.presentation.home

import com.example.passportphotomaker.domain.model.ProjectType

import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FaceRetouchingNatural
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import coil.compose.rememberAsyncImagePainter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import kotlin.math.roundToInt

// -----------------------------------------------------------------------------
// Helper functions
// -----------------------------------------------------------------------------

fun getDeviceTotalRamGb(context: Context): Double {
    val actManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    val memInfo = ActivityManager.MemoryInfo()
    actManager.getMemoryInfo(memInfo)

    return memInfo.totalMem.toDouble() /
            (1024.0 * 1024.0 * 1024.0)
}

private fun gcd(a: Int, b: Int): Int {
    return if (b == 0) a else gcd(b, a % b)
}

// -----------------------------------------------------------------------------
// Project Details Screen
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailsScreen(
    viewModel: ProjectDetailsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToCrop: () -> Unit,
    onNavigateToRetouch: () -> Unit,
    onNavigateToBackground: () -> Unit,
    onNavigateToText:  () -> Unit,
    onNavigateToExport: (imageUri: String, dpi: Int) -> Unit
) {
    val project by viewModel.project.collectAsState()
    val context = LocalContext.current

    // Calculate RAM only once.
    val totalRamGb = remember {
        getDeviceTotalRamGb(context)
    }

    // -------------------------------------------------------------------------
    // Dialog state
    // -------------------------------------------------------------------------

    var showDpiDialog by remember {
        mutableStateOf(false)
    }

    var selectedDpi by remember {
        mutableStateOf(300)
    }

    // -------------------------------------------------------------------------
    // Leaving / image freezing
    // -------------------------------------------------------------------------

    var isLeaving by remember {
        mutableStateOf(false)
    }

    var frozenImageSource by remember {
        mutableStateOf<String?>(null)
    }

    val handleBack: () -> Unit = {
        frozenImageSource =
            project?.stagingImagePath
                ?: project?.savedImagePath
                ?: project?.originalImagePath

        isLeaving = true

        onNavigateBack()
    }

    BackHandler(
        onBack = handleBack
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("My Studio")
                },
                navigationIcon = {
                    IconButton(
                        onClick = handleBack
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->

        val proj = project

        // ---------------------------------------------------------------------
        // Loading state
        // ---------------------------------------------------------------------

        if (proj == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            return@Scaffold
        }

        // ---------------------------------------------------------------------
        // Current image source
        // ---------------------------------------------------------------------

        val imageSource =
            if (isLeaving) {
                frozenImageSource
            } else {
                proj.stagingImagePath
                    ?: proj.savedImagePath
                    ?: proj.originalImagePath
            }

        // ---------------------------------------------------------------------
        // Read actual image dimensions
        // ---------------------------------------------------------------------

        var actualPixelSize by remember(imageSource) {
            mutableStateOf<Pair<Int, Int>?>(null)
        }

        LaunchedEffect(imageSource) {
            actualPixelSize = null

            val path = imageSource
                ?: return@LaunchedEffect

            withContext(Dispatchers.IO) {
                try {
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }

                    if (
                        path.startsWith("content://") ||
                        path.startsWith("file://")
                    ) {
                        context.contentResolver
                            .openInputStream(Uri.parse(path))
                            ?.use { inputStream ->
                                BitmapFactory.decodeStream(
                                    inputStream,
                                    null,
                                    options
                                )
                            }
                    } else {
                        BitmapFactory.decodeFile(
                            path,
                            options
                        )
                    }

                    if (
                        options.outWidth > 0 &&
                        options.outHeight > 0
                    ) {
                        withContext(Dispatchers.Main) {
                            actualPixelSize =
                                options.outWidth to options.outHeight
                        }
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // ---------------------------------------------------------------------
        // Main content
        // ---------------------------------------------------------------------

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {

            // -----------------------------------------------------------------
            // Image Preview
            // -----------------------------------------------------------------

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (imageSource != null) {
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = imageSource
                        ),
                        contentDescription = "Project image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = "No image available",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // -----------------------------------------------------------------
            // Metadata Card
            // -----------------------------------------------------------------

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 16.dp,
                        vertical = 8.dp
                    ),
                colors = CardDefaults.cardColors(
                    containerColor =
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {

                    Text(
                        text = "Image Info",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    val pixels = actualPixelSize

                    val aspectLabel = pixels?.let { (width, height) ->
                        val g = gcd(width, height)

                        "${width / g}:${height / g}"
                    }

                    MetadataRow(
                        label = "Pixels",
                        value = pixels?.let {
                            "${it.first} × ${it.second} px"
                        } ?: "Loading…"
                    )

                    MetadataRow(
                        label = "Aspect ratio",
                        value = aspectLabel ?: "—"
                    )

                    if (
                        proj.projectType != ProjectType.UNKNOWN &&
                        proj.stagingImagePath == null
                    ) {
                        val w = proj.cropRatio.widthRatio
                        val h = proj.cropRatio.heightRatio
                        MetadataRow(
                            label = "Size",
                            value = if (w > 0f && h > 0f) {
                                "${w.roundToInt()} × ${h.roundToInt()} mm"
                            } else {
                                "Free crop"
                            }
                        )
                    } else {
                        MetadataRow(
                            label = "Size",
                            value = "Set at export"
                        )
                    }
                }
            }

            // -----------------------------------------------------------------
            // Action Buttons
            // -----------------------------------------------------------------

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 12.dp,
                        vertical = 4.dp
                    ),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {

                StudioActionButton(
                    icon = Icons.Filled.Crop,
                    label = "Crop",
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToCrop
                )

                StudioActionButton(
                    icon = Icons.Filled.FaceRetouchingNatural,
                    label = "Retouch",
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToRetouch
                )

                StudioActionButton(
                    icon = Icons.Filled.Image,
                    label = "Bg",
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToBackground
                )
                
                StudioActionButton(
                    icon = Icons.Filled.TextFields,
                    label = "Text",
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToText
                )

                StudioActionButton(
                    icon = Icons.Filled.Download,
                    label = "Export",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showDpiDialog = true
                    }
                )
            }

            Spacer(
                modifier = Modifier.height(8.dp)
            )
        }

        // ---------------------------------------------------------------------
        // DPI Selection Dialog
        // ---------------------------------------------------------------------

        if (showDpiDialog) {

            val dpiOptions = listOf(
                300 to "300 DPI",
                350 to "350 DPI",
                450 to "450 DPI",
                600 to "600 DPI",
                1080 to "HD (1080p)"
            )

            AlertDialog(
                onDismissRequest = {
                    showDpiDialog = false
                },

                title = {
                    Text("Select Output Resolution")
                },

                text = {
                    Column(
                        verticalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {

                        Text(
                            text =
                                "Higher DPI produces a sharper image " +
                                "but a larger file size.",
                            style =
                                MaterialTheme.typography.bodySmall
                        )

                        Spacer(
                            modifier = Modifier.height(4.dp)
                        )

                        dpiOptions.forEach { (dpi, label) ->

                            FilterChip(
                                selected = selectedDpi == dpi,

                                onClick = {
                                    selectedDpi = dpi
                                },

                                label = {
                                    Text(label)
                                },

                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                },

                confirmButton = {

                    TextButton(
                        onClick = {

                            var actualTargetDpi = selectedDpi

                            // 1080 is treated as a high-resolution output
                            // and is downgraded on low-RAM devices.
                            if (
                                actualTargetDpi == 1080 &&
                                totalRamGb <= 3.8
                            ) {
                                actualTargetDpi = 600

                                Toast.makeText(
                                    context,
                                    "Low RAM! Optimizing to 600 DPI.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            showDpiDialog = false

                            val uri =
                                proj.stagingImagePath
                                    ?: proj.savedImagePath
                                    ?: proj.originalImagePath

                            if (uri != null) {
                                onNavigateToExport(
                                    uri,
                                    actualTargetDpi
                                )
                            }
                        }
                    ) {
                        Text(
                            text = "Continue",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },

                dismissButton = {

                    TextButton(
                        onClick = {
                            showDpiDialog = false
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Metadata Row
// -----------------------------------------------------------------------------

@Composable
private fun MetadataRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),

        horizontalArrangement =
            Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

// -----------------------------------------------------------------------------
// Studio Action Button
// -----------------------------------------------------------------------------

@Composable
private fun StudioActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,

        modifier = modifier,

        contentPadding = PaddingValues(
            horizontal = 2.dp,
            vertical = 12.dp
        ),

        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp)
            )

            Spacer(
                modifier = Modifier.height(4.dp)
            )

            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}