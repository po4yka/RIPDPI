package com.poyka.ripdpi.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.SavedAwgProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
                .map { saved -> saved.map(SavedAwgProfile::toAwgProfileItem).toImmutableList() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())
    }
