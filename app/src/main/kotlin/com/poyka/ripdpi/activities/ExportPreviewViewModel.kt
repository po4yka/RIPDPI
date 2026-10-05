package com.poyka.ripdpi.activities

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.diagnostics.export.CheckedDiagnosticsExportHandoff
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeaseId
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeasePhase
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPreparation
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose
import com.poyka.ripdpi.diagnostics.export.PreparedDiagnosticsExport
import com.poyka.ripdpi.diagnostics.export.PreparedDiagnosticsExportService
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.components.export.ExportPreviewPresentation
import com.poyka.ripdpi.ui.components.export.ExportPreviewPurpose
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal class ExportPreviewToken(
    val generation: Long,
    val leaseId: DiagnosticsExportLeaseId,
) {
    override fun toString(): String = "ExportPreviewToken(generation=$generation)"
}

internal data class ExportPreviewState(
    val token: ExportPreviewToken,
    val presentation: ExportPreviewPresentation,
)

/** Activity-retained ownership; external destinations are exclusively the Activity's responsibility. */
@HiltViewModel
internal class ExportPreviewViewModel
    @Inject
    constructor(
        private val exports: PreparedDiagnosticsExportService,
        private val savedState: SavedStateHandle,
        private val strings: StringResolver,
        @param:ApplicationIoScope private val cleanupScope: CoroutineScope,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ExportPreviewState?>(null)
        val state = mutableState.asStateFlow()
        private val pendingHandoff = MutableStateFlow<ExportPreviewToken?>(null)
        val handoffs = pendingHandoff.asStateFlow()
        private val contentUnlocked = MutableStateFlow(false)
        private val cleanupFailure = MutableStateFlow(false)
        val cleanupFailures = cleanupFailure.asStateFlow()

        fun acknowledgeCleanupFailure() {
            cleanupFailure.value = false
        }

        fun setContentUnlocked(unlocked: Boolean) {
            contentUnlocked.value = unlocked
        }

        private var generation = 0L
        private var owned: ExportPreviewToken? = null
        private var job: Job? = null
        private var confirming = false

        init {
            savedState.get<String>(LeaseKey)?.let(DiagnosticsExportLeaseId::parse)?.let { id ->
                val token = newOwner(id)
                job =
                    viewModelScope.launch {
                        try {
                            val preview = exports.inspect(id)
                            if (preview.phase == DiagnosticsExportLeasePhase.Ready && owns(token)) {
                                showReady(token, preview)
                            } else {
                                // A consumed lease may already belong to another process.
                                // Never relaunch or delete it.
                                if (owns(token)) forget(token)
                            }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            showError(token, R.string.export_preview_failed)
                        }
                    }
            }
        }

        fun prepare(preparation: DiagnosticsExportPreparation) {
            invalidateOwner()
            val token = newOwner(DiagnosticsExportLeaseId.create())
            mutableState.value = ExportPreviewState(token, ExportPreviewPresentation.Preparing)
            val frozen =
                when (preparation) {
                    is DiagnosticsExportPreparation.Archive -> {
                        preparation.copy(
                            request = preparation.request.copy(sessionIds = preparation.request.sessionIds.toList()),
                        )
                    }

                    DiagnosticsExportPreparation.Logs -> {
                        preparation
                    }
                }
            job =
                viewModelScope.launch {
                    try {
                        val preview = exports.prepare(token.leaseId, frozen)
                        if (owns(token)) showReady(token, preview) else discard(token)
                    } catch (cancellation: CancellationException) {
                        withContext(NonCancellable) { discard(token) }
                        throw cancellation
                    } catch (_: Exception) {
                        if (discard(token)) showError(token, R.string.export_preview_failed)
                    }
                }
        }

        fun confirm(token: ExportPreviewToken) {
            if (!owns(token) || confirming ||
                mutableState.value?.presentation !is ExportPreviewPresentation.Ready
            ) {
                return
            }
            // Fence duplicate UI callbacks synchronously, before durable consumption can suspend.
            confirming = true
            mutableState.value = ExportPreviewState(token, ExportPreviewPresentation.Preparing)
            job =
                viewModelScope.launch {
                    try {
                        exports.consume(token.leaseId)
                        if (owns(token)) run { pendingHandoff.value = token } else discard(token)
                    } catch (cancellation: CancellationException) {
                        withContext(NonCancellable) { discard(token) }
                        throw cancellation
                    } catch (_: Exception) {
                        if (discard(token)) showError(token, R.string.export_preview_failed)
                    }
                }
        }

        /** The checked file is obtained afresh, then launch and ownership release happen without suspension. */
        suspend fun handoff(
            token: ExportPreviewToken,
            launch: (CheckedDiagnosticsExportHandoff) -> Unit,
        ) {
            if (!owns(token) || !confirming) return
            try {
                val checked = awaitCheckedHandoff(token)
                if (checked != null) {
                    launch(checked)
                    forget(token)
                    if (checked.preview.purpose == DiagnosticsExportPurpose.ShareSummary) {
                        withContext(NonCancellable) { discard(token) }
                    }
                    // ShareArchive retains its lease for delayed FileProvider reads. Save purposes
                    // retain it until the independently saved picker-result lease is completed.
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (discard(token)) showError(token, R.string.export_preview_failed)
            }
        }

        private suspend fun awaitCheckedHandoff(token: ExportPreviewToken): CheckedDiagnosticsExportHandoff? {
            var candidate: CheckedDiagnosticsExportHandoff? = null
            while (candidate == null && owns(token) && confirming) {
                contentUnlocked.first { it }
                if (owns(token) && confirming) {
                    val checked = exports.validateHandoff(token.leaseId)
                    // Relocking during validation requires another unlock AND fresh validation.
                    if (contentUnlocked.value && owns(token) && confirming) candidate = checked
                }
            }
            return candidate
        }

        fun cancel(token: ExportPreviewToken) {
            if (!owns(token)) return
            invalidateOwner()
        }

        fun reportCleanupFailure(id: DiagnosticsExportLeaseId) {
            cleanupFailure.value = true
            if (owned == null) {
                val token = ExportPreviewToken(++generation, id)
                mutableState.value =
                    ExportPreviewState(
                        token,
                        ExportPreviewPresentation.Error(
                            strings.getString(R.string.export_preview_cleanup_failed),
                        ),
                    )
                owned = token
            }
        }

        private fun newOwner(id: DiagnosticsExportLeaseId): ExportPreviewToken =
            ExportPreviewToken(++generation, id).also {
                owned = it
                confirming = false
                savedState[LeaseKey] = id.encoded
            }

        private fun owns(token: ExportPreviewToken): Boolean = owned === token

        private fun forget(token: ExportPreviewToken) {
            if (!owns(token)) return
            owned = null
            pendingHandoff.value = null
            confirming = false
            savedState.remove<String>(LeaseKey)
            mutableState.value = null
        }

        private fun invalidateOwner() {
            val previous = owned
            generation++
            owned = null
            pendingHandoff.value = null
            confirming = false
            savedState.remove<String>(LeaseKey)
            mutableState.value = null
            job?.cancel()
            job = null
            previous?.let { cleanupScope.launch(Dispatchers.Main.immediate) { discard(it) } }
        }

        private suspend fun discard(token: ExportPreviewToken): Boolean {
            try {
                exports.discard(token.leaseId)
                return true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (owns(token)) {
                    showError(token, R.string.export_preview_cleanup_failed)
                }
                reportCleanupFailure(token.leaseId)
                return false
            }
        }

        private fun showError(
            token: ExportPreviewToken,
            message: Int,
        ) {
            if (owns(token)) {
                mutableState.value =
                    ExportPreviewState(
                        token,
                        ExportPreviewPresentation.Error(strings.getString(message)),
                    )
            }
        }

        private fun showReady(
            token: ExportPreviewToken,
            preview: PreparedDiagnosticsExport,
        ) {
            mutableState.value =
                ExportPreviewState(
                    token,
                    ExportPreviewPresentation.Ready(
                        summary = preview.summary,
                        fileName = preview.fileName,
                        mimeType = preview.mimeType,
                        byteCount = preview.byteCount,
                        entryNames = preview.entryNames.toImmutableList(),
                        purpose = ExportPreviewPurpose.valueOf(preview.purpose.name),
                    ),
                )
        }

        override fun onCleared() {
            invalidateOwner()
        }

        private companion object {
            const val LeaseKey = "prepared-export-lease"
        }
    }
