package com.poyka.ripdpi.ui.screens.proxyimport

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.screens.connection.ConnectionEffect
import com.poyka.ripdpi.ui.screens.connection.ConnectionRelayProfileSelector
import com.poyka.ripdpi.ui.screens.connection.ConnectionViewModelDependencies
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Inject

enum class ImportedServerSelectionResult {
    Failed,
    Selected,

    AppliedToRunningService,
}

fun interface ImportedServerSelection {
    suspend fun select(profileId: String): ImportedServerSelectionResult
}

internal class ConnectionImportedServerSelection
    @Inject
    constructor(
        private val dependencies: ConnectionViewModelDependencies,
        stringResolver: StringResolver,
    ) : ImportedServerSelection {
        private val effects =
            MutableSharedFlow<ConnectionEffect>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        private val activationFailed =
            ConnectionEffect.Message(stringResolver.getString(R.string.relay_editor_activation_failed))
        private val selector =
            ConnectionRelayProfileSelector(
                dependencies = dependencies,
                scope = dependencies.applicationScope,
                stringResolver = stringResolver,
                effects = effects,
                log = Logger.withTag(LogTags.UI),
                activeSaveJob = { null },
                suppressSaveSuccess = {},
                onProviderChanged = {},
            )

        override suspend fun select(profileId: String): ImportedServerSelectionResult {
            val wasRunning = dependencies.serviceStateStore.status.value.first == AppStatus.Running
            effects.resetReplayCache()
            val job = selector.select(profileId) ?: return ImportedServerSelectionResult.Failed
            job.join()
            return when {
                job.isCancelled || activationFailed in effects.replayCache -> ImportedServerSelectionResult.Failed
                wasRunning -> ImportedServerSelectionResult.AppliedToRunningService
                else -> ImportedServerSelectionResult.Selected
            }
        }
    }

@Module
@InstallIn(ViewModelComponent::class)
internal interface ImportedServerSelectionModule {
    @Binds
    fun bind(selection: ConnectionImportedServerSelection): ImportedServerSelection
}
