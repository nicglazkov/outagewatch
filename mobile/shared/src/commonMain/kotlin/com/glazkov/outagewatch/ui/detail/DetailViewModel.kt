package com.glazkov.outagewatch.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glazkov.outagewatch.api.OutageDetail
import com.glazkov.outagewatch.ui.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DetailState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val detail: OutageDetail? = null,
    val etaChanges: Int = 0,
    val explanation: String? = null,
    val explainFailed: Boolean = false,
)

class DetailViewModel(private val outageId: String) : ViewModel() {
    private val api = AppGraph.api

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val detail = runCatching { api.outageDetail(outageId) }.getOrNull()
        if (detail == null) {
            _state.value = DetailState(loading = false, notFound = true)
            return
        }
        _state.value = DetailState(
            loading = false,
            detail = detail,
            etaChanges = maxOf(0, detail.etaHistory.size - 1),
        )
        val explanation = runCatching { api.explain(outageId).explanation }.getOrNull()
        _state.value = _state.value.copy(
            explanation = explanation,
            explainFailed = explanation == null,
        )
    }
}
