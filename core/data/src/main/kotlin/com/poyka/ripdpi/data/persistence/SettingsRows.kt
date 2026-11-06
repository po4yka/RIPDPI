package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal class SettingsRows(
    private val records: ProfileRecordAccess,
) {
    fun snapshot(): AppSettings = decode(records.read(Namespace, Current))

    fun update(transform: AppSettings.Builder.() -> Unit) = replace(snapshot().toBuilder().apply(transform).build())

    fun replace(settings: AppSettings) = records.write(Namespace, Current, settings.toByteArray())

    companion object {
        const val Namespace = "app_settings"
        const val Current = "current"

        fun decode(bytes: ByteArray?): AppSettings =
            bytes?.let(AppSettings::parseFrom) ?: AppSettingsSerializer.defaultValue
    }
}

@Singleton
internal class RoomAppSettingsRepository
    @Inject
    constructor(
        private val database: ProfileDatabase,
    ) : AppSettingsRepository {
        override val settings =
            database
                .observe(SettingsRows.Namespace)
                .map { SettingsRows.decode(it[SettingsRows.Current]) }
                .distinctUntilChanged()

        override suspend fun snapshot() = database.read { it.settings.snapshot() }

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) =
            database.write { it.settings.update(transform) }

        override suspend fun replace(settings: AppSettings) = database.write { it.settings.replace(settings) }
    }
