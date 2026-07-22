package com.poyka.ripdpi.services

import android.content.Intent
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.boot.BootSessionPointer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of the boot-resume decision for a single received broadcast. */
sealed interface BootResumeDecision {
    /** Resume the previously-active service in [mode]. */
    data class Resume(
        val mode: Mode,
    ) : BootResumeDecision

    /** Do nothing; [reason] is for diagnostics only. */
    data class Skip(
        val reason: String,
    ) : BootResumeDecision
}

/**
 * Pure boot-resume policy, factored out of [BootResumeCoordinator] so it can be
 * unit-tested without broadcast / `PackageManager` plumbing.
 *
 * - No recorded pointer → nothing was ever active → [BootResumeDecision.Skip].
 * - Recorded profile no longer resumable (deleted) → [BootResumeDecision.Skip].
 * - `BOOT_COMPLETED` → resume the recorded mode after credential unlock.
 * - `MY_PACKAGE_REPLACED` → resume ONLY if the session was running at update
 *   time ([wasRunningAtUpdate]); a deliberately-stopped tunnel must not be
 *   silently resurrected by an app update.
 */
internal fun decideBootResume(
    action: String?,
    pointer: BootSessionPointer?,
    profileResumable: Boolean,
    wasRunningAtUpdate: Boolean,
): BootResumeDecision =
    when {
        pointer == null -> {
            BootResumeDecision.Skip("no recorded session")
        }

        !profileResumable -> {
            BootResumeDecision.Skip("recorded profile is no longer resumable")
        }

        action == Intent.ACTION_BOOT_COMPLETED -> {
            BootResumeDecision.Resume(pointer.mode)
        }

        action == Intent.ACTION_MY_PACKAGE_REPLACED && wasRunningAtUpdate -> {
            BootResumeDecision.Resume(pointer.mode)
        }

        action == Intent.ACTION_MY_PACKAGE_REPLACED -> {
            BootResumeDecision.Skip("package replaced but session was not running at update")
        }

        else -> {
            BootResumeDecision.Skip("unhandled action: $action")
        }
    }

/**
 * Decides whether the recorded boot pointer still designates a resumable
 * session, so a stale pointer (its profile/config deleted) does not trigger a
 * doomed start after reboot.
 */
interface BootResumeProfileGuard {
    suspend fun isResumable(profileId: String): Boolean
}

/**
 * Default [BootResumeProfileGuard]. Interim contract (documented because the
 * `diagnosticsActiveProfileId` notion spans diagnostics + subscription layers
 * with no single profile-by-id store):
 *  - the canonical built-in id `"default"` (and a blank id) is always resumable;
 *  - any other id is resumable iff at least one proxy group still exists —
 *    deleting all configured groups leaves nothing to resume, so the pointer is
 *    treated as stale and the boot resume is skipped.
 */
@Singleton
class DefaultBootResumeProfileGuard
    internal constructor(
        private val proxyGroupRepository: ProxyGroupRepository,
        private val recoverPendingProfileMutations: suspend () -> Unit,
    ) : BootResumeProfileGuard {
        @Inject
        constructor(
            proxyGroupRepository: ProxyGroupRepository,
            profileMutations: ProfileMutationCoordinator,
        ) : this(proxyGroupRepository, profileMutations::recover)

        override suspend fun isResumable(profileId: String): Boolean {
            if (profileId.isBlank() || profileId == DefaultProfileId) return true
            recoverPendingProfileMutations()
            return proxyGroupRepository.list().isNotEmpty()
        }

        private companion object {
            const val DefaultProfileId = "default"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class BootResumeProfileGuardModule {
    @Binds
    @Singleton
    abstract fun bindBootResumeProfileGuard(guard: DefaultBootResumeProfileGuard): BootResumeProfileGuard
}
