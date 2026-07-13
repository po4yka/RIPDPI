package com.poyka.ripdpi.data.backup

import android.util.Log
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.rules.RuleDao
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import javax.inject.Inject

private const val LogTag = "BackupRestore"

private sealed interface BackupImportOutcome {
    data class Ready(
        val document: BackupV1,
    ) : BackupImportOutcome

    data class Unsupported(
        val found: Int,
        val supported: Int,
    ) : BackupImportOutcome

    data class Malformed(
        val reason: String,
    ) : BackupImportOutcome
}

private sealed interface StagedRestoreOutcome {
    data class Ready(
        val staged: StagedRestore,
    ) : StagedRestoreOutcome

    data class Aborted(
        val reason: String,
    ) : StagedRestoreOutcome
}

/**
 * Which categories of a backup the user opted to restore. Unchecked categories
 * are left untouched in the live stores.
 */
data class RestoreSelection(
    val profilesAndGroups: Boolean,
    val routes: Boolean,
    val settings: Boolean,
) {
    /** `true` when at least one category is selected. */
    val any: Boolean get() = profilesAndGroups || routes || settings
}

/**
 * Read-only summary of a parsed backup, computed BEFORE any write. Drives the
 * preview screen (per-category counts + schema version + restorability).
 */
data class BackupPreview(
    val schemaVersion: Int,
    val appVersion: String,
    val createdAtEpochMillis: Long,
    val containsCredentials: Boolean,
    /** Profiles that decoded cleanly and can be restored. */
    val restorableProfileCount: Int,
    /** Groups present in the backup. */
    val groupCount: Int,
    /** Routing rules present in the backup. */
    val ruleCount: Int,
    /** Number of settings keys present in the backup `settings` map. */
    val settingCount: Int,
    /**
     * Display names of profiles that could NOT be decoded (typically a SHARE
     * backup whose redacted credentials were stripped). Surfaced honestly so the
     * user understands why a SHARE backup cannot restore profiles.
     */
    val undecodableProfiles: List<String>,
) {
    /** `true` when no profile could be decoded but the backup claims to carry some. */
    val hasUndecodableProfiles: Boolean get() = undecodableProfiles.isNotEmpty()

    /** `true` when the profiles+groups category has anything worth restoring. */
    val canRestoreProfilesAndGroups: Boolean get() = restorableProfileCount > 0 || groupCount > 0
}

/** Result of parsing a backup string into a [BackupPreview]. */
sealed interface BackupPreviewResult {
    data class Ready(
        val preview: BackupPreview,
    ) : BackupPreviewResult

    /** Schema version is newer than this app supports; restore is refused outright. */
    data class UnsupportedVersion(
        val found: Int,
        val supported: Int,
    ) : BackupPreviewResult

    /** The JSON was malformed or did not parse into a backup document. */
    data class Malformed(
        val reason: String,
    ) : BackupPreviewResult
}

/** Result of applying a restore. */
sealed interface RestoreResult {
    /** The restore committed. [restartRequired] is always `true` for a successful restore. */
    data class Success(
        val restartRequired: Boolean = true,
    ) : RestoreResult

    /** Nothing was selected, so no write occurred. */
    data object NothingSelected : RestoreResult

    /** Schema version is newer than this app supports; live data was NOT touched. */
    data class UnsupportedVersion(
        val found: Int,
        val supported: Int,
    ) : RestoreResult

    /** Parsing/staging failed, or a commit failed and completed writes were compensated. */
    data class Aborted(
        val reason: String,
    ) : RestoreResult

    /** A commit failed and at least one compensating write also failed, leaving integrity uncertain. */
    data class IntegrityFailure(
        val commitFailure: String,
        val rollbackFailures: List<String>,
    ) : RestoreResult
}

/**
 * Validated, in-memory staging of everything a restore will write. Building this
 * fully (and validating it) before any live store is touched is the "staging area"
 * required by the task: if staging throws, the live data is never modified.
 */
