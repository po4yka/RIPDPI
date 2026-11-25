# ADR 0011: core/diagnostics code decisions

> Status: Accepted. Decision date: 2026-11-25.

## Context

These decisions moved out of source comments in `core/diagnostics/`. Each section names the code that the decision governs.

## Decisions

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/AndroidLocalNetworkContextCollector.kt#AndroidLocalNetworkContextCollector.after

A selection change must drop all of a mixed-subscription observation. No part of it can stay.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/BypassCombinationScorer.kt#BypassCombinationScorer.candidateComparator

A validated remembered policy ranks above only those fresh results that could not be saved.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/ComparisonScanCoordinator.kt#Companion.deriveAssessmentCode

Only a foreign control proves the path that the affected targets use. A domestic control does not prove it.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/ComparisonScanCoordinator.kt#ControlTierOutcome

Controls are grouped by where the control is hosted. A control without a tier counts as foreign. A report with no tier at all (an older catalog) keeps its earlier verdicts.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/ComparisonScanCoordinator.kt#Companion.otherForeign

The international link is the problem only when every foreign control failed. An inconclusive control is not proof of a broken link.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/ComparisonScanCoordinator.kt#ControlTierOutcome.bucket

Plain HTTP cannot clear a control whose TLS certificate failed.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/ConfirmGoodDpiEvidenceCollector.kt#relayConnected

The relay listener needs the session credentials that its owner registered.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/ConnectionStageEvidence.kt#ConnectionStageEvidence

Read one value only. A duplicate key is ambiguous, also when the values are equal.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/ConnectionStageProjection.kt#toConnectionStageScale

The projection shows measured facts. It does not join independent connections and does not infer the intent of the provider.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/CurrentStrategyVerdictEvaluator.kt#activeControlTransferStage

Each control checks a transfer. One blocked control provider must not hide a path that works.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DefaultDiagnosticsTimelineSource.kt#DefaultDiagnosticsTimelineSource.activeConnectionSession

The diagnostics subscribers own the Room flow, not the process. This stops a DAO invalidation observer from living after a UI or test database closes. The replay ends with the last subscriber. Then a returning UI cannot query live artifacts for an old session before Room refreshes the current one. The cost is an initial null or loading state and one database read each time collection resumes.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DefaultDiagnosticsTimelineSource.kt#DefaultDiagnosticsTimelineSource.approachStats

The flow decodes up to 200 stored reports. It runs again on each usage-session upsert while the service runs. Keep it off the dispatcher of the collector, which is often Main.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DeviceStateEventRecorder.kt#DefaultDeviceStateEventRecorder.failure

A broadcast callback has no caller to report to, and the application scope has no handler. A failed evidence write must not kill the process.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsArchiveFailure.kt#DiagnosticsArchiveFailureCode

The failure codes are shown in the UI and copied to the clipboard. They must not contain exception messages, paths, or identifiers.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsBoundaryMapper.kt#decodeStoredOrNull

A stored row from another schema version, or a corrupt row, becomes a missing projection. It must not fail the whole Room flow that maps it.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsBoundarySources.kt#DefaultDiagnosticsHistorySource.observeDiagnosticsSessions

Each row decodes its stored report JSON. Keep this work off the dispatcher of the collector.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsBoundarySources.kt#DefaultDiagnosticsRememberedPolicySource.deletePolicy

Purge the per-network learned state only when no other mode refers to the network scope. If a policy for the same fingerprint stays, purging would orphan it from its DNS-substrate learning. The clear-all path purges every companion per-network store in the same way, so no learned state stays behind.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsBoundarySources.kt#DefaultDiagnosticsActiveConnectionPolicySource.activePolicies

The source starts eagerly because it is a singleton bound to ApplicationIoScope. Active connection policies must be available at once when a consumer queries it. WhileSubscribed would give the first subscriber a stale initial value.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsContractCodecs.kt#StoredDiagnosticsReportSchemaVersions

Version 11 only adds an optional request field, so version 10 reports decode without change.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsHomeVerification.kt#VerificationApplicationProbeTypes

A DNS answer or a QUIC Initial response alone does not show application access.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsLocalNetworkPreflight.kt#LocalNetworkScanFilter.validInitialTarget

Native input validation reports malformed Initial targets without opening a socket.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsLocalNetworkPreflight.kt#LocalNetworkScanFilter.admitSingleEndpointProbes

Probes that carry one endpoint each are admitted after the target lists. Then deferred rows keep their order.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsLocalNetworkPreflight.kt#LocalNetworkScanFilter.peers

The target order mirrors native ordered_connect_targets: legacy address, preferred edges, then hostname fallback.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsLocalNetworkReport.kt#withLocalNetworkDeferrals

App preflight outcomes are capability omissions. They are never native probe execution or DPI evidence.

A recommendation cannot claim coverage of the original target set, because part of that set did not run.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsManager.kt#DiagnosticsHomePacketCaptureDisposition.failureCode

A live capture lease carries captureSetId. The durable and archive-safe projections omit it. When the export could not confirm capture retirement, the report records that fact. captureSetId stays process-local so that recovery keeps exact ownership. The persisted privacy-safe projection restores the terminal facts only.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsManager.kt#DiagnosticsManagerModule.bindProbeResultCacheTrimmable

This hook lets RipDpiApp.onTrimMemory shed the in-memory probe cache when the app goes to the background (Android 17 per-app memory cap). The persisted file stays. Only the regenerable in-memory map is dropped. See TrimmableCache.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsOutcomeTaxonomy.kt#bucketDnsIntegrity

No usable answer is no evidence of interference (outcome.rs).

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsOutcomeTaxonomy.kt#bucketStrategyFailureClassification

A capability-skipped probe is not a network failure. A required platform feature was missing at run time (for example TTL write). Show it as Inconclusive so that the UI does not show a false negative.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsOutcomeTaxonomy.kt#eventLevelOverride

Some Failed outcomes are expected censorship or policy findings, not infrastructure faults. At ERROR level they make logcat look like a crash, so they log at WARN. This mirrors ripdpi-diagnostics-contracts/src/util/outcome_policy.rs::event_level_override.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsScanStartRejectedException.kt#ProfileUnreadable

The stored profile request cannot be decoded, so its legal-safety policy is unknown.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsTargetOptimizer.kt#Scope

Evidence is kept for each host. Profiles that share a probe suite share the evidence. Unseen hosts stay mandatory.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsTargetOptimizer.kt#DiagnosticsTargetOptimizer.scope

Selecting a profile to run it by hand must not hide that run from the automatic profile.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsTargetOptimizer.kt#Evidence.members

A complete registered cohort keeps its own concurrency contract.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DirectModePolicySupport.kt#hasDefinitiveDirectModeEvidence

