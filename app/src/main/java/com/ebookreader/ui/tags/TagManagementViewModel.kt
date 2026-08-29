package com.ebookreader.ui.tags

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.di.Injector
import com.ebookreader.domain.model.Tag
import com.ebookreader.domain.model.TagGroup
import com.ebookreader.domain.repository.TagRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TagManagementViewModel(application: Application) : AndroidViewModel(application) {

    private val tagRepository: TagRepository = Injector.tagRepository()

    val tags: StateFlow<List<Tag>> = tagRepository.getAllTags()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val tagGroups: StateFlow<List<TagGroup>> = tagRepository.getAllTagGroups()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun deleteTagGroup(group: TagGroup) {
        viewModelScope.launch {
            tagRepository.deleteTagGroup(group)
        }
    }
}
