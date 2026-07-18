package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
internal class StrategyConfigEditorViewModel
    @Inject
    constructor() : ViewModel() {
        var session by mutableStateOf<StrategyConfigEditorSession?>(null)
            private set

        fun syncBuiltIn(configText: String) {
            session = session?.syncCleanBuiltIn(configText) ?: StrategyConfigEditorSession.initial(configText)
        }

        fun update(transform: StrategyConfigDraft.() -> StrategyConfigDraft) {
            session = session?.update(transform)
        }

        fun selectSource(
            source: StrategyConfigSource,
            builtInConfigText: String,
        ) {
            session = session?.selectSource(source, builtInConfigText)
        }

        fun importConfig(configText: String) {
            session = session?.importConfig(configText)
        }

        fun beginSave(): StrategyConfigSaveRequest? {
            val (savingSession, request) = session?.beginSave() ?: return null
            session = savingSession
            return request
        }

        fun completeSave(
            request: StrategyConfigSaveRequest,
            succeeded: Boolean,
        ) {
            session = session?.completeSave(request, succeeded)
        }

        suspend fun runSave(
            request: StrategyConfigSaveRequest,
            save: suspend () -> StrategyConfigBanner,
        ): StrategyConfigBanner {
            var saved = false
            return try {
                save().also { saved = it.saved }
            } finally {
                completeSave(request, saved)
            }
        }
    }
