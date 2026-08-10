package com.example.passportphotomaker.domain.repository

import com.example.passportphotomaker.domain.model.ProjectState
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    fun getAllProjects(): Flow<List<ProjectState>>
    suspend fun getProjectById(id: String): ProjectState?
    suspend fun saveProject(projectState: ProjectState)
    suspend fun deleteProject(projectState: ProjectState)
    suspend fun clearAllStagingEdits()
}
