package com.example.passportphotomaker.data.repository

import android.content.Context
import com.example.passportphotomaker.domain.model.PrintProjectDraft
import com.example.passportphotomaker.domain.repository.PrintProjectRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PrintProjectRepositoryImpl(context: Context) : PrintProjectRepository {
    private val prefs = context.getSharedPreferences("passport_print_drafts_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val _draftsFlow = MutableStateFlow<List<PrintProjectDraft>>(emptyList())

    init { loadDrafts() }

    private fun loadDrafts() {
        val json = prefs.getString("drafts_list", "[]")
        val type = object : TypeToken<List<PrintProjectDraft>>() {}.type
        val drafts: List<PrintProjectDraft> = gson.fromJson(json, type) ?: emptyList()
        _draftsFlow.value = drafts.sortedByDescending { it.updatedAt }
    }

    override fun getAllDrafts(): Flow<List<PrintProjectDraft>> = _draftsFlow.asStateFlow()

    override suspend fun getDraftById(id: String): PrintProjectDraft? =
        _draftsFlow.value.find { it.id == id }

    override suspend fun saveDraft(draft: PrintProjectDraft) {
        val list = _draftsFlow.value.toMutableList()
        val i = list.indexOfFirst { it.id == draft.id }
        if (i >= 0) list[i] = draft else list.add(draft)
        saveAndEmit(list)
    }

    override suspend fun deleteDraft(id: String) {
        val list = _draftsFlow.value.toMutableList()
        list.removeAll { it.id == id }
        saveAndEmit(list)
    }

    private fun saveAndEmit(list: List<PrintProjectDraft>) {
        val sorted = list.sortedByDescending { it.updatedAt }
        prefs.edit().putString("drafts_list", gson.toJson(sorted)).apply()
        _draftsFlow.value = sorted
    }
}