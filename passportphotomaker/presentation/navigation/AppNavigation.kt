package com.example.passportphotomaker.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import androidx.navigation.navArgument
import com.example.myempty.passportphotoapp.PassportPhotoApp
import com.example.passportphotomaker.domain.model.ProjectType
import com.example.passportphotomaker.presentation.home.HomeScreen
import com.example.passportphotomaker.presentation.home.HomeViewModel
import com.example.passportphotomaker.presentation.home.ProjectDetailsScreen
import com.example.passportphotomaker.presentation.home.ProjectDetailsViewModel
import com.example.passportphotomaker.presentation.editor.CropAlignScreen
import com.example.passportphotomaker.presentation.editor.RetouchScreen
import com.example.passportphotomaker.presentation.editor.BackgroundScreen
import com.example.passportphotomaker.presentation.editor.EditorViewModel
import com.example.passportphotomaker.presentation.output_selection.OutputSelectionScreen
import com.example.passportphotomaker.presentation.output_selection.PrintPreviewScreen
import com.example.passportphotomaker.presentation.output_selection.PhotoSizeAdjustmentScreen
import com.example.passportphotomaker.presentation.editor.TextEditorScreen
object Routes {
    const val HOME                  = "home"
    const val PROJECT_DETAILS       = "project_details"
    const val EDITOR_FLOW           = "editor_flow"
    const val CROP_ALIGN            = "crop_align"
    const val RETOUCH               = "retouch"
    const val BACKGROUND            = "background"
    const val TEXT_EDITOR           = "text_editor"
    const val OUTPUT_SELECTION      = "output_selection"
    const val PHOTO_SIZE_ADJUSTMENT = "photo_size_adjustment"
    const val PRINT_PREVIEW         = "print_preview"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context       = LocalContext.current
    val appContainer  = (context.applicationContext as PassportPhotoApp).container

    // â”€â”€ Shared studio projects (Requirement 2) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // HomeScreen and PhotoSizeAdjustmentScreen both observe this exact same
    // Flow from the single repository instance â€” guaranteed shared data.
    val studioProjects by appContainer.projectRepository
    .getAllProjects()
    .map { list -> list.filter { it.projectType != ProjectType.UNKNOWN } }
    .collectAsState(initial = emptyList())