Only a raw-path scan measures the direct path. An in-path scan ran through the active tunnel or proxy.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DirectModePolicySupport.kt#buildVerdict

A TLS failure alone proves neither claim. A local strategy can still work, so emit no verdict.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DirectModePolicySupport.kt#hasTlsHandshakeFailureEvidence

An alert of unknown origin is evidence of a response only.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DnsResponseSemantics.kt#DnsResponseSemantics

AD and EDE come from the resolver. The app does not verify them locally.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DnsSemanticsSummary.kt#dnsSemanticsSummaryLines

Only validated response codes, flags, and counts go into the text export.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/FileLogWriter.kt#Companion.lineBytes

Use logcat directly. Kermit would call this failed writer again.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/HomeAnalysisAugmentationSource.kt#HomeAnalysisAugmentationSource

The implementations live in the app layer. This keeps core:diagnostics free of Android Context and network-IO dependencies.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/HomeCompositeStageDefinitions.kt#HomeCompositeStageSpecs

Keep the stable profile id for catalog and session compatibility.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/LocalNetworkAssessment.kt#LocalConstraintCode

The assessment lists observed conditions to review. It does not diagnose a provider restriction.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/LocalNetworkSummary.kt#localNetworkSummaryLines

The summary has fixed fields only. Never copy Android identifiers or exception messages into it.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/LogcatSnapshotCollector.kt#LogcatSnapshot.preCollectionRingLoss

Android can evict records from its ring buffer before collection starts. The collector cannot measure that loss, so it must not infer it from droppedByteCount.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/LogcatSnapshotCollector.kt#LogcatSnapshotCollector.capture

With sinceTimestampMs the collector uses the logcat -T flag. Logs of long diagnostic scans (300 s and more) then survive the circular buffer rotation that happens between scan start and archive export. Without it the collector reads the current buffer. The collector filters to the own PID so that it never captures logs of other apps.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/LogcatSnapshotCollector.kt#LogcatCommandTimedOut.process

Standard error is outside the capture contract and must never block the child process.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/LogcatSnapshotCollector.kt#buildLogcatCommand

Archive collection stays limited to this app process. There is no unrestricted logcat fallback.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/NetworkMetadataProvider.kt#NetworkMetadataProvider.kt

The legacy CDMA, 2G, and 3G network type constants have no replacement. These networks are end of life.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/NetworkMetadataProvider.kt#HttpPublicIpInfoResolver.formatAsn

ipinfo.io returns the org as "AS12389 Rostelecom". Keep the full value, but strip the raw IP that can leak through other fields.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/NetworkMetadataProvider.kt#AndroidNetworkMetadataProvider.systemPrivateDnsStatusProvider

The system Private DNS status is read behind an injectable seam. Then the status mapping can be unit-tested without a device. The default implementation has no dependencies, so it is constructed inline and not wired through Hilt.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/NetworkMetadataProvider.kt#AndroidNetworkMetadataProvider.resolveWifiDetails

connectionInfo and dhcpInfo have no modern replacement. The API 29 and later path is already preferred.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/NetworkPathValidationSource.kt#AndroidNetworkPathValidationSource.publishLock

Callbacks and both collectors publish at the same time. Capture and send under one lock. Then the last evidence sent is always the last one captured.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/NetworkTransitionTimeline.kt#NetworkTransitionEvent

A callback fact is privacy-safe. Network handles and link-layer values never cross this boundary.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/NetworkTransitionTimeline.kt#NetworkTransitionTimeline

The callback lane has one consumer and is bounded. Callback producers use Channel.trySend, so they never block the callback thread of ConnectivityManager. A terminal consumer can enqueue a barrier with a bounded wait. Later callback events cannot displace the barrier.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/PmtuDiagnosis.kt#diagnosePmtu

The value is a lower bound for outbound packets only. It is never a tunnel setting or an exact path maximum.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/ProbeResultCache.kt#DefaultProbeResultCache.trimToBackground

Dropping the in-memory copy leaves the persisted file untouched. The next lookup or store reloads it from disk. So this only sheds regenerable memory, as the Android TRIM_MEMORY_UI_HIDDEN and TRIM_MEMORY_BACKGROUND callbacks ask. The call does not block and is safe on the main thread. The entries field is volatile so the null write is visible to concurrent suspend callers. They reload on their next access.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/QuicVersionMatrixModels.kt#isQuicSni

The SNIs go into the ClientHello, so both must be DNS names.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/QuicVersionMatrixModels.kt#QuicVersionMatrixEvidence

The exported evidence of a matrix row holds no SNI, local port, or timing.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/QuicVersionMatrixModels.kt#QuicResidualEvidence

The test SNI and then the control SNI go on one socket. The cell cannot tell same-flow state from endpoint-wide state.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/QuicVersionMatrixModels.kt#canonicalQuicVersionMatrixEvidence

The ordinary export keeps validated, re-encoded evidence only.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/QuicVersionMatrixModels.kt#quicVersionWithoutScopeAuthority

A change of network scope withdraws every version claim and keeps the cells.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/RememberedPolicySessionTracker.kt#RememberedPolicySessionTracker.policy

Only a replayed remembered policy is tracked. The time and traffic of a session do not show that a strategy unblocks anything. So an ordinary session neither creates nor validates a policy.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/RememberedPolicySessionTracker.kt#RememberedPolicySessionTracker.entity

A clean session counts as a success, but only probe evidence validates a policy. The status and lastValidatedAt stay as they are, so the policy still expires on schedule.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/RememberedPolicySessionTracker.kt#RememberedPolicyTerminalOutcome.applyTo

The update applies the session result to the row as stored now, so updates that other sessions made since this one started are kept. It returns null when the row is of another mode. Outbox JSON from older builds has extra counter keys. The tolerant decoder ignores them.

Only the proved branch of prepareTerminalOutcome updates the strategy signature.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/RuntimeRootCauseAssessment.kt#CanonicalNetworkTransitionSelection.terminalDataPlaneEvent

Process and device-state events are supporting context until they have exit evidence correlated to the session.

DNS and relay verdicts stay fail-closed until their producers emit an allowlisted kind.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/RuntimeSessionCoordinator.kt#RuntimeSessionCoordinator.hadActiveSession

The finalize flush can throw on a stuck outbox. The reconnect must still be recorded.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/RuntimeSessionCoordinator.kt#RuntimeSessionCoordinator.handleStatusChange

A stuck outbox blocks the new session. No later event retries it while the service stays Running.

A stuck outbox must not keep this run open. Otherwise the next start reuses the run.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/RuntimeSessionCoordinator.kt#RuntimeSessionCoordinator.startSampling

Serialize the check and the launch. Then two concurrent service-running events cannot both see an inactive job and start duplicate sampler loops.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/RuntimeSessionCoordinator.kt#RuntimeSessionCoordinator.intervalSeconds

