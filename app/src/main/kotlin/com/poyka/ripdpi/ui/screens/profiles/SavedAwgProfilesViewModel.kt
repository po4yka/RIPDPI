package com.poyka.ripdpi.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.SavedAwgProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Lists saved AmneziaWG profiles for the picker; only names and key presence leave the repository. */
@HiltViewModel
internal class SavedAwgProfilesViewModel
    @Inject
    constructor(
        repository: AwgProfileRepository,
    ) : ViewModel() {
        val profiles: StateFlow<ImmutableList<AwgProfileItem>> =
            repository
                .observeProfiles()
                .map<List<SavedAwgProfile>, ImmutableList<AwgProfileItem>> { saved ->
                    saved.map(SavedAwgProfile::toAwgProfileItem).toImmutableList()
                }.catch {
                    if (it is CancellationException) throw it
                    // Like the relay catalog: a failed recovery or read leaves the picker usable without AWG rows.
                    emit(persistentListOf())
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())
    }
