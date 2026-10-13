package com.nevoit.cirro.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nevoit.cirro.data.LibraryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(private val repository: LibraryRepository) : ViewModel() {
    private var refreshJob: Job? = null
    private val _refreshVersion = MutableStateFlow(0L)
    val refreshVersion = _refreshVersion.asStateFlow()

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            repository.refresh()
            _refreshVersion.value++
        }
    }
}