The application scope has no handler. A failed tick must not kill the process.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/RuntimeTerminalOutbox.kt#RuntimeTerminalOutbox.finalizeRememberedPolicy

The outcome is a delta, so it must reach the row once. The checkpoint records policyApplied in the same transaction. A replay of this phase (process death before SESSION_UPSERT) skips it. The checkpoint applies the outcome to the row that it reads in its own transaction.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/RuntimeTerminalOutbox.kt#TerminalOutboxMarker.policyApplied

The flag is true once the policy checkpoint applied the outcome. It is absent in markers that were written before the flag existed.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/SelectiveMatrixEvidence.kt#selectiveMatrixSummaryLines

Target identifiers stay opaque. The archive text redaction removes user hostnames and source URLs.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/SelectiveMatrixModels.kt#normalizeSelectiveMatrixHost

The check is syntax validation only. The native runner resolves the target and rejects non-public destinations before it connects.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/SelectiveMatrixPairing.kt#targetOutcomes

Rows key on the opaque target id only. An attempt without an id is not paired, so no hostname becomes a key.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/StageScaleSummary.kt#connectionStageSummaryLines

Export only enum identifiers and bounded numeric observations from the shared projection. Keep the measured stage facts before source identifiers are removed from the archive report.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/StrategyCandidateApplier.kt#StrategyCandidateApplier.apply

Validate and apply against one current value. Then concurrent settings changes are kept.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/StrategyCandidateApplier.kt#StrategyCandidateApplier.applicableReport

A partial test, or a test from another network (or an old row that does not name one), shows nothing about the strategy that this network needs.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/StrategyProbeRunGuard.kt#StrategyProbeRunGuard

Only one run at a time can activate a strategy. A second activation would capture the candidate of the first run as the settings to restore. Callers fail at once and do not wait.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/StrategyProbeRunGuard.kt#replayableStrategyCandidateIds

Scan results carry native candidate ids. So only the ids that StrategyProbeActivator can activate from DefaultStrategyProbeCandidateProvider can be replayed under their strategy.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/StrategyProbeService.kt#DefaultStrategyProbeService.run

One run at a time. An overlapping run would capture the candidate of the other run as the original. A second run fails at once and does not wait, because a collector that starts a run would deadlock.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/StrategyProbeService.kt#SettingsStrategyProbeActivator.restore

Revert only the fields that activate() wrote. Then edits made during the probe survive.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/SystemPrivateDnsDetector.kt#SystemPrivateDnsStatus

The status is computed in Kotlin only and is informational. It is not a policy source for the VPN DNS interceptor, and it does not prove that the interceptor sees every lookup. Android pushes the Private DNS mode to the VPN network too (AOSP DnsManager). In strict mode the system stub resolver sends DNS-over-TLS to the configured hostname through the tunnel and never queries the interceptor address. Domain rules and host mapping then do not apply to those lookups. The wire values are persisted and keep their historical names.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/TelegramSummary.kt#withTelegramEvidence

Project port numbers and known states before archive redaction removes dial addresses.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/TelegramSummary.kt#path

The final fields can contain untrusted report text. Emit only known tokens.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/TelegramTargetValidation.kt#validateForScan

Keep the input checks before the permission preflight, which can resolve a host.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/TlsHopComparisonModels.kt#TlsHopLocalKey

The local-history detail has the SNIs and the timing of each attempt. The ordinary export drops it.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/TlsHopComparisonModels.kt#isTlsHopSni

An IP literal sends no SNI, so both names must be DNS names.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/TlsHopComparisonModels.kt#canonicalTlsHopComparisonEvidence

The ordinary export keeps the summary only. The archive redactor drops TlsHopLocalKey.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/TlsHopComparisonModels.kt#withoutTlsHopScopeAuthority

A change of network scope withdraws the interval and keeps the attempts.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/TransferEvidenceSummary.kt#transferEvidenceSummaryLines

Only validated numeric measurements and known stop reasons go into the text export.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/AutomaticProbeScheduler.kt#AutomaticProbeScheduler.launchedDeliveries

These are the deliveries for which this scheduler started a probe and that are not settled yet. Their retries skip the eligibility check, so the launcher can report the terminal row, although the cooldown that was just recorded now rejects a fresh evaluation.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/AutomaticProbeScheduler.kt#AutomaticProbeScheduler.failure

The application scope has no handler. A durable-store failure ends this job only. The delivery stays pending, so the next schedule() for it retries.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/AutomaticProbeScheduler.kt#AutomaticProbeScheduler.outcome

Record the cooldown when a probe actually starts. Earlier launcher results returned true only for deliveries that were already settled. That left the map empty and let each new delivery for the same fingerprint bypass the cooldown.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DefaultDiagnosticsBootstrapper.kt#DefaultDiagnosticsBootstrapper.completed

A corrupted override or an unreadable bundled catalog must degrade to missing persisted profiles. It must not crash each startup caller.

A caller that is cancelled part-way leaves the remaining steps to the next initialize().

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DefaultDiagnosticsBootstrapper.kt#DefaultDiagnosticsBootstrapper.recoverInterruptedScanSessions

Process death during a scan leaves sessions stuck in the running state. The raw-path settlement barrier covers raw-path scans only. So sweep the remaining stale rows once for each process at startup.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DefaultDiagnosticsHomeCompositeRunService.kt#DefaultDiagnosticsHomeCompositeRunService.previousOutcome

The audit applies nothing when its session was measured on another network or on an unknown network. The full run and the Quick scan run report this through the same flag.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsBridgeExecutionServices.kt#BridgePollingService.awaitCompletion

A null poll means that there is no fresh engine progress yet. Publishing it would erase the seeded preparing state before the first real progress arrives.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsBridgeRetirement.kt#BridgeRetirementQueue

This owner lives for the whole process, for native bridge retirements that must outlive cancelled work scopes. Android has no reliable graceful process-termination callback. closeAndDrain exists for controlled component and test teardown. It closes admission before it joins the tracked work.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsHomeRunLease.kt#DiagnosticsHomeRunLease

The lease serializes Home ownership with scan admission and profile changes.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsNetworkScope.kt#DiagnosticsNetworkScope

The evidence is transient callback evidence. Copies of a prepared scan keep the original epoch.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsPlanningServices.kt#DefaultDiagnosticsPlanner.planned

A port outside the u16 range of the wire would reject the whole request.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsPlanningServices.kt#canonicalQuicProbeHostOrNull

Keep an explicit root label for the lookup. Native removes it for SNI.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanController.kt#DefaultDiagnosticsScanController.pendingRequest

Cancellation must propagate, so that structured concurrency sees that the scope of the caller was torn down. Converting it into Failed would resume a cancelled coroutine with an unrelated error result.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanController.kt#DefaultDiagnosticsScanController.failure

