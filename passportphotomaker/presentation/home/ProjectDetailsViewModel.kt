package com.example.passportphotomaker.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.passportphotomaker.domain.model.ProjectState
import com.example.passportphotomaker.domain.repository.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProjectDetailsViewModel(
    private val repository: ProjectRepository,
    private val projectId: String,
    private val appScope: CoroutineScope
) : ViewModel() {

    /**
     * Observes the single project by ID from the shared repository flow.
     * Automatically reflects any updates made by EditorViewModel (e.g. after a
     * standalone edit saves a new savedImagePath back to the repository).
     */
    val project: StateFlow<ProjectState?> = repository.getAllProjects()
        .map { list -> list.find { it.id == projectId } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    /**
     * Discards a staged (unexported) edit when the user leaves My Studio.
     * Runs on appScope, NOT viewModelScope — this ViewModel is destroyed the
     * instant navController.popBackStack() removes its NavBackStackEntry,
     * which cancels viewModelScope immediately. Using appScope (tied to the
     * Application, not this ViewModel) guarantees the repository write
     * actually completes instead of being cancelled mid-flight.
     */
    fun discardStagedEdits() {
        appScope.launch(Dispatchers.IO) {
            val current = project.value ?: return@launch
            current.stagingImagePath?.let { stagingPath ->
                runCatching {
                    java.io.File(stagingPath).takeIf { it.exists() }?.delete()
                }
            }
            val cleared = current.copy(
                stagingImagePath = null,
                stagingCropRatio = null,
                stagingTextLayers = null
            )
            repository.saveProject(cleared)
        }
    }

    class Factory(
        private val repository: ProjectRepository,
        private val projectId: String,
        private val appScope: CoroutineScope
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProjectDetailsViewModel(repository, projectId, appScope) as T
    }
}