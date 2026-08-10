package com.example.passportphotomaker.data.repository

import android.content.Context
import com.example.passportphotomaker.domain.model.ProjectState
import com.example.passportphotomaker.domain.repository.ProjectRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProjectRepositoryImpl(context: Context) : ProjectRepository {
    private val prefs = context.getSharedPreferences("passport_projects_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // Holds the state in memory and updates the UI automatically
    private val _projectsFlow = MutableStateFlow<List<ProjectState>>(emptyList())

    init {
        loadProjects()
    }

    private fun loadProjects() {
        val json = prefs.getString("projects_list", "[]")
        val type = object : TypeToken<List<ProjectState>>() {}.type
        val projects: List<ProjectState> = gson.fromJson(json, type) ?: emptyList()
        _projectsFlow.value = projects.sortedByDescending { it.timestamp }
    }

    override fun getAllProjects(): Flow<List<ProjectState>> {
        return _projectsFlow.asStateFlow()
    }

    override suspend fun getProjectById(id: String): ProjectState? {
        return _projectsFlow.value.find { it.id == id }
    }

    override suspend fun saveProject(projectState: ProjectState) {
        val currentList = _projectsFlow.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.id == projectState.id }
        
        if (existingIndex >= 0) {
            currentList[existingIndex] = projectState // Update existing
        } else {
            currentList.add(projectState) // Add new
        }
        
        saveAndEmit(currentList)
    }

    override suspend fun deleteProject(projectState: ProjectState) {
        val currentList = _projectsFlow.value.toMutableList()
        currentList.removeAll { it.id == projectState.id }
        saveAndEmit(currentList)
    }
    
    override suspend fun clearAllStagingEdits() {
        val currentList = _projectsFlow.value
        val hasAnyStaging = currentList.any {
            it.stagingImagePath != null || it.stagingTextLayers != null
        }
        if (!hasAnyStaging) return
        currentList.forEach { p ->
            p.stagingImagePath?.let { path ->
                runCatching { java.io.File(path).takeIf { it.exists() }?.delete() }
            }
        }
        val cleared = currentList.map {
            it.copy(
                stagingImagePath = null,
                stagingCropRatio = null,
                stagingTextLayers = null
            )
        }
        saveAndEmit(cleared)
    }

    private fun saveAndEmit(list: List<ProjectState>) {
        val sortedList = list.sortedByDescending { it.timestamp }
        val json = gson.toJson(sortedList)
        prefs.edit().putString("projects_list", json).apply()
        _projectsFlow.value = sortedList
    }
}