The application scope has no handler. A store failure while the scan records a failed or cancelled scan must not kill the process. Cleanup has already run in finally.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanController.kt#DefaultDiagnosticsScanController.executionJob

A failed cleanup write surfaces here and replaces any cancellation. Retry it once, so that the scan does not show as running until the next process. Raw-path scans are left to the settlement barrier, which owns their terminal record.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanExecutionCoordinator.kt#DiagnosticsScanExecutionCoordinator.reprobeFailure

Reserve the hidden scan slot and bind the execution job before the VPN-resume wait. Admission control (hasActiveScan, HiddenAutomaticProbeConflict) reads only the registry. So the reserved slot stops a manual or automatic start from being admitted into the resume window and probing the same network path at the same time as this re-probe.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanOrchestrationServices.kt#ScanAdmissionService.allowed

An undecodable profile fails closed. There is no automatic probe.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanOrchestrationServices.kt#ActiveScanRegistry.consumeCancelledSessionReport

After cancellation is signaled, poll the native bridge for up to graceMs. Native can first return a one-shot CHECKPOINT. Keep it as a fallback, but continue to poll for the TERMINAL report until the grace period ends.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanOrchestrationServices.kt#ActiveScanRegistry.cancellationRegistered

The execution job is still cancelled below so that registry cleanup can finish.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanOrchestrationServices.kt#ActiveScanRegistry.executionClaimedBySameSession

A cancellation that already claimed the bridge of this session is tearing it down. Registering the execution job would start a scan on a dying bridge when startup lands inside the cancel window. Claims from other sessions on a shared bridge instance do not block this session.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanOrchestrationServices.kt#ActiveScanRegistry.bridgeIsRegistered

takeReportJson() is a destructive native read. If cancellation won just before this completion poll, hand the consumed report to the cancellation owner. Otherwise the only copy of the partial report is lost.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanOrchestrationServices.kt#ActiveScanRegistry.updateProgress

Publish while holding bridgeMutex. A StateFlow assignment never blocks. Publishing outside the lock lets an older computation win the publication race and move the visible progress backward.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanOrchestrationServices.kt#BridgeReportPollingState.observe

Freeze at report acceptance, before completion or persistence can suspend.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanRequestFactory.kt#poyka

The endpoint templates of the catalog are empty, so user endpoints must replace them before any budget validation.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanRequestFactory.kt#withLiveRelayListenerCredentials

Probe runtimes that chain through the live native relay must present its session credentials. The native monitor reads them as input only. Reports and recommendations never serialize them.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanRequestFactory.kt#resolveProtectPath

The function returns the absolute path of the protect_path socket that VpnService.protect() uses, only while the scan stays inside the active VPN path. RAW_PATH orchestration stops the VPN runtime before it starts the native bridge. So its protect backend is not available on purpose and must not be advertised to native probes.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanWorkflow.kt#DiagnosticsScanWorkflow.decodeEngineConfig

The engine writes an unset option of a strategy config as an explicit null. The config model of the app reads a missing key as unset and rejects a null. So the nulls are dropped before an engine config is decoded. A config that the user supplies keeps the strict decoder, which rejects an explicit null.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanWorkflow.kt#BackgroundAutoPersistRejectionReason.hasTargetSuccess

The bar is the same as in the engine: at least half of the targets. Then one success out of several does not promote a candidate.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/HomeCompositeNetworkScope.kt#withoutNetworkAuthority

Keep historical stage records and local observations, without authority over the current network.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/HomeCompositeRunJobs.kt#HomeCompositeRunJobs.handlerFailure

The application scope has no handler. A failing failure handler must not kill the process.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/HomeCompositeStageExecutor.kt#HomeCompositeStageExecutor.settlement

A stage that settles as failed also yields null. Only a missing settlement is a timeout.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/contract/engine/EngineContract.kt#DiagnosticsEngineSchemaVersion

Schema version 10 makes the TCP and QUIC recommendation lanes optional. It must stay in sync with the Rust constant DIAGNOSTICS_ENGINE_SCHEMA_VERSION.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/crash/CrashReportReader.kt#AnySchemeUriRegex

Exception messages can carry proxy links, tokens, and addresses. Skip the logcat host-name rule here. It would also erase package names from the stack frames.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/crash/CrashReportWriter.kt#CrashReportWriter

This class has no Hilt dependencies. RipDpiApp.onCreate creates it by hand before DI is available. It is a synchronous writer that is installed as the default Thread.UncaughtExceptionHandler. It swallows each failure, because it must not stop the system handler from running.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/crash/CrashReportWriter.kt#CrashReportWriter.appendBreadcrumbs

Breadcrumb serialization must not break the crash report.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/DatagramSocketQuicUdpProbe.kt#resolveProbeAddress

Keep admission until the detached lookup finishes, also when its caller is cancelled.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/DnsHttpResponse.kt#readDnsBody

Inspect the decoded stream. Content-Length can be absent or can describe the encoded body.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/DnsHttpResponse.kt#readDnsResponse

Keep cancellation attached until the reader closes the body, also when a source read is blocked.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/DnsIntegrityChecker.kt#DnsIntegrityChecker.permits

Domains run in parallel and each UDP leg has a budget. On a network that drops UDP port 53, the sequential retry loop over six servers took about one minute for each domain.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/DnsIntegrityChecker.kt#dnsAnswersOverlap

IPv4 answers match when they share an address or a /24. This is the same rule as native classify_dns_answer_overlap. Different resolvers often steer to different edges of one CDN block.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/DnsJsonResponseParser.kt#DnsJsonResponseParser.negativeTtl

An SOA RNAME can escape a dot in the mailbox label.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/DnsJsonResponseParser.kt#DnsJsonResponseParser.ttl

RFC 2181 says that a received RR TTL with the high bit set is treated as zero.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/DnsWireBuilder.kt#DohTransactionId

HTTP correlates the exchange. A fixed ID permits DoH cache reuse (RFC 8484 section 4.1).

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/DomainReachabilityScanner.kt#DomainReachabilityScanner.isVersionInterference

A protocol_version or handshake_failure alert means that the origin declines that version or its ciphers (RFC 8446 section 6.2). It is not a middlebox. Only other failures point at version blocking.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/DomainReachabilityScanner.kt#OkHttpDomainReachabilityAttemptRunner.body

Count as the bytes arrive. Then a timeout in the middle of the body reports how far the stream got (TCP16 band).

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/DomainReachabilityScanner.kt#OkHttpDomainReachabilityAttemptRunner.response

readHttpResponse() did not return, so only the buffer knows how many bytes arrived.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/DpiTargetHosts.kt#dnsNameOrIpv4

Some resolvers treat noncanonical numeric names as abbreviated IPv4 literals.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/EchReadinessProbe.kt#EchReadinessProbe.BindingTimeoutMs

