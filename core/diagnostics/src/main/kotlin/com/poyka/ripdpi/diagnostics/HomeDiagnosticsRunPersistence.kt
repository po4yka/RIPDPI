package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryClock
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHomeDetectionLaunchOriginStorageValue
import com.poyka.ripdpi.data.diagnostics.HomeDiagnosticsRunEntity
import com.poyka.ripdpi.data.diagnostics.HomeDiagnosticsRunStore
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
internal class HomeDiagnosticsRunPersistence
    @Inject
    constructor(
        private val store: HomeDiagnosticsRunStore,
        private val clock: DiagnosticsHistoryClock,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        suspend fun persistCompletedRun(outcome: DiagnosticsHomeCompositeOutcome): DiagnosticsHomeCompositeOutcome {
            val now = clock.now()
            val persistedOutcome = outcome.withLocalDetectionEvidenceSession(now)
            val existing = store.getHomeRun(outcome.runId)
            val payload = PersistedHomeDiagnosticsRunPayload.from(persistedOutcome)
            requireNotNull(payload.toOutcomeOrNull(persistedOutcome.runId)) {
                "Completed home diagnostics outcome does not satisfy the durable evidence contract"
            }
            val runEntity =
                HomeDiagnosticsRunEntity(
                    runId = persistedOutcome.runId,
                    origin = PersistedHomeRunOrigin,
                    status = PersistedHomeRunStatusCompleted,
                    payloadVersion = PersistedHomeRunPayloadVersion,
                    payloadJson = json.encodeToString(PersistedHomeDiagnosticsRunPayload.serializer(), payload),
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            val detectionEvidence = persistedOutcome.localDetectionEvidenceProjection(now)
            store.persistCompletedHomeRun(
                run = runEntity,
                detectionSession = detectionEvidence?.session,
                detectionResults = detectionEvidence?.results.orEmpty(),
            )
            return persistedOutcome
        }

        suspend fun getCompletedRun(runId: String): DiagnosticsHomeCompositeOutcome? {
            val entity = store.getHomeRun(runId)
            return entity?.takeIf { row -> row.isSupportedHomeRun(runId) }?.let { row ->
                decodePayloadOrNull(row.payloadJson)?.toOutcomeOrNull(expectedRunId = runId)
            }
        }

        private fun HomeDiagnosticsRunEntity.isSupportedHomeRun(runId: String): Boolean =
            this.runId == runId &&
                origin == PersistedHomeRunOrigin &&
                status == PersistedHomeRunStatusCompleted &&
                payloadVersion == PersistedHomeRunPayloadVersion

        private fun decodePayloadOrNull(payloadJson: String): PersistedHomeDiagnosticsRunPayload? =
            runCatching {
                json.decodeFromString(PersistedHomeDiagnosticsRunPayload.serializer(), payloadJson)
            }.getOrElse { error ->
                when (error) {
                    is SerializationException,
                    is IllegalArgumentException,
                    -> null

                    else -> throw error
                }
            }

        private fun DiagnosticsHomeCompositeOutcome.localDetectionEvidenceProjection(
            now: Long,
        ): LocalDetectionEvidenceProjection? {
            val stage = detectionStageSummaryOrNull() ?: return null
            val sessionId = requireNotNull(stage.sessionId)
            return LocalDetectionEvidenceProjection(
                session =
                    ScanSessionEntity(
                        id = sessionId,
                        profileId = DetectionStageProfileId,
                        pathMode = ScanPathMode.RAW_PATH.name,
                        serviceMode = null,
                        status = "completed",
                        summary = stage.summary,
                        reportJson = null,
                        startedAt = stage.wallClockMs?.let { now - it.coerceAtLeast(0L) } ?: now,
                        finishedAt = now,
                        launchOrigin = DiagnosticsHomeDetectionLaunchOriginStorageValue,
                    ),
                results = detectionDecisionSignals.toLocalDetectionProbeResults(sessionId, now),
            )
        }

        private fun DiagnosticsHomeCompositeOutcome.withLocalDetectionEvidenceSession(
            now: Long,
        ): DiagnosticsHomeCompositeOutcome {
            val stage = detectionStageSummaryOrNull()
            return if (stage == null || detectionSignalCount == null) {
                this
            } else {
                val sessionId = stage.sessionId ?: localDetectionSessionId(now)
                val updatedStages =
                    stageSummaries.map { summary ->
                        if (summary.stageKey == DetectionStageKey) {
                            summary.copy(sessionId = sessionId)
                        } else {
                            summary
                        }
                    }
                copy(
                    stageSummaries = updatedStages,
                    bundleSessionIds = (bundleSessionIds + sessionId).distinct(),
                )
            }
        }

        private fun DiagnosticsHomeCompositeOutcome.detectionStageSummaryOrNull() =
            stageSummaries.firstOrNull { stage ->
                stage.stageKey == DetectionStageKey &&
                    stage.status == DiagnosticsHomeCompositeStageStatus.COMPLETED
            }

        private fun DiagnosticsHomeCompositeOutcome.localDetectionSessionId(now: Long): String =
            "home-${runId.ifBlank { now.toString() }}-detection"

        private fun List<HomeDetectionDecisionSignal>.toLocalDetectionProbeResults(
            sessionId: String,
            now: Long,
        ): List<ProbeResultEntity> =
            mapIndexed { index, signal ->
                ProbeResultEntity(
                    id = "$sessionId-signal-$index",
                    sessionId = sessionId,
                    probeType = "home_detection_signal",
                    target = signal.category.name,
                    outcome = signal.semantics.name,
                    detailJson = json.encodeToString(HomeDetectionDecisionSignal.serializer(), signal),
                    createdAt = now + index,
                )
            }
    }

private data class LocalDetectionEvidenceProjection(
    val session: ScanSessionEntity,
    val results: List<ProbeResultEntity>,
)

@Serializable
private data class PersistedHomeDiagnosticsRunPayload(
    val version: Int = PersistedHomeRunPayloadVersion,
    val runId: String,
    val status: String,
    val fingerprintHash: String? = null,
    val actionable: Boolean,
    val headline: String,
    val summary: String,
    val recommendationSummary: String? = null,
    val confidenceSummary: String? = null,
    val coverageSummary: String? = null,
    val appliedSettings: List<DiagnosticsAppliedSetting> = emptyList(),
    val recommendedSessionId: String? = null,
    val stageSummaries: List<PersistedHomeStageSummary> = emptyList(),
    val completedStageCount: Int = 0,
    val failedStageCount: Int = 0,
    val skippedStageCount: Int = 0,
    val bundleSessionIds: List<String> = emptyList(),
    val detection: PersistedHomeDetectionEvidence? = null,
    val actionableHeadline: String? = null,
    val actionableNextSteps: List<String> = emptyList(),
    val connectivityAssessment: ConnectivityAssessment? = null,
    val internetLossReproAction: HomeReproAction? = null,
    val packetCaptureDisposition: PersistedPacketCaptureDisposition =
        PersistedPacketCaptureDisposition.notRequested(),
) {
    fun toOutcomeOrNull(expectedRunId: String): DiagnosticsHomeCompositeOutcome? =
        parsePersistedHomeRunOrNull {
            toOutcomeOrThrow(expectedRunId)
        }

    private fun toOutcomeOrThrow(expectedRunId: String): DiagnosticsHomeCompositeOutcome {
        validatePayloadIdentity(expectedRunId)
        val bundleIds = validatedBundleSessionIds()
        val stages = validatedStages(bundleIds)
        validateStageSessionIds(stages, bundleIds)
        validateStageCounts(stages)
        invalidIf(recommendedSessionId != null && recommendedSessionId !in bundleIds)
        val detectionEvidence = validatedDetectionEvidence(stages)
        val restoredPacketCaptureDisposition = packetCaptureDisposition.toOutcomeOrNull().orInvalid()
        return DiagnosticsHomeCompositeOutcome(
            runId = runId,
            fingerprintHash = fingerprintHash,
            actionable = actionable,
            headline = headline,
            summary = summary,
            recommendationSummary = recommendationSummary,
            confidenceSummary = confidenceSummary,
            coverageSummary = coverageSummary,
            appliedSettings = appliedSettings,
            recommendedSessionId = recommendedSessionId,
            stageSummaries = stages,
            completedStageCount = completedStageCount,
            failedStageCount = failedStageCount,
            skippedStageCount = skippedStageCount,
            bundleSessionIds = bundleIds,
            detectionVerdict = detectionEvidence.verdict,
            detectionRuleApplied = detectionEvidence.ruleApplied,
            detectionEvidenceScopes = detectionEvidence.scopes,
            detectionSignalCount = detectionEvidence.signalCount,
            detectionDecisionSignals = detectionEvidence.decisionSignals,
            actionableHeadline = actionableHeadline,
            actionableNextSteps = actionableNextSteps,
            connectivityAssessment = connectivityAssessment,
            internetLossReproAction = internetLossReproAction,
            packetCaptureDisposition = restoredPacketCaptureDisposition,
        )
    }

    private fun validatePayloadIdentity(expectedRunId: String) {
        invalidIf(version != PersistedHomeRunPayloadVersion)
        invalidIf(runId != expectedRunId)
        invalidIf(status != PersistedHomeRunStatusCompleted)
    }

    private fun validatedBundleSessionIds(): List<String> {
        invalidIf(bundleSessionIds.distinct().size != bundleSessionIds.size)
        return bundleSessionIds
    }

    private fun validatedStages(bundleIds: List<String>): List<DiagnosticsHomeCompositeStageSummary> {
        val stages = stageSummaries.map { it.toStageSummaryOrInvalid(bundleIds) }
        invalidIf(stages.distinctBy(DiagnosticsHomeCompositeStageSummary::stageKey).size != stages.size)
        return stages
    }

    private fun validateStageSessionIds(
        stages: List<DiagnosticsHomeCompositeStageSummary>,
        bundleIds: List<String>,
    ) {
        val stageSessionIds = stages.mapNotNull(DiagnosticsHomeCompositeStageSummary::sessionId)
        invalidIf(stageSessionIds.distinct().size != stageSessionIds.size)
        invalidIf(stageSessionIds.toSet() != bundleIds.toSet())
    }

    private fun validateStageCounts(stages: List<DiagnosticsHomeCompositeStageSummary>) {
        invalidIf(completedStageCount != stages.count { it.status == DiagnosticsHomeCompositeStageStatus.COMPLETED })
        invalidIf(
            failedStageCount !=
                stages.count {
                    it.status == DiagnosticsHomeCompositeStageStatus.FAILED ||
                        it.status == DiagnosticsHomeCompositeStageStatus.UNAVAILABLE
                },
        )
        invalidIf(skippedStageCount != stages.count { it.status == DiagnosticsHomeCompositeStageStatus.SKIPPED })
        invalidIf(completedStageCount + failedStageCount + skippedStageCount != stages.size)
    }

    private fun validatedDetectionEvidence(
        stages: List<DiagnosticsHomeCompositeStageSummary>,
    ): PersistedHomeDetectionOutcomeEvidence {
        val detectionStageCompleted =
            stages.firstOrNull { it.stageKey == DetectionStageKey }?.status ==
                DiagnosticsHomeCompositeStageStatus.COMPLETED
        invalidIf(detectionStageCompleted != (detection != null))
        return detection?.toOutcomeEvidenceOrInvalid(stages) ?: PersistedHomeDetectionOutcomeEvidence()
    }

    companion object {
        fun from(outcome: DiagnosticsHomeCompositeOutcome): PersistedHomeDiagnosticsRunPayload =
            PersistedHomeDiagnosticsRunPayload(
                runId = outcome.runId,
                status = PersistedHomeRunStatusCompleted,
                fingerprintHash = outcome.fingerprintHash,
                actionable = outcome.actionable,
                headline = outcome.headline,
                summary = outcome.summary,
                recommendationSummary = outcome.recommendationSummary,
                confidenceSummary = outcome.confidenceSummary,
                coverageSummary = outcome.coverageSummary,
                appliedSettings = outcome.appliedSettings,
                recommendedSessionId = outcome.recommendedSessionId,
                stageSummaries = outcome.stageSummaries.map(PersistedHomeStageSummary::from),
                completedStageCount = outcome.completedStageCount,
                failedStageCount = outcome.failedStageCount,
                skippedStageCount = outcome.skippedStageCount,
                bundleSessionIds = outcome.bundleSessionIds,
                detection =
                    outcome.detectionSignalCount?.let {
                        PersistedHomeDetectionEvidence.from(outcome)
                    },
                actionableHeadline = outcome.actionableHeadline,
                actionableNextSteps = outcome.actionableNextSteps,
                connectivityAssessment = outcome.connectivityAssessment,
                internetLossReproAction = outcome.internetLossReproAction,
                packetCaptureDisposition = PersistedPacketCaptureDisposition.from(outcome.packetCaptureDisposition),
            )
    }
}

@Serializable
private data class PersistedHomeStageSummary(
    val stageKey: String,
    val stageLabel: String,
    val profileId: String,
    val pathMode: String? = null,
    val evidenceType: String,
    val vantage: String,
    val targetReachability: String,
    val sessionId: String? = null,
    val status: String,
    val headline: String,
    val summary: String,
    val recommendationContributor: Boolean = false,
    val wallClockMs: Long? = null,
    val passiveVpnRouteEvidence: NetworkPathValidationEvidence? = null,
) {
    fun toStageSummaryOrNull(bundleSessionIds: List<String>): DiagnosticsHomeCompositeStageSummary? =
        parsePersistedHomeRunOrNull {
            toStageSummaryOrInvalid(bundleSessionIds)
        }

    fun toStageSummaryOrInvalid(bundleSessionIds: List<String>): DiagnosticsHomeCompositeStageSummary {
        invalidIf(!ArchiveStageKeyRegex.matches(stageKey))
        invalidIf(sessionId != null && sessionId !in bundleSessionIds)
        val parsedPathMode = pathMode?.let { enumValueOrInvalid<ScanPathMode>(it) }
        val parsedEvidenceType = enumValueOrInvalid<HomeCompositeStageEvidenceType>(evidenceType)
        val parsedVantage = enumValueOrInvalid<HomeCompositeStageVantage>(vantage)
        val parsedTargetReachability = enumValueOrInvalid<HomeCompositeTargetReachability>(targetReachability)
        val parsedStatus = enumValueOrInvalid<DiagnosticsHomeCompositeStageStatus>(status)
        val restoredPassiveVpnEvidence =
            validatedPassiveVpnEvidence(
                evidenceType = parsedEvidenceType,
                status = parsedStatus,
            )
        val canonicalIdentity = CanonicalHomeStageIdentities[stageKey].orInvalid()
        invalidIf(
            profileId != canonicalIdentity.profileId ||
                parsedPathMode != canonicalIdentity.pathMode ||
                parsedEvidenceType != canonicalIdentity.evidenceType ||
                parsedVantage != canonicalIdentity.vantage ||
                parsedTargetReachability != canonicalIdentity.targetReachability,
        )
        return DiagnosticsHomeCompositeStageSummary(
            stageKey = stageKey,
            stageLabel = stageLabel,
            profileId = profileId,
            pathMode = parsedPathMode,
            evidenceType = parsedEvidenceType,
            vantage = parsedVantage,
            targetReachability = parsedTargetReachability,
            sessionId = sessionId,
            status = parsedStatus,
            headline = headline,
            summary = summary,
            recommendationContributor = recommendationContributor,
            wallClockMs = wallClockMs,
            passiveVpnRouteEvidence = restoredPassiveVpnEvidence,
        )
    }

    private fun validatedPassiveVpnEvidence(
        evidenceType: HomeCompositeStageEvidenceType,
        status: DiagnosticsHomeCompositeStageStatus,
    ): NetworkPathValidationEvidence? =
        when {
            evidenceType != HomeCompositeStageEvidenceType.PASSIVE_VPN_ROUTE -> {
                invalidIf(passiveVpnRouteEvidence != null)
                null
            }

            status == DiagnosticsHomeCompositeStageStatus.SKIPPED && passiveVpnRouteEvidence == null -> {
                null
            }

            else -> {
                invalidIf(
                    status !in
                        setOf(
                            DiagnosticsHomeCompositeStageStatus.COMPLETED,
                            DiagnosticsHomeCompositeStageStatus.SKIPPED,
                        ),
                )
                val evidence = passiveVpnRouteEvidence.orInvalid()
                invalidIf(evidence.sanitizeVpnRouteEvidenceForArchive() != evidence)
                val route = evidence.vpnRouteEvidence.orInvalid()
                if (status == DiagnosticsHomeCompositeStageStatus.COMPLETED) {
                    invalidIf(evidence.captureStatus != "captured")
                    invalidIf(route.lifecycleGeneration == null || route.lifecycleGeneration <= 0L)
                    invalidIf(route.lifecycleState !in setOf("established", "bridge_ready"))
                    invalidIf(route.callbackState != "complete")
                    invalidIf(route.ownerVerification != "verified")
                    invalidIf(route.routeConsistency != "consistent")
                    invalidIf(route.ownPackageExcluded != true)
                    invalidIf(
                        route.forwardingOutcome != "unavailable" &&
                            route.forwardingLifecycleGeneration != route.lifecycleGeneration,
                    )
                } else {
                    invalidIf(evidence.captureStatus != "rejected")
                }
                evidence
            }
        }

    companion object {
        fun from(stage: DiagnosticsHomeCompositeStageSummary): PersistedHomeStageSummary =
            PersistedHomeStageSummary(
                stageKey = stage.stageKey,
                stageLabel = stage.stageLabel,
                profileId = stage.profileId,
                pathMode = stage.pathMode?.name,
                evidenceType = stage.evidenceType.name,
                vantage = stage.vantage.name,
                targetReachability = stage.targetReachability.name,
                sessionId = stage.sessionId,
                status = stage.status.name,
                headline = stage.headline,
                summary = stage.summary,
                recommendationContributor = stage.recommendationContributor,
                wallClockMs = stage.wallClockMs,
                passiveVpnRouteEvidence =
                    stage.passiveVpnRouteEvidence?.sanitizeVpnRouteEvidenceForArchive(),
            )
    }
}

@Serializable
private data class PersistedHomeDetectionEvidence(
    val stageKey: String,
    val verdict: String,
    val ruleApplied: String? = null,
    val signalCount: Int,
    val evidenceScopes: List<String> = emptyList(),
    val decisionSignals: List<HomeDetectionDecisionSignal> = emptyList(),
) {
    fun toOutcomeEvidenceOrNull(
        stages: List<DiagnosticsHomeCompositeStageSummary>,
    ): PersistedHomeDetectionOutcomeEvidence? =
        parsePersistedHomeRunOrNull {
            toOutcomeEvidenceOrInvalid(stages)
        }

    fun toOutcomeEvidenceOrInvalid(
        stages: List<DiagnosticsHomeCompositeStageSummary>,
    ): PersistedHomeDetectionOutcomeEvidence {
        val stage =
            stages
                .firstOrNull { it.stageKey == stageKey }
                .orInvalid()
        invalidIf(stage.status != DiagnosticsHomeCompositeStageStatus.COMPLETED)
        invalidIf(signalCount != decisionSignals.size)
        val parsedVerdict = enumValueOrInvalid<DiagnosticsHomeDetectionVerdict>(verdict)
        val declaredScopes =
            evidenceScopes.map { value ->
                enumValueOrInvalid<DetectionScope>(value)
            }
        invalidIf(declaredScopes.distinct().size != declaredScopes.size)
        invalidIf(decisionSignals.any { !it.isContractValid() })
        val validatedSignals = decisionSignals
        val signalScopes =
            validatedSignals
                .map(HomeDetectionDecisionSignal::scope)
                .map(DetectionScope::valueOf)
                .distinct()
        invalidIf(declaredScopes != signalScopes)
        return PersistedHomeDetectionOutcomeEvidence(
            verdict = parsedVerdict,
            ruleApplied = ruleApplied,
            signalCount = validatedSignals.size,
            scopes = signalScopes,
            decisionSignals = validatedSignals,
        )
    }

    companion object {
        fun from(outcome: DiagnosticsHomeCompositeOutcome): PersistedHomeDetectionEvidence =
            PersistedHomeDetectionEvidence(
                stageKey = DetectionStageKey,
                verdict = requireNotNull(outcome.detectionVerdict).name,
                ruleApplied = outcome.detectionRuleApplied,
                signalCount = requireNotNull(outcome.detectionSignalCount),
                evidenceScopes = outcome.detectionEvidenceScopes.map(DetectionScope::name),
                decisionSignals = outcome.detectionDecisionSignals,
            )
    }
}

private data class PersistedHomeDetectionOutcomeEvidence(
    val verdict: DiagnosticsHomeDetectionVerdict? = null,
    val ruleApplied: String? = null,
    val signalCount: Int? = null,
    val scopes: List<DetectionScope> = emptyList(),
    val decisionSignals: List<HomeDetectionDecisionSignal> = emptyList(),
)

@Serializable
private data class PersistedPacketCaptureDisposition(
    val requested: Boolean,
    val outcome: DiagnosticsHomePacketCaptureOutcome,
    val totalDrops: Long? = null,
    val failureCode: String? = null,
) {
    fun toOutcomeOrNull(): DiagnosticsHomePacketCaptureDisposition? =
        runCatching {
            toOutcomeOrInvalid()
        }.getOrNull()

    fun toOutcomeOrInvalid(): DiagnosticsHomePacketCaptureDisposition =
        DiagnosticsHomePacketCaptureDisposition(
            requested = requested,
            outcome = outcome,
            captureSetId = null,
            totalDrops = totalDrops,
            failureCode = failureCode,
        )

    companion object {
        fun notRequested(): PersistedPacketCaptureDisposition =
            PersistedPacketCaptureDisposition(
                requested = false,
                outcome = DiagnosticsHomePacketCaptureOutcome.NOT_REQUESTED,
            )

        fun from(disposition: DiagnosticsHomePacketCaptureDisposition): PersistedPacketCaptureDisposition =
            PersistedPacketCaptureDisposition(
                requested = disposition.requested,
                outcome = disposition.outcome,
                totalDrops = disposition.totalDrops,
                failureCode = disposition.failureCode,
            )
    }
}

private data class CanonicalHomeStageIdentity(
    val profileId: String,
    val pathMode: ScanPathMode?,
    val evidenceType: HomeCompositeStageEvidenceType,
    val vantage: HomeCompositeStageVantage,
    val targetReachability: HomeCompositeTargetReachability,
)

private const val PersistedHomeRunPayloadVersion = 2
private const val PersistedHomeRunOrigin = "home_composite"
private const val PersistedHomeRunStatusCompleted = "completed"
private const val DetectionStageKey = "detection_signals"
private val ArchiveStageKeyRegex = Regex("^[a-z0-9_]+$")
private val CanonicalHomeStageIdentities =
    (HomeCompositeStageSpecs + QuickScanStageSpecs).associate { spec ->
        spec.key to
            CanonicalHomeStageIdentity(
                profileId = spec.profileId,
                pathMode = spec.pathMode,
                evidenceType = spec.evidenceType,
                vantage = spec.vantage,
                targetReachability = spec.targetReachability,
            )
    }

private class InvalidPersistedHomeRun : RuntimeException()

private inline fun <T> parsePersistedHomeRunOrNull(block: () -> T): T? =
    try {
        block()
    } catch (_: InvalidPersistedHomeRun) {
        null
    }

private fun invalidIf(condition: Boolean) {
    if (condition) throw InvalidPersistedHomeRun()
}

private fun <T : Any> T?.orInvalid(): T = this ?: throw InvalidPersistedHomeRun()

private inline fun <reified T : Enum<T>> enumValueOrInvalid(value: String): T =
    runCatching { enumValueOf<T>(value) }.getOrNull().orInvalid()
