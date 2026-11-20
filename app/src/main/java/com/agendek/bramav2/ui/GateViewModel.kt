package com.agendek.bramav2.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.agendek.bramav2.domain.GateRepository
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.HistoryEntry
import com.agendek.bramav2.graph
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Jedyny most UI → domena: stan i intencje [GateRepository] (UI nie sięga do magazynów ani `:ble`). */
class GateViewModel(app: Application) : AndroidViewModel(app) {
    val repository: GateRepository = app.graph.repository
    val state: StateFlow<GateUiState> = repository.state
    val history: Flow<List<HistoryEntry>> = repository.history
}