    NavHost(navController = navController, startDestination = Routes.HOME) {

        // â”€â”€ 1. HOME â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        composable(Routes.HOME) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = HomeViewModel.Factory(appContainer.projectRepository, appContainer.printProjectRepository)
            )
            HomeScreen(
                viewModel                = homeViewModel,
                onNavigateToCrop         = { projectId ->
                    // FAB "New Photo" → enter the full edit pipeline
                    navController.navigate("${Routes.EDITOR_FLOW}/$projectId")
                },
                onNavigateToProjectDetails = { projectId ->
                    // "My Studio" card tap → hub & spoke details screen
                    navController.navigate("${Routes.PROJECT_DETAILS}/$projectId")
                },
                onResumeDraft = { projectId, draftId ->
                    // "Recent Print Projects" tile tap → resume a paused batch session
                    navController.navigate("${Routes.PHOTO_SIZE_ADJUSTMENT}/$projectId/resume/$draftId")
                }
            )
        }

        // â”€â”€ 2. MY STUDIO HUB â€” Project Details â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        composable(
            route = "${Routes.PROJECT_DETAILS}/{projectId}",
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId")
                ?: return@composable
            val detailsViewModel: ProjectDetailsViewModel = viewModel(
                factory = ProjectDetailsViewModel.Factory(
                    appContainer.projectRepository, projectId,
                    (context.applicationContext as PassportPhotoApp).appScope
                )
            )

            val handleLeaveStudio: () -> Unit = {
                navController.popBackStack()
                detailsViewModel.discardStagedEdits()
            }
            // BackHandler removed from here — moved inside ProjectDetailsScreen
            // itself so hardware back shares the same freeze-state as the UI arrow

            ProjectDetailsScreen(
                viewModel              = detailsViewModel,
                onNavigateBack         = handleLeaveStudio,
                // Each action navigates INTO the editor_flow nested graph with
                // isStandaloneEdit=true so "Done" pops straight back here.
                onNavigateToCrop       = {
                    navController.navigate(
                        "${Routes.CROP_ALIGN}/$projectId?isStandaloneEdit=true"
                    )
                },
                onNavigateToRetouch    = {
                    navController.navigate(
                        "${Routes.RETOUCH}/$projectId?isStandaloneEdit=true"
                    )
                },
                onNavigateToBackground = {
                    navController.navigate(
                        "${Routes.BACKGROUND}/$projectId?isStandaloneEdit=true"
                    )
                },
                onNavigateToText       = {
                    navController.navigate(
                        "${Routes.TEXT_EDITOR}/$projectId?isStandaloneEdit=true"
                    )
                },
                onNavigateToExport = { imageUri, dpi ->
                    val encoded = android.net.Uri.encode(imageUri)
                    navController.navigate(
                        "${Routes.OUTPUT_SELECTION}/$projectId?seedImageUri=$encoded&targetDpi=$dpi"
                    )
                }
            )
        }

                // ── 3. EDITOR WORKFLOW (nested graph) ────────────────────────────────
        navigation(
            startDestination = "${Routes.CROP_ALIGN}/{projectId}",
            route            = "${Routes.EDITOR_FLOW}/{projectId}"
        ) {

            // SCREEN A ── Crop & Align ────────────────────────────────────────
            composable(
                route = "${Routes.CROP_ALIGN}/{projectId}?isStandaloneEdit={isStandaloneEdit}",
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType },
                    navArgument("isStandaloneEdit") {
                        type = NavType.BoolType; defaultValue = false
                    }
                )
            ) { backStackEntry ->
                val isStandaloneEdit = backStackEntry.arguments
                    ?.getBoolean("isStandaloneEdit") ?: false
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("${Routes.EDITOR_FLOW}/{projectId}")
                }
                val projectId = parentEntry.arguments?.getString("projectId")
                    ?: return@composable
                val editorViewModel: EditorViewModel = viewModel(
                    parentEntry,
                    factory = EditorViewModel.Factory(appContainer.projectRepository, projectId)
                )
                val batchPaperId by editorViewModel.batchPaperSizeId.collectAsState()

                CropAlignScreen(
                    viewModel             = editorViewModel,
                    onNavigateBack        = { navController.popBackStack() },
                    onNavigateToOutput    = { id ->
                        navController.navigate("${Routes.RETOUCH}/$id")
                    },
                    isStandaloneEdit      = isStandaloneEdit,
                    onStandaloneComplete  = {
                        navController.popBackStack()
                    },
                    onEscapeBatchMode  = {
                        // FIX: Safely drop the editor pipeline to reveal Layout Config beneath it!
                        navController.popBackStack(
                            route = "${Routes.CROP_ALIGN}/{projectId}?isStandaloneEdit={isStandaloneEdit}",
                            inclusive = true
                        )
                    }
                )
            }

            // SCREEN B — Retouch 
            composable(
                route = "${Routes.RETOUCH}/{projectId}?isStandaloneEdit={isStandaloneEdit}",
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType },
                    navArgument("isStandaloneEdit") {
                        type = NavType.BoolType; defaultValue = false
                    }
                )
            ) { backStackEntry ->
                val isStandaloneEdit = backStackEntry.arguments?.getBoolean("isStandaloneEdit") ?: false
                val parentEntry = remember(backStackEntry) { navController.getBackStackEntry("${Routes.EDITOR_FLOW}/{projectId}") }
                val projectId = parentEntry.arguments?.getString("projectId") ?: return@composable
                val editorViewModel: EditorViewModel = viewModel(parentEntry, factory = EditorViewModel.Factory(appContainer.projectRepository, projectId))
                val batchPaperId by editorViewModel.batchPaperSizeId.collectAsState()

                RetouchScreen(
                    viewModel          = editorViewModel,
                    onNavigateBack     = { navController.popBackStack() },
                    onNavigateToNext   = {
                        if (isStandaloneEdit) {
                            navController.popBackStack("${Routes.PROJECT_DETAILS}/$projectId", inclusive = false)
                        } else {
                            navController.navigate("${Routes.BACKGROUND}/$projectId")
                        }
                    },
                    isStandaloneEdit   = isStandaloneEdit,
                    onEscapeBatchMode  = {
                        navController.popBackStack(route = "${Routes.CROP_ALIGN}/{projectId}?isStandaloneEdit={isStandaloneEdit}", inclusive = true)
                    }
                )
            }

            // SCREEN C ── Background ──────────────────────────────────────────
            composable(
                route = "${Routes.BACKGROUND}/{projectId}?isStandaloneEdit={isStandaloneEdit}",
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType },
                    navArgument("isStandaloneEdit") { type = NavType.BoolType; defaultValue = false }
                )
            ) { backStackEntry ->
                val isStandaloneEdit = backStackEntry.arguments?.getBoolean("isStandaloneEdit") ?: false
                val parentEntry = remember(backStackEntry) { navController.getBackStackEntry("${Routes.EDITOR_FLOW}/{projectId}") }
                val projectId   = parentEntry.arguments?.getString("projectId") ?: return@composable
                val editorViewModel: EditorViewModel = viewModel(parentEntry, factory = EditorViewModel.Factory(appContainer.projectRepository, projectId))
                val batchPaperId by editorViewModel.batchPaperSizeId.collectAsState()

                // 🔥 THE BULLETPROOF SYSTEM BACK CATCHER 🔥
                // This explicitly intercepts the physical phone swipe to ensure 
                // it perfectly mimics the UI Back arrow and restores the pristine image!
                androidx.activity.compose.BackHandler {
                    editorViewModel.restoreRetouchCheckpoint(context)
                    navController.popBackStack()
                }

                BackgroundScreen(
                    viewModel          = editorViewModel,
                    onNavigateBack     = {
                        editorViewModel.restoreRetouchCheckpoint(context)
                        navController.popBackStack()
                    },
                    onNavigateToNext   = {
                        if (isStandaloneEdit) {
                            navController.popBackStack(
                                "${Routes.PROJECT_DETAILS}/$projectId",
                                inclusive = false
                            )
                        } else {
                            // CHANGED: route through Text before Output Selection / batch
                            // loop-back, instead of jumping straight there.
                            navController.navigate("${Routes.TEXT_EDITOR}/$projectId")
                        }
                    },
                    isStandaloneEdit   = isStandaloneEdit,
                    onEscapeBatchMode  = {
                        navController.popBackStack(
                            route = "${Routes.CROP_ALIGN}/{projectId}?isStandaloneEdit={isStandaloneEdit}",
                            inclusive = true
                        )
                    }
                )
            }
            // SCREEN D ── Textscreen ──────────────────────────────────────────
            composable(
    route = "${Routes.TEXT_EDITOR}/{projectId}?isStandaloneEdit={isStandaloneEdit}",
    arguments = listOf(
        navArgument("projectId") { type = NavType.StringType },
        navArgument("isStandaloneEdit") {
            type = NavType.BoolType
            defaultValue = false
        }
    )
) { backStackEntry ->

    val coroutineScope = rememberCoroutineScope()

    val isStandaloneEdit =
        backStackEntry.arguments?.getBoolean("isStandaloneEdit") ?: false

    val parentEntry = remember(backStackEntry) {
        navController.getBackStackEntry("${Routes.EDITOR_FLOW}/{projectId}")
    }

    val projectId =
        parentEntry.arguments?.getString("projectId")
            ?: return@composable

    val editorViewModel: EditorViewModel = viewModel(
        parentEntry,
        factory = EditorViewModel.Factory(
            appContainer.projectRepository,
            projectId
        )
    )

    val batchPaperId by editorViewModel.batchPaperSizeId.collectAsState()

    TextEditorScreen(
        viewModel = editorViewModel,
        onNavigateBack = {
            navController.popBackStack()
        },
        onNavigateToNext = {
            coroutineScope.launch {
                if (isStandaloneEdit) {
                    navController.popBackStack(
                        "${Routes.PROJECT_DETAILS}/$projectId",
                        inclusive = false
                    )
                } else if (batchPaperId != null) {
                    runCatching {
                        editorViewModel.bakeTextForBatch(context)
                            ?: editorViewModel.projectState.value?.savedImagePath
                    }.onFailure { it.printStackTrace() }

                    /*
                     * Batch editing always enters Text through:
                     * Layout -> Crop -> Retouch -> Background -> Text.
                     *
                     * Do not use popBackStack(route = "...{arg}...") here.
                     * That string is a destination pattern, not the concrete
                     * back-stack entry, and Navigation can throw when it
                     * cannot resolve the placeholder route. Popping the known
                     * entries is deterministic and returns to Layout Config.
                     */
                    repeat(4) {
                        if (!navController.popBackStack()) return@launch
                    }
                } else {
                    editorViewModel.projectState.value?.savedImagePath?.let {
                        editorViewModel.commitBatchImage(it)
                    }

                    navController.navigate(
                        "${Routes.OUTPUT_SELECTION}/$projectId"
                    )
                }
            }
        },
        isStandaloneEdit = isStandaloneEdit
    )
}
            // SCREEN E ── Output Selection ──────────────────────────────────────────
            composable(
                // 1. Add parameters to the route
                route = "${Routes.OUTPUT_SELECTION}/{projectId}?seedImageUri={seedImageUri}&targetDpi={targetDpi}",
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType },
                    navArgument("seedImageUri") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("targetDpi") { type = NavType.IntType; defaultValue = 300 }
                )
            ) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("${Routes.EDITOR_FLOW}/{projectId}")
                }
                val projectId = parentEntry.arguments?.getString("projectId") ?: return@composable
                
                // 2. Extract the URI and DPI
                val rawSeedUri = backStackEntry.arguments?.getString("seedImageUri")
                val seedImageUri = rawSeedUri?.let { android.net.Uri.decode(it) }
                val targetDpi = backStackEntry.arguments?.getInt("targetDpi") ?: 300

                val editorViewModel: EditorViewModel = viewModel(
                    parentEntry,
                    factory = EditorViewModel.Factory(appContainer.projectRepository, projectId)
                )
                
                OutputSelectionScreen(
                    viewModel             = editorViewModel,
                    seedImageUri          = seedImageUri, // 3. Pass it to the screen
                    targetDpi             = targetDpi,    // 4. Pass it to the screen
                    onNavigateBack        = { navController.popBackStack() },
                    onPrintLayoutSelected = { paperId, resolvedUri ->
                        if (!resolvedUri.isNullOrEmpty()) {
                            val encoded = android.net.Uri.encode(resolvedUri)
                            navController.navigate(
                                "${Routes.PHOTO_SIZE_ADJUSTMENT}/$projectId/$paperId?seedImageUri=$encoded&isStandaloneEdit=true"
                            )
                        } else {
                            navController.navigate(
                                "${Routes.PHOTO_SIZE_ADJUSTMENT}/$projectId/$paperId"
                            )
                        }
                    },
                    onSaveComplete = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.HOME) { inclusive = true }
                        }
                    }
                )
            }

            // SCREEN F — Layout Config (Photo Size Adjustment) ─────────────────────
            composable(
                route = "${Routes.PHOTO_SIZE_ADJUSTMENT}/{projectId}/{paperId}" +
                    "?seedImageUri={seedImageUri}&isStandaloneEdit={isStandaloneEdit}&targetDpi={targetDpi}",
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType },
                    navArgument("paperId")   { type = NavType.StringType },
                    navArgument("seedImageUri") {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    },
                    navArgument("isStandaloneEdit") {
                        type = NavType.BoolType; defaultValue = false
                    },
                    navArgument("targetDpi") { type = NavType.IntType; defaultValue = 300 }
                )
            ) { backStackEntry ->
                val paperId          = backStackEntry.arguments?.getString("paperId") ?: "photo_4x6"
                val rawSeedUri       = backStackEntry.arguments?.getString("seedImageUri")
                val seedImageUri     = rawSeedUri?.let { android.net.Uri.decode(it) }
                val isStandaloneEdit = backStackEntry.arguments
                    ?.getBoolean("isStandaloneEdit") ?: false
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("${Routes.EDITOR_FLOW}/{projectId}")
                }
                val projectId = parentEntry.arguments?.getString("projectId")
                    ?: return@composable
                val editorViewModel: EditorViewModel = viewModel(
                    parentEntry,
                    factory = EditorViewModel.Factory(appContainer.projectRepository, projectId)
                )
                val targetDpi = backStackEntry.arguments?.getInt("targetDpi") ?: 300

                // ↓↓↓ everything from here down is NEW — replaces the old onNavigateBack lambda ↓↓↓
                var showExitDialog by remember { mutableStateOf(false) }
                val printBatches by editorViewModel.printBatches.collectAsState()

                val handleBackNavigation: () -> Unit = {
                    if (printBatches.size > 1) showExitDialog = true
                    else if (isStandaloneEdit) navController.popBackStack()
                    else navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
                }

                androidx.activity.compose.BackHandler(onBack = handleBackNavigation)

                if (showExitDialog) {
                    AlertDialog(
                        onDismissRequest = { showExitDialog = false },
                        title = { Text("Save this print layout?") },
                        text = { Text("You have ${printBatches.size} photos in this sheet. Save your progress to resume later, or discard it.") },
                        confirmButton = {
                            TextButton(onClick = {
                                editorViewModel.pauseBatchSession(context, editorViewModel.batchPaperSizeId.value ?: paperId, appContainer.printProjectRepository)
                                showExitDialog = false
                                if (isStandaloneEdit) navController.popBackStack()
                                else navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
                            }) { Text("Save & Exit") }
                        },
                        dismissButton = {
                            Row {
                                TextButton(onClick = { showExitDialog = false }) { Text("Cancel") }
                                TextButton(onClick = {
                                    showExitDialog = false
                                    if (isStandaloneEdit) navController.popBackStack()
                                    else navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
                                }) { Text("Discard & Exit") }
                            }
                        }
                    )
                }

                PhotoSizeAdjustmentScreen(
                    viewModel            = editorViewModel,
                    paperSizeId          = paperId,
                    seedImageUri         = seedImageUri,
                    studioProjects       = studioProjects,
                    onNavigateBack       = handleBackNavigation,
                    onNavigateToPreview  = { selectedPaperId, selectedDpi -> 
                        navController.navigate(
                            "${Routes.PRINT_PREVIEW}/$projectId/$selectedPaperId?targetDpi=$selectedDpi"
                        ) 
                    },
                    onRestartForNewImage = {
                        navController.navigate("${Routes.CROP_ALIGN}/$projectId")
                    }
                )
            }

              // SCREEN G — Print Preview
            composable(
                route = "${Routes.PRINT_PREVIEW}/{projectId}/{paperId}?targetDpi={targetDpi}",
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType },
                    navArgument("paperId") { type = NavType.StringType },
                    navArgument("targetDpi") { type = NavType.IntType; defaultValue = 300 }
                )
            ) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("${Routes.EDITOR_FLOW}/{projectId}")
                }
                val paperId   = backStackEntry.arguments?.getString("paperId") ?: "photo_4x6"
                val projectId = parentEntry.arguments?.getString("projectId")
                    ?: return@composable
                val targetDpi = backStackEntry.arguments?.getInt("targetDpi") ?: 300

                val editorViewModel: EditorViewModel = viewModel(
                    parentEntry,
                    factory = EditorViewModel.Factory(appContainer.projectRepository, projectId)
                )
                PrintPreviewScreen(
                    viewModel        = editorViewModel,
                    paperSizeId      = paperId,
                    targetDpi        = targetDpi,
                    onNavigateBack   = { navController.popBackStack() },
                    onExportComplete = {
                        editorViewModel.clearBatches(appContainer.printProjectRepository)
                        navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
                    }
                )
            }

            // SCREEN H — Resume a paused batch draft
            composable(
                route = "${Routes.PHOTO_SIZE_ADJUSTMENT}/{projectId}/resume/{draftId}",
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType },
                    navArgument("draftId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val parentEntry = remember(backStackEntry) { navController.getBackStackEntry("${Routes.EDITOR_FLOW}/{projectId}") }
                val projectId = parentEntry.arguments?.getString("projectId") ?: return@composable
                val draftId = backStackEntry.arguments?.getString("draftId") ?: return@composable
                val editorViewModel: EditorViewModel = viewModel(parentEntry, factory = EditorViewModel.Factory(appContainer.projectRepository, projectId))
                val currentBatchPaperId by editorViewModel.batchPaperSizeId.collectAsState()
                val printBatches by editorViewModel.printBatches.collectAsState()
                var showExitDialog by remember { mutableStateOf(false) }

                LaunchedEffect(draftId) {
                    appContainer.printProjectRepository.getDraftById(draftId)?.let { editorViewModel.resumeBatchSession(it) }
                }

                val handleBackNavigation: () -> Unit = {
                    if (printBatches.size > 1) showExitDialog = true
                    else navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
                }
                androidx.activity.compose.BackHandler(onBack = handleBackNavigation)

                if (showExitDialog) {
                    AlertDialog(
                        onDismissRequest = { showExitDialog = false },
                        title = { Text("Save this print layout?") },
                        text = { Text("You have ${printBatches.size} photos in this sheet. Save your progress to resume later, or discard it.") },
                        confirmButton = {
                            TextButton(onClick = {
                                editorViewModel.pauseBatchSession(context, currentBatchPaperId ?: "photo_4x6", appContainer.printProjectRepository)
                                showExitDialog = false
                                navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
                            }) { Text("Save & Exit") }
                        },
                        dismissButton = {
                            Row {
                                TextButton(onClick = { showExitDialog = false }) { Text("Cancel") }
                                TextButton(onClick = {
                                    showExitDialog = false
                                    navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
                                }) { Text("Discard & Exit") }
                            }
                        }
                    )
                }

                if (currentBatchPaperId == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    PhotoSizeAdjustmentScreen(
                        viewModel = editorViewModel,
                        paperSizeId = currentBatchPaperId!!,
                        studioProjects = studioProjects,
                        onNavigateBack = handleBackNavigation,
                        onNavigateToPreview = { selectedPaperId, selectedDpi -> navController.navigate("${Routes.PRINT_PREVIEW}/$projectId/$selectedPaperId?targetDpi=$selectedDpi") },
                        onRestartForNewImage = { navController.navigate("${Routes.CROP_ALIGN}/$projectId") }
                    )
                }
            }
        }
    }
}