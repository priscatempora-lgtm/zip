package com.example.passportphotomaker.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.passportphotomaker.domain.model.PrintProjectDraft
import com.example.passportphotomaker.domain.model.ProjectState
import com.example.passportphotomaker.domain.model.ProjectType
import com.example.passportphotomaker.domain.repository.PrintProjectRepository
import com.example.passportphotomaker.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: ProjectRepository,
    private val draftRepository: PrintProjectRepository
) : ViewModel() {

    // Automatically fetches and updates the UI when the database changes
    // Draft/in-progress sessions (projectType == UNKNOWN) are hidden until the
    // user completes at least one export — prevents unfinished projects from
    // polluting "Recent Projects" the moment a gallery image is picked.
    val recentProjects: StateFlow<List<ProjectState>> = repository.getAllProjects()
        .map { list -> list.filter { it.projectType != ProjectType.UNKNOWN } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val recentDrafts: StateFlow<List<PrintProjectDraft>> = draftRepository.getAllDrafts()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun createNewProject(imageUri: String, onProjectCreated: (String) -> Unit) {
        viewModelScope.launch {
            val newProject = ProjectState(originalImagePath = imageUri)
            repository.saveProject(newProject)
            // Pass the new project ID back to navigate
            onProjectCreated(newProject.id)
        }
    }

    fun deleteHistoryItem(project: ProjectState) {
        viewModelScope.launch {
            project.savedImagePath?.let { path ->
                val file = java.io.File(path)
                if (file.exists()) file.delete()
            }
            repository.deleteProject(project)
        }
    }

    fun deleteDraft(draft: PrintProjectDraft, alsoDeleteImages: Boolean) {
        viewModelScope.launch {
            if (alsoDeleteImages) {
                val members = repository.getAllProjects().first().filter { it.batchGroupId == draft.id }
                members.forEach { m ->
                    m.savedImagePath?.let { path ->
                        val file = java.io.File(path)
                        if (file.exists()) file.delete()
                    }
                    repository.deleteProject(m)
                }
            }
            draftRepository.deleteDraft(draft.id)
        }
    }

    // Factory to inject the repository manually
    class Factory(
        private val repository: ProjectRepository,
        private val draftRepository: PrintProjectRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository, draftRepository) as T
        }
    }
}