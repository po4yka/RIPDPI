package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.state.SettingsUiState

private const val BackupPinLength = 4

@Composable
internal fun rememberSettingsScreenLocalState(uiState: SettingsUiState): SettingsScreenLocalState {
    var showResetConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showBiometricConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showPinRequiredDialog by rememberSaveable { mutableStateOf(false) }
    var backupPinDraft by remember { mutableStateOf("") }
    var backupPinReplacementRevision by remember { mutableStateOf(0L) }
    var communityApiUrlDraft by rememberSaveable(uiState.communityApiUrl) {
        mutableStateOf(uiState.communityApiUrl)
    }
    var communityApiUrlReplacementRevision by remember { mutableStateOf(0L) }
    val pinErrorText =
        when {
            backupPinDraft.isNotEmpty() && backupPinDraft.length < BackupPinLength -> {
                stringResource(R.string.settings_backup_pin_error)
            }

            else -> {
                null
            }
        }

    return SettingsScreenLocalState(
        showResetConfirmDialog = showResetConfirmDialog,
        onShowResetConfirmDialogChanged = { showResetConfirmDialog = it },
        showBiometricConfirmDialog = showBiometricConfirmDialog,
        onShowBiometricConfirmDialogChanged = { showBiometricConfirmDialog = it },
        showPinRequiredDialog = showPinRequiredDialog,
        onShowPinRequiredDialogChanged = { showPinRequiredDialog = it },
        backupPinDraft = backupPinDraft,
        onBackupPinDraftChanged = { next ->
            backupPinDraft = next.filter(Char::isDigit).take(BackupPinLength)
        },
        onBackupPinDraftCleared = {
            backupPinDraft = ""
            backupPinReplacementRevision++
        },
        backupPinReplacementRevision = backupPinReplacementRevision,
        pinErrorText = pinErrorText,
        canSaveBackupPin = backupPinDraft.length == BackupPinLength,
        showBackupPinEditor = uiState.biometricEnabled || uiState.hasBackupPin || backupPinDraft.isNotBlank(),
        communityApiUrlDraft = communityApiUrlDraft,
        communityApiUrlReplacementKey = uiState.communityApiUrl to communityApiUrlReplacementRevision,
        onCommunityApiUrlDraftChanged = { communityApiUrlDraft = it },
        onCommunityApiUrlDraftReset = {
            communityApiUrlDraft = ""
            communityApiUrlReplacementRevision++
        },
    )
}

@Stable
internal data class SettingsScreenLocalState(
    val showResetConfirmDialog: Boolean,
    val onShowResetConfirmDialogChanged: (Boolean) -> Unit,
    val showBiometricConfirmDialog: Boolean,
    val onShowBiometricConfirmDialogChanged: (Boolean) -> Unit,
    val showPinRequiredDialog: Boolean,
    val onShowPinRequiredDialogChanged: (Boolean) -> Unit,
    val backupPinDraft: String,
    val onBackupPinDraftChanged: (String) -> Unit,
    val onBackupPinDraftCleared: () -> Unit,
    val backupPinReplacementRevision: Long,
    val pinErrorText: String?,
    val canSaveBackupPin: Boolean,
    val showBackupPinEditor: Boolean,
    val communityApiUrlDraft: String,
    val communityApiUrlReplacementKey: Any,
    val onCommunityApiUrlDraftChanged: (String) -> Unit,
    val onCommunityApiUrlDraftReset: () -> Unit,
)
