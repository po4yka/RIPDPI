---
id: RLY-1794477185649013
title: Repair obfs4 relay and managed transport contracts
kind: bug
status: doing
area: relay
priority: high
owner: Transport repair coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: tun07-contract-repairs
created: 2026-11-12
updated: 2026-11-12
---

## Goal

Repair the confirmed TUN-07 relay and identity defects, complete the managed PT contract, and close socket ownership during stop.

## Acceptance criteria

- Standalone obfs4 cannot claim a generic TCP relay; Tor with an obfs4 bridge remains available.
- Valid Arti identities pass permission preflight without modification.
- Managed PT readiness requires a valid version, loopback listener, and completed negotiation; startup errors retain their cause.
- Stop prevents new socket ownership; PT arguments escape all specified separators.
- Output from a stopped helper cannot alter a later process run.
- Invalid obfs4 certificate encoding or length fails before Tor activation.
- Relevant behavior tests, static analysis, CI source gates and independent review pass before integration.
- Record source/artifact evidence separately from unperformed external or physical acceptance.

## Parallel ownership

- F1 worker: app ModeEditorRelaySection and ModeEditorScreenTest; service LocalPathRelayKindResolver, UpstreamRelayValidationSupport and UpstreamRelayRuntimeConfigResolverTest.
- F2 worker: service PermissionCheckedRelayConfigResolver and RuntimeLocalNetworkPreflightTest.
- PT worker: PluggableTransportManager/SocksBridge, SubprocessRelayOutputParser/ProcessSupervisor/ReadinessProbe/SocksRelayManager and their existing tests. Separate commits for negotiation, socket ownership and argument encoding.
- PT worker follow-up: SubprocessRelayProcessSupervisor and its owning tests only; isolate output-reader events between process runs.
- Failure-reason worker: SubprocessRelayReadinessProbe, SubprocessSocksRelayManager and SubprocessRelayComponentsTest; preserve typed helper errors after listener negotiation.
- Certificate worker: data/settings TorBridgeValidation and TorBridgeValidationTest; app ConnectionTorValidationTest; service UpstreamRelayRuntimeConfigResolverTest after the F1 writer releases ownership.
- Coordinator: task/OpenSpec records, validation evidence, artifact verification and integration.
- Each writer uses an isolated worktree. No writer edits dependencies, schemas, locale sets, goldens, or baselines. Any newly required shared file is assigned before editing.
- Coordinator serializes task records, generated board, and integration. Review agents are read-only.
