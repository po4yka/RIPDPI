## Context

See proposal.md and the twelve DIA-18 audit findings. Implementation and regression tests remain local. Public documentation may be read; external active probes and physical-device transport runs remain outside this job.

## Goals / Non-Goals

- Goal: close F1 through F12 with separate reviewed fixes and regression evidence.
- Non-goal: add an active DNS leak service, local MMDB fallback, root mode, or a new DNS transport.

## Decisions

- Extend the existing CategoryResult.executionStatus and CheckExecutionStatus with OBSERVATION_ONLY. Use one hasIncompleteChecks() owner for UI, reports, score, history and community output. Existing producers default to COMPLETED. A skipped GeoIP baseline remains incomplete; optional skipped probes remain optional.
- Keep the public IP result shape compatible. Canonicalize numeric addresses and compare by family in both comparison layers.
- Use nullable internal GeoIP traits, provider-specific admission, requested-IP identity, field-specific quorum, and strict country majority.
- Add executeCancellable to the existing DetectionResolverNetworkStack. Its cancellation hook remains active through the bounded body read; it returns a buffered DetectionTransportResponse.
- Use one dispatcher and upstream DoH instance per managed DNS call. Share the existing executor and connection pool. Cancel both the DNS subscriber and its owned HTTP calls, and reject late child calls after cancellation. The pinned upstream cache otherwise keeps transport requests running after subscriber cancellation. Preserve upstream wire parsing, TLS, bootstrap and host guards.
- Pass one run resolver context to endpoint resolution and ASN enrichment. A master flag suppresses all active network work.
- Remove unproven underlay claims; do not add physical bypass measurement.
- Reuse existing status labels when their meaning fits. New strings require all locales and the translation export. Golden blessing needs separate explicit family authorization if a changed golden is required.

## Contracts and ownership

- Root owns DetectionModels.kt, DnsLeakChecker.kt, DetectionResolverNetworkStack.kt and a bounded cancellable HTTP helper, IfconfigClient.kt, VerdictEngine.kt status handling, UI/status presentation, reports/exports, locale resources, API snapshots, task/OpenSpec files and combined gates.
- IP worker owns IpComparisonChecker.kt, consensus/IpConsensusBuilder.kt, probe/ReflectedIpAddress.kt and their tests: F2/F3/F7/F9 plus the F6 preflight and F10 call-site changes.
- Geo worker owns GeoIpChecker.kt, small provider helpers, and GeoIpCheckerTest.kt: F4/F5 and F10 call-site changes. Root owns VerdictEngineTest.kt; Geo uses actual VerdictEngine from its owning tests.
- Policy worker owns DetectionRunnerContracts.kt, DetectionPipelineScheduler.kt, DetectionPipelineResultAssembler.kt, DetectionRunner.kt, DetectionCheckerPortAdapters.kt, app DetectionResultPresenter.kt/DetectionResolverSettings.kt and HomeDiagnosticsAugmentationModule.kt with their tests. BypassChecker.kt is policy-owned until its F1 patch is integrated; root then owns path-label changes.
- Each writer uses an isolated worktree. Root serializes shared model, locale, contract snapshot and task files. No Cargo.lock, proto, JNI, baseline or relay registry change is planned.
- Workers prepare one patch per finding. Independent pr-reviewer review precedes behavior commits. Root integrates patches in dependency order, runs combined gates, then fast-forwards main and pushes as requested.

## Risks / Trade-offs

- Some public APIs do not provide security traits on their anonymous tier. Missing capability remains unknown and may reduce quorum.
- Rejecting undocumented HTML payloads reduces reflection coverage; it prevents false IP attribution.
- Partial results remain visible and must not gain hard confidence through downstream classification.
- Body cancellation must cover response consumption, not only response headers.
- Managed DoH calls do not reuse earlier-call DNS cache entries. Network handover attribution still needs separate device acceptance.
- SYSTEM lookup cancellation cannot stop work already owned by the operating system.

## Migration Plan

No stored, wire, JNI or protobuf migration. The OBSERVATION_ONLY enum value is additive. Exports keep executionStatus and NOT_RUN for an absent category. Existing producers default to COMPLETED. Revert the corresponding fix commit to roll back; do not weaken gates. Validate owning tests, app unit tests, static analysis, locale lint, offline ci-gates, architecture health, locked Cargo metadata, and applicable visual tests. Record live-device and external interoperability gaps separately.