private data class StagedRestore(
    val groups: List<ProxyGroup>?,
    val privateData: BackupPrivateDataV1?,
    val rules: List<RuleEntity>?,
    val settings: AppSettings?,
)

/** Preimages captured before the first live write and used for reverse-order compensation. */
private data class RestorePreimage(
    val groups: List<ProxyGroup>?,
    val privateData: BackupPrivateDataV1?,
    val rules: List<RuleEntity>?,
    val settings: AppSettings?,
)

private data class RestoreCommitProgress(
    var privateData: Boolean = false,
    var groups: Boolean = false,
    var rules: Boolean = false,
    var settings: Boolean = false,
)

/**
 * Parses a backup document and applies a selective, staged, atomic restore into the
 * live stores ([ProxyGroupRepository] / [RuleDao] / [AppSettingsRepository]).
 *
 * Contract honored:
 * - **Preview before write** — [preview] decodes counts and decode failures with no
 *   side effects.
 * - **Strict version gating** — a newer-than-app schema is refused and NEVER
 *   partially overwrites; older versions migrate (via [BackupImporter.import]).
 * - **Staging + integrity** — [restore] decodes and validates the whole selected
 *   payload into [StagedRestore] first; only then does it commit. Malformed JSON or
 *   a decode failure aborts without touching live data.
 * - **Cross-store compensation** — preimages are captured before the first write;
 *   a later failure restores completed categories in reverse order. Each individual
 *   category still uses its own atomic replace boundary.
 * - **SHARE backups** — profiles whose redacted credentials were stripped cannot be
 *   decoded; they are reported in the preview and excluded from the staged write.
 */