Native gets 10 seconds for each binding. The coroutine guard also bounds four injected or stalled bindings.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/HttpsDnsWireParser.kt#HttpsDnsWireParser.useDefaultAlpn

RFC 9460 sections 7.1.2 and 9.2 select a transport by ALPN overlap. TLS then offers its full TCP ALPN list.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/HttpsDnsWireParser.kt#isEchConfigList

RFC 9849 section 4. Validate the list and each entry envelope before the data goes to the TLS backend. The TLS backend validates the version-specific contents.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/QuicH3FingerprintProbe.kt#QuicProbeOutcome

Keep these wire values paired with the Rust JNI result codes.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/Tcp16FatHeaderProbe.kt#Tcp16FatHeaderProbe.requestedHost

Keep the authority constant for the whole connection.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/Tcp16FatHeaderProbe.kt#isPeerClose

Stack-trace recovery can wrap the IOException that OkHttp uses to report an EOF.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/Tcp16ProbeResult.kt#Tcp16ProbeResult.completedPaddingBytes

The count is of X-Pad value bytes from the successful requests on one connection. It excludes the failed request.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/Tcp16RequestRunner.kt#Tcp16ConnectionTracker.connectionPool

Use one pool for each target run. A pool that concurrent targets share evicted their keep-alive sockets.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/Tcp16RequestRunner.kt#OkHttpTcp16RequestRunner.startedNanos

Cancellation can close the socket also while this dispatcher is blocked in execute().

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/Tcp16RequestRunner.kt#OkHttpTcp16RequestRunner.hasDirectRoute

An HTTP or SOCKS proxy would choose the peer for the URL authority. That would invalidate the pin.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/Tcp16RequestRunner.kt#Companion.configure

The synthetic TLS name controls this probe. Keep the certificate chain checks.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/DohBootstrapSpoofingDetector.kt#resolveBlockingDohBootstrap

The system lookup can outlive its caller. The worker owns the slot until the lookup returns.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/DohBootstrapSpoofingDetector.kt#DohBootstrapSpoofingDetector.check

The system resolver and the geo metadata lookup (JNI and file I/O) both block.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/DohBootstrapSpoofingDetector.kt#DohBootstrapSpoofingDetector.results

Judge every answer. On a dual-stack network the first answer can be an IPv6 address that no table covers.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/DohBootstrapSpoofingDetector.kt#DohBootstrapSpoofingDetector.verdict

No DoH provider serves from a loopback, private, link-local, or reserved address. Such an address is a sinkhole.

When no source knows the address (no geoip.db, or an address outside the bundled /24 ranges), there is no evidence of spoofing. Report it as not verified.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/DohBootstrapSpoofingDetector.kt#DohBootstrapSpoofingDetector.address

classify() accepts IP literals only, so this getByName never does a DNS lookup.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/HttpCompressionProber.kt#HttpCompressionProber.decoded

Count as the stream is read. Then a failure in the middle of the body reports how far decoding got.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/HttpCompressionProber.kt#HttpCompressionProber.matchesContentEncoding

RFC 9110 section 8.4.1.3: x-gzip is the same coding as gzip.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/Ipv4WhitelistedSubnetDiscoverer.kt#classifyProbeFailure

Some JDKs report a handshake read timeout as an SSLException. A silent peer is unreachable. It is not a TLS failure.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/Ipv4WhitelistedSubnetDiscoverer.kt#Ipv4WhitelistedSubnetDiscoverer.cacheSubnets

Publish only a complete run. Then a failed or cancelled run keeps the previous cache. Cache file I/O, JSON, and sampling block, and collectors run on Main.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/Ipv4WhitelistedSubnetDiscoverer.kt#Ipv4WhitelistedSubnetDiscoverer.permits

A /24 that drops SYN packets costs one timeout for each wave, not one for each sampled address.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/Ipv4WhitelistedSubnetDiscoverer.kt#Ipv4WhitelistedSubnetDiscoverer.offsets

Draw offsets. Do not list each host. A /9 announcement would create about 8 million strings.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/Ipv4WhitelistedSubnetDiscoverer.kt#SubnetsCache.load

A torn or foreign file is a regenerable cache. Drop it. Do not fail each check.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/SnowflakeStunReachabilityProbe.kt#SnowflakeStunReachabilityProbe.paddedLength

The unauthenticated Binding check recognizes the two address attributes.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/exit/LastExitInspector.kt#ProcessExitHistoryItem.rawPid

Untrusted fields exist at the source boundary so that tests can prove that the persistence projector discards them. The Android source never copies trace content. It keeps only the boolean that comes from the description.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/exit/LastExitInspector.kt#Companion.DescriptionMarker

The marker is an Android 17 marker that is checked locally. Its description is never persisted.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveExporter.kt#DefaultDiagnosticsArchiveExporter.source

Open under the lock and copy outside it. The destination is a DocumentsProvider stream that can block. The open stream keeps reading even if a later discard() unlinks the file.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveExporter.kt#DefaultDiagnosticsArchiveExporter.analyticsContext

Use the class name only. A collector error message or trace can name hosts or addresses.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveModels.kt#DiagnosticsArchiveFormat.schemaVersion

Version 12 adds exact semantic-event accounting and post-redaction logcat completeness accounting.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchivePathRedactor.kt#DiagnosticsArchivePathRedactor.redactLine

Ambiguous absolute paths have no reliable boundary when spaces, punctuation, or escape layers are allowed. Fail closed for each logical line. Keep only a strict known-app Android logcat prefix when one is present. A backslash can be a Windows separator, a JSON escape layer, or the start of a case-insensitive Unicode slash or backslash escape. None of these is safe to keep as it is.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchivePayloadModels.kt#DiagnosticsArchivePacketCaptureDisposition

The projection is redacted. Fields that identify a raw capture never enter the archive.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveRedactor.kt#DiagnosticsArchiveRedactor.redactSession

The hash is of the ordered targets, which include user-entered hostnames.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveRedactor.kt#projectStructuredArchiveJson

Timings of each TLS hop attempt stay in local history.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveRedactor.kt#PemTailRegex

Fail closed. Base64-alphabet lines just before a sensitive END marker are secret material, also when a fragment can be read as an ordinary word.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveRedactor.kt#ArchiveCanonicalEvidence

Evidence details are exported only in their validated, re-encoded form.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveRuntimeSupport.kt#selectArchiveRuntimeContext

Select the context that best explains the runtime failure and keeps the terminal state in time order. Raw-path scans stop the VPN on purpose. So their later Halted or idle snapshot must not hide a still-relevant passive Running snapshot with degraded or failed native components.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveRuntimeSupport.kt#redactedRuntimeAddresses

