package com.poyka.ripdpi.data.backup

import com.poyka.ripdpi.data.ProxyProfile

/**
 * Restore-side counterpart of [BackupProfileProvider].
 *
 * Profiles are persisted encrypted in `:core:service`
 * ([com.poyka.ripdpi.keystore.EncryptedProfileStore]) and are not owned by a single
 * `:core:data` repository, so the restore use case ([BackupRestoreUseCase]) writes
 * decoded profiles through this abstraction. The app module binds a concrete
 * implementation, keeping `:core:data` free of an upward dependency on
 * `:core:service`.
 *
 * Implementations MUST replace the entire stored profile set with [profiles]
 * atomically with respect to a single restore — partial application would leave the
 * profile store inconsistent with the freshly-restored groups/rules.
 */
fun interface BackupProfileRestoreSink {
    /** Replaces every stored proxy profile with [profiles]. May be empty. */
    suspend fun replaceAll(profiles: List<ProxyProfile>)
}
