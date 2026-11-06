package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow

interface AppSettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun snapshot(): AppSettings

    suspend fun update(transform: AppSettings.Builder.() -> Unit)

    /**
     * Overwrites the whole store and discards writes committed since [settings] was read.
     * Use it only for reset and backup restore; read-modify-write callers use [update].
     */
    suspend fun replace(settings: AppSettings)
}