Redact the network addresses of a runtime summary. Do not redact protocolKind. It is a privacy-safe transport-kind enum (for example "vless_reality", "hysteria2", "amneziawg"). It is not an address or an identifier. It is the active-protocol signal that the diagnostic report shows. network-fingerprint-privacy.md classes relay-kind strings as safe to record.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveSelectionBuilder.kt#DiagnosticsArchiveSelectionBuilder

Normal and prepared exports capture the same immutable archive selection.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsPreparedArtifactReader.kt#DiagnosticsPreparedArtifactReader

A delayed URI read uses only the committed private journal. It never starts Room or Hilt.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsPreparedExportManager.kt#DiagnosticsPreparedExportManager.documentSaveSource

The caller copies the returned file without holding the archive lock.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsPreparedExportManager.kt#DiagnosticsPreparedExportManager.orphaned

A published lease whose export record is gone (for example after "Reset all settings") is no longer owned by anything. It must not stay readable until it expires. One lease that cannot be removed must not keep the other leases from being reclaimed.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsPreparedFileStore.kt#DiagnosticsPreparedFileStore

The journal is private and durable. The archiveMutex serializes all calls.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsPreparedFileStore.kt#DiagnosticsPreparedFileStore.id

Atomic retirement proves ownership of this empty namespace. No arbitrary contents are deleted.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsPreparedFileStore.kt#DiagnosticsPreparedFileStore.retired

Keep the known journal retryable after a deletion error. Empty tombstones also recover after process death.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/PreparedDiagnosticsExport.kt#DiagnosticsExportLeaseId

The key is opaque. Neither saved state nor a caller can supply a filesystem path.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/PreparedDiagnosticsExport.kt#CheckedDiagnosticsExportHandoff

The value is returned only after durable one-shot consumption and a check of the identity of the private file and record.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/PreparedDiagnosticsExportInspector.kt#PreparedDiagnosticsExportInspector

The preview reads the final file, after each typed and correlation redactor and the ZIP packaging step.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/PreparedExportClock.kt#PreparedExportTime

The observation is one coherent clock reading for the private export capability, which is bound to the reboot. The service and the thin FileProvider share it. It does not initialize Room or Hilt.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/ReplayArchiveEntryBuilder.kt#ReplayArchiveEntryBuilder

The detail string of each event goes through ReplayArchiveRedactor before serialization. So resolver IPs from the DNS step never reach disk. The builder returns null when the results are empty. Then the renderer can omit the entry and the zip has no misleading empty-array file.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/ReplayArchiveModels.kt#ReplayArchivePayload

The wire schema is decoupled from the in-memory ReplayProbeResult and ReplayStepEvent types. The orchestrator can then change without a change of the on-disk format. The schema version is pinned to DiagnosticsArchiveFormat.schemaVersion. Then each archive-level schema bump rolls the replay records forward too.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/ReplayArchiveRedactor.kt#ReplayArchiveRedactor

The ReplayStepEvent.StepCompleted event of the DNS step carries detail "resolved <ip>". Raw resolver output in storage would regress the Play Store Data Safety surface (network-fingerprint-privacy.md). The redactor replaces sensitive endpoints, credentials, paths, certificate material, hostnames, and IP addresses. It keeps the safe diagnostic structure. Over-redaction is better than under-redaction.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/SelectiveMatrixPairExport.kt#SelectiveMatrixPairExport

The export carries transport labels, timestamps, opaque target ids, stages of each leg, and repetition counts only. It has no session ids, hostnames, revisions, or network identifiers.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/XrayProviderExportContext.kt#currentXrayExportContext

The context is the current service context. It is separate from historical scan evidence. It launches no probes.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/finalization/DiagnosticsNetworkScopeReport.kt#withoutNetworkScopeAuthority

Keep the measurements, but do not let history or Home consumers apply advice from mixed networks.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/finalization/DiagnosticsNetworkScopeReport.kt#scopeReportJson

Invalid JSON cannot carry actionable evidence and must not escape from a failed finalization.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/finalization/DiagnosticsNetworkScopeReport.kt#revokePersistedNetworkScope

Revoke the staged advice without inserting the measurement rows and native events a second time.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/finalization/DiagnosticsReportPersister.kt#MaxInlineReportJsonBytes

The threshold must stay in sync with the same threshold in the DiagnosticsDao queries. CursorWindow limits a single row to about 2 MB. Reports above this size never fit and earlier caused SQLiteBlobTooBigException on read.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/finalization/DiagnosticsScanFinalizationServices.kt#ScanFinalizationService.freshPolicy

Use one predicate. A fresh result ranks above a remembered policy exactly when its policy is persisted.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/finalization/DiagnosticsScanFinalizationServices.kt#ScanFinalizationService.finalize

Persistence of the report and the context can suspend while the physical network changes.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/finalization/DiagnosticsScanFinalizationServices.kt#ScanFinalizationService.persistNetworkEvidence

An in-path scan measured the active tunnel or proxy, not the direct path of the raw network.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/finalization/DiagnosticsScanFinalizationServices.kt#ScanFinalizationService.buildFreshRememberedPolicy

The builder applies the auto-persist eligibility bar, the quick_v1 suite check, and the valid-recommendation check.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/memory/NativeMemoryProbe.kt#NativeMemorySample

RIPDPI is single-process, so the Rust native core shares this process. The native heap counts the footprint of the Rust allocator. processRssBytes covers the whole process (Java and native). This is the footprint that the per-app memory limiter of Android 17 measures.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/memory/NativeMemoryProbe.kt#Companion.DefaultPageSizeBytes

The page size here is a fallback only. The real page size (4 KiB, or 16 KiB on newer devices) is read at run time with Os.sysconf.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/model/ControlTier.kt#ControlTier

The tier says where a control lives. Then one blocked provider or a broken international link is not read as a broken network.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/model/DiagnosticsContextModels.kt#NetworkSnapshotModel.systemPrivateDnsStatus

The Private DNS status is tri-state. It is computed in Kotlin only and is informational. It is never a source of VPN DNS policy. It is serialized as SystemPrivateDnsStatus.wireValue.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/profiling/MemoryProfilingRegistrar.kt#MemoryProfilingRegistrar

The registrar uses the event-driven profiling triggers of Android 17. A Java heap dump is captured when the app hits an OutOfMemoryError or a severe memory or binder anomaly. The OOM profile is delivered at the next launch. The anomaly profile is delivered just before the system ends the app. The platform writes the artifacts to the own storage of the app (no backend, by project policy). The app records a diagnostics event that points at the captured file. Then the existing pipeline can show and export it.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/profiling/MemoryProfilingRegistrar.kt#DefaultMemoryProfilingRegistrar.register

ProfilingTrigger.TRIGGER_TYPE_OOM and TRIGGER_TYPE_ANOMALY need API 37.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/recommendation/ResolverRecommendationEngine.kt#TcpFatHeaderDpiBlockedOutcomes

