package com.poyka.ripdpi.data.fakes

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [AppSettingsRepository] shared by unit tests. */
class FakeAppSettingsRepository(
    initialSettings: AppSettings = AppSettingsSerializer.defaultValue,
) : AppSettingsRepository {
    private val state = MutableStateFlow(initialSettings)

    override val settings: Flow<AppSettings> = state.asStateFlow()

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}
