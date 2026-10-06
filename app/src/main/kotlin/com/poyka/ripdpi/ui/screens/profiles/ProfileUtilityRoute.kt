package com.poyka.ripdpi.ui.screens.profiles

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ProfileUtilityRoute(
    onBack: () -> Unit,
    viewModel: ProfileUtilityViewModel = hiltViewModel(),
) {
    ProfileUtilityScreen(
        viewModel.uiState.collectAsStateWithLifecycle().value,
        onBack,
        ProfileUtilityActions(
            viewModel::favorite,
            viewModel::check,
            viewModel::checkAndSelect,
            viewModel::cancel,
            viewModel::retryCleanup,
            viewModel::updateUrl,
            viewModel::checkAndSelectFastest,
        ),
    )
}
