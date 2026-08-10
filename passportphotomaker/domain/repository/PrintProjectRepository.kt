// domain/repository/PrintProjectRepository.kt
package com.example.passportphotomaker.domain.repository

import com.example.passportphotomaker.domain.model.PrintProjectDraft
import kotlinx.coroutines.flow.Flow

interface PrintProjectRepository {
    fun getAllDrafts(): Flow<List<PrintProjectDraft>>
    suspend fun getDraftById(id: String): PrintProjectDraft?
    suspend fun saveDraft(draft: PrintProjectDraft)
    suspend fun deleteDraft(id: String)
}