class BackupRestoreUseCase
    @Inject
    constructor(
        private val groupRepository: ProxyGroupRepository,
        private val ruleDao: RuleDao,
        private val settingsRepository: AppSettingsRepository,
        private val privateDataStore: BackupPrivateDataStore,
    ) {
        constructor(
            groupRepository: ProxyGroupRepository,
            ruleDao: RuleDao,
            settingsRepository: AppSettingsRepository,
        ) : this(groupRepository, ruleDao, settingsRepository, BackupPrivateDataStore.Empty)

        /** Parses [json] into a [BackupPreview] without touching any store. */
        fun preview(json: String): BackupPreviewResult =
            when (val imported = importBackup(json, "Backup preview parse failed")) {
                is BackupImportOutcome.Ready -> {
                    imported.document.toPreviewResult()
                }

                is BackupImportOutcome.Unsupported -> {
                    BackupPreviewResult.UnsupportedVersion(found = imported.found, supported = imported.supported)
                }

                is BackupImportOutcome.Malformed -> {
                    BackupPreviewResult.Malformed(reason = imported.reason)
                }
            }

        private fun BackupV1.toPreviewResult(): BackupPreviewResult {
            val decoded = BackupRestoreDecoder.decodeProfiles(this)
            val restorable = decoded.filterIsInstance<ProfileDecodeResult.Decoded>()
            val failed = decoded.filterIsInstance<ProfileDecodeResult.Failed>()
            val restorableProfileIds =
                buildSet {
                    groups.forEach { group -> group.members.forEach { add(it.id) } }
                    restorable.forEach { add(it.profile.id) }
                }
            val separateProfileCount =
                privateData?.let {
                    it.relayProfiles.size + it.warpProfiles.size + it.awgProfiles.size + it.xrayMetadata.size
                } ?: 0

            return BackupPreviewResult.Ready(
                BackupPreview(
                    schemaVersion = schemaVersion,
                    appVersion = appVersion,
                    createdAtEpochMillis = createdAtEpochMillis,
                    containsCredentials = containsCredentials,
                    restorableProfileCount = restorableProfileIds.size + separateProfileCount,
                    groupCount = groups.size,
                    ruleCount = rules.size,
                    settingCount = settings.size,
                    undecodableProfiles = failed.map { it.displayName },
                ),
            )
        }

        /**
         * Applies a restore of the categories selected in [selection].
         *
         * Decoding + staging happens entirely before any live write, so any failure
         * leaves the live stores untouched. On success, the caller is expected to
         * restart the process so DataStore / Room observers reinitialize.
         */
        suspend fun restore(
            json: String,
            selection: RestoreSelection,
        ): RestoreResult =
            when {
                !selection.any -> {
                    RestoreResult.NothingSelected
                }

                else -> {
                    when (val imported = importBackup(json, "Backup restore parse failed")) {
                        is BackupImportOutcome.Ready -> {
                            restoreImported(imported.document, selection)
                        }

                        is BackupImportOutcome.Unsupported -> {
                            RestoreResult.UnsupportedVersion(found = imported.found, supported = imported.supported)
                        }

                        is BackupImportOutcome.Malformed -> {
                            RestoreResult.Aborted(reason = imported.reason)
                        }
                    }
                }
            }

        private suspend fun restoreImported(
            document: BackupV1,
            selection: RestoreSelection,
        ): RestoreResult =
            when (val outcome = stageSafely(document, selection)) {
                is StagedRestoreOutcome.Ready -> commit(outcome.staged, selection)
                is StagedRestoreOutcome.Aborted -> RestoreResult.Aborted(reason = outcome.reason)
            }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun commit(
            staged: StagedRestore,
            selection: RestoreSelection,
        ): RestoreResult {
            val preimage =
                try {
                    capturePreimage(staged)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w(LogTag, "Backup restore preimage failed; live data untouched: ${failure::class.simpleName}")
                    return RestoreResult.Aborted(reason = failure::class.simpleName.orEmpty())
                }
            val progress = RestoreCommitProgress()
            return try {
                staged.privateData?.let {
                    progress.privateData = true
                    privateDataStore.replaceAll(it)
                }
                staged.groups?.let {
                    progress.groups = true
                    groupRepository.replaceAll(it)
                }
                staged.rules?.let {
                    progress.rules = true
                    ruleDao.replaceAll(it)
                }
                staged.settings?.let {
                    progress.settings = true
                    settingsRepository.replace(it)
                }

                Log.i(
                    LogTag,
                    "Backup restore committed: profiles+groups=${selection.profilesAndGroups} " +
                        "routes=${selection.routes} settings=${staged.settings != null}",
                )
                RestoreResult.Success(restartRequired = true)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { rollback(preimage, progress) }
                    .filterNot { rollbackFailure -> rollbackFailure === cancelled }
                    .forEach(cancelled::addSuppressed)
                throw cancelled
            } catch (failure: Exception) {
                val rollbackFailures = withContext(NonCancellable) { rollback(preimage, progress) }
                rollbackFailures
                    .filterNot { rollbackFailure -> rollbackFailure === failure }
                    .forEach(failure::addSuppressed)
                if (rollbackFailures.isEmpty()) {
                    Log.w(LogTag, "Backup restore commit failed and was compensated: ${failure::class.simpleName}")
                    RestoreResult.Aborted(reason = failure::class.simpleName.orEmpty())
                } else {
                    Log.e(LogTag, "Backup restore compensation failed after ${failure::class.simpleName}")
                    RestoreResult.IntegrityFailure(
                        commitFailure = failure::class.simpleName.orEmpty(),
                        rollbackFailures = rollbackFailures.map { it::class.simpleName.orEmpty() },
                    )
                }
            }
        }

        private suspend fun capturePreimage(staged: StagedRestore): RestorePreimage =
            RestorePreimage(
                groups = staged.groups?.let { groupRepository.list() },
                privateData = staged.privateData?.let { privateDataStore.snapshot() },
                rules = staged.rules?.let { ruleDao.allRules().first() },
                settings = staged.settings?.let { settingsRepository.snapshot() },
            )

        @Suppress("TooGenericExceptionCaught")
        private suspend fun rollback(
            preimage: RestorePreimage,
            progress: RestoreCommitProgress,
        ): List<Exception> {
            val failures = mutableListOf<Exception>()

            suspend fun compensate(block: suspend () -> Unit) {
                try {
                    block()
                } catch (rollbackFailure: Exception) {
                    failures += rollbackFailure
                }
            }

            if (progress.settings) compensate { settingsRepository.replace(requireNotNull(preimage.settings)) }
            if (progress.rules) compensate { ruleDao.replaceAll(requireNotNull(preimage.rules)) }
            if (progress.groups) compensate { groupRepository.replaceAll(requireNotNull(preimage.groups)) }
            if (progress.privateData) compensate { privateDataStore.replaceAll(requireNotNull(preimage.privateData)) }
            return failures
        }

        private suspend fun stageSafely(
            document: BackupV1,
            selection: RestoreSelection,
        ): StagedRestoreOutcome =
            try {
                StagedRestoreOutcome.Ready(stage(document, selection))
            } catch (e: IllegalArgumentException) {
                Log.w(LogTag, "Backup restore staging failed; live data untouched: ${e::class.simpleName}")
                StagedRestoreOutcome.Aborted(reason = e::class.simpleName.orEmpty())
            } catch (e: IllegalStateException) {
                Log.w(LogTag, "Backup restore staging failed; live data untouched: ${e::class.simpleName}")
                StagedRestoreOutcome.Aborted(reason = e::class.simpleName.orEmpty())
            }

        private fun importBackup(
            json: String,
            malformedLogPrefix: String,
        ): BackupImportOutcome =
            try {
                BackupImportOutcome.Ready(BackupImporter.import(json))
            } catch (e: UnsupportedBackupVersion) {
                BackupImportOutcome.Unsupported(found = e.found, supported = e.supported)
            } catch (e: SerializationException) {
                Log.w(LogTag, "$malformedLogPrefix: ${e::class.simpleName}")
                BackupImportOutcome.Malformed(reason = e::class.simpleName.orEmpty())
            } catch (e: IllegalArgumentException) {
                Log.w(LogTag, "$malformedLogPrefix: ${e::class.simpleName}")
                BackupImportOutcome.Malformed(reason = e::class.simpleName.orEmpty())
            } catch (e: IllegalStateException) {
                Log.w(LogTag, "$malformedLogPrefix: ${e::class.simpleName}")
                BackupImportOutcome.Malformed(reason = e::class.simpleName.orEmpty())
            }

        private suspend fun stage(
            document: BackupV1,
            selection: RestoreSelection,
        ): StagedRestore {
            val groups =
                if (selection.profilesAndGroups) {
                    // Only profiles that decode cleanly are staged; SHARE-stripped
                    // profiles are excluded (already surfaced in the preview).
                    val profiles =
                        BackupRestoreDecoder
                            .decodeProfiles(document)
                            .filterIsInstance<ProfileDecodeResult.Decoded>()
                            .map { it.profile }
                    reconcileProfilesWithGroups(document.groups, profiles)
                } else {
                    null
                }
            val privateData = document.privateData.takeIf { selection.profilesAndGroups }
            val rules =
                if (selection.routes) {
                    document.rules.map(BackupRestoreDecoder::ruleExportToEntity)
                } else {
                    null
                }
            val settings =
                if (selection.settings) {
                    BackupSettingsConverter.fromMap(document.settings)
                } else {
                    null
                }
            return StagedRestore(groups = groups, privateData = privateData, rules = rules, settings = settings)
        }

        private fun reconcileProfilesWithGroups(
            groups: List<ProxyGroup>,
            profiles: List<ProxyProfile>,
        ): List<ProxyGroup> {
            val reconciled = groups.toMutableList()
            val groupIndexes = groups.withIndex().associate { it.value.id to it.index }
            val persistedProfileIds = groups.flatMap { it.members }.mapTo(mutableSetOf()) { it.id }

            profiles.forEach { profile ->
                val groupIndex =
                    requireNotNull(groupIndexes[profile.groupId]) {
                        "Backup profile ${profile.id} references missing group ${profile.groupId}"
                    }
                if (persistedProfileIds.add(profile.id)) {
                    val group = reconciled[groupIndex]
                    reconciled[groupIndex] = group.copy(members = group.members + profile)
                }
            }

            return reconciled
        }
    }