Only a reset or a close at the 16 KB threshold is evidence of DPI. A timeout or a failed handshake is not.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/recommendation/ResolverRecommendationEngine.kt#DnsInterferenceOutcomes

DNS outcomes that the native classifier rates Failed or Attention count as interference (outcome_policy.rs).

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/recommendation/ResolverRecommendationEngine.kt#confirmedRecovery

One native DNS-integrity row measures the plain and the encrypted answers to the same question.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/recommendation/ResolverRecommendationEngine.kt#canonicalIpOrSelf

Use one spelling for each IP literal, so that 2001:db8::1 and 2001:DB8:0::1 compare equal. Hostnames pass through.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/recommendation/ResolverRecommendationEngine.kt#ResolverRecommendationEngine.currentPathKey

A full tie goes to the default provider, never to Cloudflare, which is blocked in Russia.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/recommendation/StrategyRecommendationEngine.kt#StrategyRecommendationEngine.isBasicStrategy

RST injection: DPI injects TCP RST. Fake packets with the TTL trick defeat this. Threshold blocking: DPI allows the handshake but blocks after N KB. Disorder breaks the reassembly. A TLS record split with fake packets is the most reliable. Silent drop: packets disappear. Use disorder with the TTL trick. Mixed signals: a TLS record split with fake packets is the most broadly effective.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/DefaultProbeReplayService.kt#DefaultProbeReplayService

The service does not change AppSettings. The caller must wrap the run call in its own activate-finally-restore block in StrategyProbeService. The caller also wires the OkHttp client through the local SOCKS5 proxy. Cancellation uses the executeAsync adapter of OkHttp. It cancels the Call and closes a response that cannot be delivered to the collector.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/DefaultProbeReplayService.kt#ReplayHttpClient

The factory owns the URL construction. Production wiring can then target the live SOCKS5 proxy, and tests route through the port of MockWebServer.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/DefaultProbeReplayService.kt#ReplayEventListener.terminalStep

fail() clears currentStep, so the step that failed must be kept on its own.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/DefaultProbeReplayService.kt#ReplayEventListener.start

A new step after a failure means that OkHttp retried on another route. The earlier failure is not terminal.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/DefaultProbeReplayService.kt#ReplayEventListener.secureConnectStart

OkHttp emits no separate "ClientHello sent" callback. Treat the ClientHello step as an instant boundary that is inferred at secureConnectStart. Then the UI can show the ordered progression. The real TLS handshake lasts until secureConnectEnd.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/DefaultProbeReplayService.kt#ReplayEventListener.next

When a call fails with no step in flight, the fault almost always belongs to the next protocol boundary after the last step that completed. For example, a failed handshake surfaces in callFailed without a new secureConnectStart. The helper maps "last good" to "what was about to start". Then the UI attribution is correct.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/ProbeReplayModule.kt#ProbeReplayModule

The factory builds both the client and the absolute https URL for each call. The URL depends on the request domain, so it cannot be cached when the factory is built. The client is built new for each call. Each replay sets its own call, connect, read, and write timeouts and attaches the EventListener of the caller. An OkHttpClient with a non-null event listener cannot be shared safely between calls.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/ProbeReplayModule.kt#ProbeReplayModule.settings

The listen endpoint is the own endpoint of the user. A replay never writes listen fields.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/ProbeReplayService.kt#ProbeReplayService

A replay changes the live strategy through SettingsStrategyProbeActivator. Callers must show the "Replay disrupts live session" confirmation when the VPN is not in the Halted state. The run ends with ReplayStepEvent.Finished whatever the outcome. Cancelling the collecting coroutine cancels the underlying OkHttp Call.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/ProxyConfigStrategyApplier.kt#ProxyConfigStrategyApplier

Like the activator of the strategy probe, the applier only writes app settings. It does not signal the running proxy, because no settings-to-runtime reload signal exists to wait for. The replay assumes that the runtime picks up the strategy in the same way as the strategy probe.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/ProxyConfigStrategyApplier.kt#SettingsProxyConfigStrategyApplier.apply

Do one update against the current value, so a concurrent write is not reverted. Copy only strategy fields. The listen, relay, WARP, AWG, and runtime fields stay as they are. Revert a field only if this replay wrote it and nobody changed it since.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/ReplayRecommendationEngine.kt#ReplayRecommendationEngine

The returned value is always a string-resource key. The UI layer does the localized resolution. The catalog never carries user-visible strings, to avoid locale rot in the data layer. The catalog loads lazily at the first lookup and is cached for the life of the singleton. The JSON parse is the expensive part, so this keeps it off the hot replay path.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/ReplayResultStore.kt#ReplayResultStore

The state is process-scoped on purpose. Replay results are diagnostic snapshots that the user can re-run on demand. They are not policy state that must survive an LMK SIGKILL. This matches the exception for ephemeral diagnostic aggregates in .claude/rules/android-vpn-lifecycle.md.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/ReplayStep.kt#ReplayStepKind

Each kind maps 1:1 to an OkHttp EventListener boundary in DefaultProbeReplayService. The orchestration model and the UI presentation model are separate on purpose. Then the UI can change its rendering vocabulary without a change of the orchestrator contract.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/ReplayStepEvent.kt#StepFailed

The detail of a failure is a localizable string without personal data. It never includes the resolved IP. It uses the original domain only.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/ScanCandidateConfigSource.kt#ScanCandidateConfigSource

The stored proxy config of a candidate never leaves core.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/StrategyScopedProbeReplay.kt#StrategyScopedProbeReplay

The flow activates the strategy, runs, and restores in a finally block that also runs on cancellation. The flow fails, without a change of settings, when the config is gone or invalid, the strategy id is unknown, or another strategy run holds StrategyProbeRunGuard. Error messages never include config content. Like the strategy probe, the flow writes app settings and assumes that the runtime picks them up. No settings-to-runtime reload signal exists to await.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/StrategySettingsSlots.kt#StrategySettingsSlot

The list mirrors the strategy sections of applyToSettings. It adds the listen-section fields that change what the replayed connection puts on the wire: TCP Fast Open (SynData chains), the custom and default TTL of real packets, freeze detection (it triggers adaptive fallback), and domain resolution (noDomain). It leaves out on purpose the listen address, port, max connections, and buffer size (the replay must not move the proxy). It also leaves out the relay, WARP, AWG, and ws-tunnel fields (other transports) and the host-autolearn fields (learned state, not strategy). A writer field that is missing from this list is never applied. Add new strategy fields from RipDpiProxyUIPreferencesSettingsWriter here as well.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/rkn/RknProbeHeaders.kt#RknProbeHeaders.CHROME_UA_VERSION

