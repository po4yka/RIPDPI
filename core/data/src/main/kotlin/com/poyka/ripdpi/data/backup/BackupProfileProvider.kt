package com.poyka.ripdpi.data.backup

import com.poyka.ripdpi.data.ProxyProfile

/**
 * Supplies the full set of [ProxyProfile] records to include in a backup.
 *
 * Profiles are not owned by a single `:core:data` repository — they are persisted
 * encrypted in `:core:service` ([com.poyka.ripdpi.keystore.EncryptedProfileStore])
 * and materialized by the app layer. To keep `:core:data` free of an upward
 * dependency on `:core:service`, the gather use case ([BackupExportUseCase])
 * depends on this small abstraction instead, and the app module binds a concrete
 * implementation.
 *
 * Implementations MUST return decrypted, fully-populated profiles: the
 * [BackupExporter] performs its own SHARE/FULL redaction downstream via
 * [BackupAllowlist].
 */
fun interface BackupProfileProvider {
    /** Returns every stored proxy profile. May be empty. */
    suspend fun profiles(): List<ProxyProfile>
}
