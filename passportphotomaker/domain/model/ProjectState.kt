package com.example.passportphotomaker.domain.model

import com.example.passportphotomaker.domain.model.TextLayer
import java.util.UUID

/**
 * Discriminates the final output path chosen for a project.
 *
 * UNKNOWN  — project was created but never completed.
 * DIGITAL  — user chose "Save Digital Copy" on OutputSelectionScreen.
 * PRINT    — user chose "Create Print Layout" and entered LayoutConfigScreen.
 *
 * Stored as its name string by Gson so old SharedPreferences entries without
 * this field default to UNKNOWN at deserialisation time.
 */
enum class ProjectType { UNKNOWN, DIGITAL, PRINT }

data class ProjectState(
    val id: String = UUID.randomUUID().toString(),
    val originalImagePath: String? = null,
    val cropRatio: CropRatio = CropRatio.RATIO_35_45,
    val rotationDegrees: Float = 0f,
    val isPerspectiveCorrected: Boolean = false,
    val selectedPaperSize: PaperSize = PaperSize.ISO_A4,
    val horizontalGapMm: Float = 2f,
    val verticalGapMm: Float = 2f,
    val pageMarginMm: Float = 5f,
    val borderThicknessMm: Float = 0.25f,
    val whiteGapMm: Float = 0f,
    val showCuttingGuides: Boolean = false,
    val showRegistrationMarks: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),

    /**
     * Identifies how this project was completed. Set by [EditorViewModel.updateProjectType]
     * after the user either saves a digital copy or enters the print layout screen.
     * Drives the "Recent Projects" card badge and (future) deep-link routing from HomeScreen.
     */
    val projectType: ProjectType = ProjectType.UNKNOWN,

    /**
     * MediaStore URI string of the image that was auto-saved to the gallery when the user
     * entered LayoutConfigScreen (PRINT path) or explicitly saved a digital copy (DIGITAL path).
     * Null until a save has succeeded. Used as the thumbnail source for the RecentProjects list
     * when [originalImagePath] has been revoked by the system.
     */
    val savedImagePath: String? = null,
    val batchGroupId: String? = null,

    /**
     * Working copy written by standalone Crop/Retouch/Bg tools' "Next" action.
     * Lets successive standalone-tool sessions chain edits together (each tool screen is a separate
     * navigation into editor_flow, so the EditorViewModel instance doesn't survive between them —
     * this disk file is the hand-off).
     * NEVER read by History/HomeScreen — only savedImagePath is "published."
     */
    val stagingImagePath: String? = null,

    /**
     * Crop ratio belonging to [stagingImagePath]. Kept separate from the
     * published ratio so leaving My Studio can discard an unexported edit
     * without changing the existing project's metadata.
     */
    val stagingCropRatio: CropRatio? = null,
    val stagingTextLayers: List<TextLayer>? = null
)