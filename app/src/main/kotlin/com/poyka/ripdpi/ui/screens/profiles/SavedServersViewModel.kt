package com.poyka.ripdpi.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.ProfileUtilityCatalogReader
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
internal class SavedServersViewModel
    @Inject
    constructor(
        catalog: ProfileUtilityCatalogReader,
        awg: AwgProfileRepository,
    ) : ViewModel() {
        val hasSavedServer: StateFlow<Boolean?> =
            combine<_, _, Boolean?>(catalog.observe(), awg.observeProfiles()) { servers, awgProfiles ->
                servers.entries.isNotEmpty() || awgProfiles.isNotEmpty()
            }.catch {
                if (it is CancellationException) throw it
                emit(null)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }
