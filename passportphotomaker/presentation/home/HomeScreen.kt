package com.example.passportphotomaker.presentation.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.example.passportphotomaker.domain.model.PrintProjectDraft
import com.example.passportphotomaker.domain.model.ProjectState
import com.example.passportphotomaker.domain.model.ProjectType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToCrop: (String) -> Unit,
    onNavigateToProjectDetails: (String) -> Unit,
    onResumeDraft: (projectId: String, draftId: String) -> Unit
) {
    val recentProjects by viewModel.recentProjects.collectAsState()
    val recentDrafts   by viewModel.recentDrafts.collectAsState()

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.createNewProject(it.toString()) { projectId ->
                onNavigateToCrop(projectId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Passport Photo Maker") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { galleryLauncher.launch("image/*") },
                icon = { Icon(Icons.Filled.Add, contentDescription = "New Photo") },
                text = { Text("New Photo") }
            )
        }
    ) { paddingValues ->
        // Single LazyColumn for the full body avoids nested-scroll conflicts.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 88.dp), // FAB clearance
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ── Recent Print Projects (resumable drafts) ───────────────────────
            if (recentDrafts.isNotEmpty()) {
                item(key = "drafts_header") {
                    Text(
                        text = "Recent Print Projects",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(recentDrafts, key = { it.id }) { draft ->
                    DraftCard(
                        draft = draft,
                        onClick = {
                            val newProjectId = UUID.randomUUID().toString()
                            onResumeDraft(newProjectId, draft.id)
                        },
                        onDeleteAll = {
                            viewModel.deleteDraft(draft, alsoDeleteImages = true)
                        },
                        onDeleteKeepPhotos = {
                            viewModel.deleteDraft(draft, alsoDeleteImages = false)
                        }
                    )
                }
                item(key = "drafts_spacer") {
                    Spacer(Modifier.height(16.dp))
                }
            }

            // ── History ────────────────────────────────────────────────────────
            item(key = "history_header") {
                Text(
                    text = "History",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (recentProjects.isEmpty()) {
                item(key = "history_empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No recent projects. Tap 'New Photo' to start.")
                    }
                }
            } else {
                items(recentProjects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onClick = { onNavigateToProjectDetails(project.id) },
                        onDelete = { viewModel.deleteHistoryItem(project) }
                    )
                }
            }
        }
    }
}

// ── ProjectCard ────────────────────────────────────────────────────────────────

@Composable
fun ProjectCard(
    project: ProjectState,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Thumbnail — loads from the saved gallery image if available, otherwise
            // falls back to the original source URI picked from the gallery.
            val thumbnailSource = project.savedImagePath ?: project.originalImagePath
            if (thumbnailSource != null) {
                Image(
                    painter = rememberAsyncImagePainter(thumbnailSource),
                    contentDescription = "Project thumbnail",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            // Project info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Project ${project.id.take(8)}…",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = formatTimestamp(project.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Project-type badge — only shown once the project has been completed.
            // DIGITAL: the user saved a digital copy.
            // PRINT:   the user entered the print layout screen (auto-save fired).
            if (project.projectType != ProjectType.UNKNOWN) {
                val (badgeColor, textColor, label) = when (project.projectType) {
                    ProjectType.DIGITAL -> Triple(
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                        "Digital"
                    )
                    ProjectType.PRINT -> Triple(
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer,
                        "Print"
                    )
                    ProjectType.UNKNOWN -> Triple(Color.Transparent, Color.Transparent, "")
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = badgeColor
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Delete button
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete project",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this photo?") },
            text = { Text("This will permanently remove the photo and its saved files.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ── DraftCard ──────────────────────────────────────────────────────────────────

@Composable
fun DraftCard(
    draft: PrintProjectDraft,
    onClick: () -> Unit,
    onDeleteAll: () -> Unit,
    onDeleteKeepPhotos: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Thumbnail — first batch photo, or a placeholder box if no images yet.
            val firstImagePath = draft.batches.firstOrNull()?.imagePath
            if (firstImagePath != null) {
                Image(
                    painter = rememberAsyncImagePainter(firstImagePath),
                    contentDescription = "Draft thumbnail",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            // Draft info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${draft.batches.size} photo${if (draft.batches.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = draft.paperSizeId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatTimestamp(draft.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // "In Progress" badge
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = "In Progress",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }

            // Delete button
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete draft",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Delete confirmation dialog — three actions
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this print project?") },
            text = { Text("You can delete just the print layout, or also delete all ${draft.batches.size} photos in this project.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteAll()
                    }
                ) { Text("Delete Everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showDeleteDialog = false
                            onDeleteKeepPhotos()
                        }
                    ) { Text("Keep Photos") }
                    TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
                }
            }
        )
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
