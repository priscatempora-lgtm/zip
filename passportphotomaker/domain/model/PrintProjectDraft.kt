package com.example.passportphotomaker.domain.model

import java.util.UUID

/**
 * A paused, resumable multi-image print layout session. Created when the user
 * exits PhotoSizeAdjustmentScreen mid-batch and chooses "Save & Exit". Deleted
 * automatically once that batch's sheet is successfully exported.
 *
 * id matches ProjectState.batchGroupId for every photo that was part of this
 * session — that shared id is the join key used to re-fetch the member photos.
 */
data class PrintProjectDraft(
    val id: String = UUID.randomUUID().toString(),
    val paperSizeId: String,
    val batches: List<PrintBatch>,
    val updatedAt: Long = System.currentTimeMillis()
)