Update the headers at least once for each release cycle, or when they are more than six months behind Chrome stable.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/rkn/RknProbeHeaders.kt#RknProbeHeaders.build

Do not set Accept-Encoding. Then OkHttp asks for gzip itself and decodes the body. With the header set here, the stub page body stays compressed (and br has no decoder), so the stub markers never match.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/rkn/SystemDohDnsComparator.kt#SystemDohDnsComparator.runLookup

Only a "no such host" answer is a negative answer. A timeout or other error proves nothing.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/rkn/SystemDohDnsComparator.kt#SystemDohDnsComparator.areDisjoint

Large domains rotate multi-A answers. Only a zero intersection is a rewrite signal.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/rkn/SystemDohDnsComparator.kt#isNegativeDnsAnswer

Android getAllByName throws UnknownHostException for each getaddrinfo failure. The cause is the hidden android.system.GaiException, and its message names the code, for example "android_getaddrinfo failed: EAI_NODATA (...)". Only EAI_NODATA and EAI_NONAME are a negative answer. EAI_AGAIN (SERVFAIL, upstream timeout, no network) and each other failure prove nothing. The code reads the whole cause chain, because coroutine stack-trace recovery can wrap the original exception in a copy.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/rkn/SystemDohDnsComparator.kt#SystemIpv4Resolver.resolve

An IPv6-only answer is an answer, not a negative one.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/rkn/SystemDohDnsComparator.kt#DohReferenceEndpoint

The reference resolvers are public DoH resolvers with a JSON API on a fixed IP. Then a reference lookup never depends on the system resolver. They belong to independent providers. Cloudflare is blocked in Russia, so it cannot be the only reference.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/rkn/SystemDohDnsComparator.kt#DohReferenceResolver

Ask every reference provider at once and unite their answers. A blocked provider does not remove the reference. CDN answers that differ by provider do not read as a rewrite. The call fails only when no provider answers.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/rkn/SystemDohDnsComparator.kt#DohReferenceResolver.resolve

Skip only a failed provider. Cancellation and JVM errors still propagate.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/rkn/SystemDohDnsComparator.kt#DohJsonResolver.connection

An error status is not an answer. The reference resolver can then tell it from an empty answer.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/rkn/SystemDohDnsComparator.kt#DohJsonResolver.body

A capped read keeps a slow or oversized provider answer from outliving the comparison budget.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/rkn/SystemDohDnsComparator.kt#DohJsonResolver.HttpTimeoutMs

Connect plus read stays under the 5 s budget of the comparator. A silent provider then cannot void the others.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/shared/DnsClassification.kt#DnsClassification

This enum mirrors the wire values of the engine contract and com.poyka.ripdpi.data.DirectDnsClassification. It lives in the neutral shared package, so neither mode arm depends on the module of the other. It has no platform HTTP or VpnService references on purpose.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/workflow/DiagnosticsHomeWorkflowServiceImpl.kt#DiagnosticsHomeRecommendationApplier.applyValidatedRecommendation

Validate and apply against one current value, so concurrent settings changes are kept.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/workflow/DiagnosticsHomeWorkflowServiceImpl.kt#DefaultDiagnosticsHomeWorkflowService.networkChanged

Settings that were measured on one network must not be applied on another. An old row without its network key cannot show where it was measured.

### core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/FieldFailureStageContractTest.kt#FieldFailureStageContractTest

This test is the Kotlin half of the failure-stage contract. The Rust half is in ripdpi-diagnostics-contracts (types/field_failure_stage.rs). Both read the same vendored client-field-report v1 schema under core/data/src/test/resources/contract/.

### core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/PreparedExportOrphanCleanupTest.kt#PreparedExportOrphanCleanupTest

"Reset all settings" deletes the export records but not filesDir/diagnostics-prepared.

### core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/StrategyProbeServiceTest.kt#StrategyProbeServiceTest.provider

A contained native panic marshals luaListStrategies and luaLoadedScriptPaths to null. The boundary must convert them to empty and must not throw NullPointerException. Only the built-in candidates remain.

### core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/dpi/QuicH3FingerprintProbeTest.kt#QuicH3FingerprintProbeTest.retriesOnlyMissingTemplatesWithinTheTargetDeadline

Three silent attempts use nine seconds. The successful control leaves 2.5 seconds for the three missing templates to retry once in parallel.

### core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/dpi/QuicH3FingerprintProbeTest.kt#LoopbackAddress

These tokens exercise the verdict and retry orchestration. Native RFC tests own the wire validation.

### core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/dpich/WithCancellationContractTest.kt#WithCancellationContractTest.reader

Coroutine debug recovery can copy an Exception and keep its original cause.

### core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/export/ReplayArchiveRedactorTest.kt#ReplayArchiveRedactorTest.original

A time string such as "12:34:56" has only two colons and no "::". The IPv6 pattern needs 7 colons or the "::" shorthand, so the time string must pass without change.

### core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/profiling/MemoryProfilingRegistrarTest.kt#MemoryProfilingRegistrarTest

The ProfilingManager registration needs API 37 and a device. The test verifies as a pure function the event-building logic that goes into the result callback.

### core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/replay/DefaultProbeReplayServiceTest.kt#DefaultProbeReplayServiceTest.recommendationEngine

The harness is HTTP-only. TLS steps are not emitted on the success path, because the request is plain HTTP. The connection-reset test uses HTTP because OkHttp reports the RST on the response read. This exercises the ConnectionReset classification without TLS.

### core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/replay/DefaultProbeReplayServiceTest.kt#DefaultProbeReplayServiceTest.flowCancellation_cancelsUnderlyingCall

Use runBlocking, not runTest. The implementation does real I/O on Dispatchers.IO. The virtual scheduler of runTest reports UncompletedCoroutinesError when a task on a real dispatcher is in flight at the end of the block. The test measures cancellation, not virtual time.

### core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/replay/ReplayCatalogParityTest.kt#ReplayCatalogParityTest

Each recommendationKey in the JSON catalog must resolve to an R.string key in the values/strings*.xml files of the app. Lint MissingTranslation severity="error" in lint.xml enforces per-locale parity at compile time. So this test verifies only the canonical values files. It walks up from the JVM working directory to the workspace root (the directory with settings.gradle.kts). If a module re-layout breaks the test, update this file lookup.

### core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/replay/ReplayRecommendationEngineTest.kt#ReplayRecommendationEngineTest

The test loads the asset through the classpath copy under src/test/resources/diagnostics/replay_recommendations.json. That copy mirrors src/main/assets/diagnostics/replay_recommendations.json byte for byte. This avoids the Android Context dependency and keeps the test runnable in a plain JVM harness.

### core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/rkn/SystemDohDnsComparatorTest.kt#SystemDohDnsComparatorTest.failures

EAI_AGAIN is what bionic returns for SERVFAIL, an upstream timeout, or no network.
