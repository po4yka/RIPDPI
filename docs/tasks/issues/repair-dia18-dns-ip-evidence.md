---
id: DGN-1794748905877489
title: Repair DIA-18 DNS and public IP evidence
kind: bug
status: doing
area: diagnostics
priority: high
owner: Diagnostics repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: dia18-dns-ip-evidence
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Close DIA-18 findings F1 through F12 without expanding active measurement scope.

## Acceptance criteria

All twelve requirements have regression evidence. Owning and combined gates pass. Each fix is reviewed and committed. Main contains the commits and origin/main is updated. External compatibility is not inferred from local tests.

## Parallel ownership

- Root owns DetectionModels.kt, DnsLeakChecker.kt, DetectionResolverNetworkStack.kt and a bounded cancellable HTTP helper, IfconfigClient.kt, VerdictEngine.kt status handling, UI/status presentation, reports/exports, locale resources, API snapshots, task/OpenSpec files and combined gates.
- IP worker owns IpComparisonChecker.kt, consensus/IpConsensusBuilder.kt, probe/ReflectedIpAddress.kt and their tests: F2/F3/F7/F9 plus the F6 preflight and F10 call-site changes.
- Geo worker owns GeoIpChecker.kt, small provider helpers, and GeoIpCheckerTest.kt: F4/F5 and F10 call-site changes. Root owns VerdictEngineTest.kt; Geo uses actual VerdictEngine from its owning tests.
- Policy worker owns DetectionRunnerContracts.kt, DetectionPipelineScheduler.kt, DetectionPipelineResultAssembler.kt, DetectionRunner.kt, DetectionCheckerPortAdapters.kt, app DetectionResultPresenter.kt/DetectionResolverSettings.kt and HomeDiagnosticsAugmentationModule.kt with their tests. BypassChecker.kt is policy-owned until its F1 patch is integrated; root then owns path-label changes.
- Policy worker also owns DirectDns.kt and its transport tests for per-call DNS cancellation. Root owns CancellableDetectionDns.kt and resolver wrappers.
- Each writer uses an isolated worktree. Root serializes shared model, locale, contract snapshot and task files. No Cargo.lock, proto, JNI, baseline or relay registry change is planned.
- Workers prepare one patch per finding. Independent pr-reviewer review precedes behavior commits. Root integrates patches in dependency order, runs combined gates, then fast-forwards main and pushes as requested.
