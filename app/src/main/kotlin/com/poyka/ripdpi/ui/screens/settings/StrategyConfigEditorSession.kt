package com.poyka.ripdpi.ui.screens.settings

internal data class StrategyConfigDraft(
    val source: StrategyConfigSource,
    val configText: String,
    val luaPath: String,
    val luaFunction: String,
)

internal data class StrategyConfigSaveRequest(
    val id: Long,
    val draft: StrategyConfigDraft,
)

internal data class StrategyConfigEditorSession(
    val baseline: StrategyConfigDraft,
    val draft: StrategyConfigDraft = baseline,
    private val nextSaveId: Long = 1L,
    private val activeSaveId: Long? = null,
) {
    companion object {
        fun initial(configText: String): StrategyConfigEditorSession =
            StrategyConfigEditorSession(
                baseline =
                    StrategyConfigDraft(
                        source = StrategyConfigSource.BuiltIn,
                        configText = configText,
                        luaPath = "",
                        luaFunction = "",
                    ),
            )
    }

    val isDirty: Boolean
        get() = draft != baseline

    val isSaving: Boolean
        get() = activeSaveId != null

    fun update(transform: StrategyConfigDraft.() -> StrategyConfigDraft): StrategyConfigEditorSession =
        copy(draft = draft.transform())

    fun selectSource(
        source: StrategyConfigSource,
        builtInConfigText: String,
    ): StrategyConfigEditorSession =
        update {
            copy(
                source = source,
                configText = if (source == StrategyConfigSource.BuiltIn) builtInConfigText else configText,
            )
        }

    fun importConfig(configText: String): StrategyConfigEditorSession =
        update {
            copy(
                source = StrategyConfigSource.CustomYaml,
                configText = configText,
            )
        }

    fun toScreenState(
        activePath: String,
        banner: StrategyConfigBanner?,
    ): StrategyConfigScreenState =
        StrategyConfigScreenState(
            source = draft.source,
            configText = draft.configText,
            luaPath = draft.luaPath,
            luaFunction = draft.luaFunction,
            activePath = activePath,
            banner = banner,
            isSaving = isSaving,
        )

    fun syncCleanBuiltIn(configText: String): StrategyConfigEditorSession {
        if (isDirty || draft.source != StrategyConfigSource.BuiltIn) return this
        val synced = draft.copy(configText = configText)
        return copy(baseline = synced, draft = synced)
    }

    fun beginSave(): Pair<StrategyConfigEditorSession, StrategyConfigSaveRequest>? {
        if (isSaving) return null
        val request = StrategyConfigSaveRequest(id = nextSaveId, draft = draft)
        return copy(nextSaveId = nextSaveId + 1, activeSaveId = request.id) to request
    }

    fun completeSave(
        request: StrategyConfigSaveRequest,
        succeeded: Boolean,
    ): StrategyConfigEditorSession {
        if (activeSaveId != request.id) return this
        return copy(
            baseline = if (succeeded) request.draft else baseline,
            activeSaveId = null,
        )
    }
}
