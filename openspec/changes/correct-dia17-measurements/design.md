## Context

Task DGN-1794747077897183 repairs the seven confirmed DIA-17 findings. The current user request authorizes implementation, separate commits, main integration and push.

## Goals / Non-Goals

- Goal: preserve measurement state and quality, respect network permission, and bound active I/O.
- Non-goal: add IPv6 Echo, raw ICMP, new targets or a new ICMP UI mode. Do not claim device or external acceptance.

## Decisions

- Add a defaulted category execution status. Preserve existing completed categories. Add inconclusive ICMP/RTT states and preserve skipped category status.
- Require at least three valid hosts in each RTT cohort. Missing replies or unknown jitter reduce confidence. Missing cohorts are inconclusive. This is a conservative product policy, not an RFC threshold.
- Map timing evidence in its category context, preserving local capability facts. Keep timing conclusions as review evidence.
- Resolve a bounded endpoint before the TCP connect timer. Exclude close and DNS from the interval. Cancel owned processes and sockets.
- Select ping numeric-output arguments from a local capability check: iputils accepts -n; toybox is numeric without it. Never probe public targets for capability inspection.
- Keep ICMP activation internal-only. Do not raise uncalibrated spoof confidence or infer proven home routing from SIM data alone.

## Contracts and ownership

- Modules: core:detection and app. No Rust, JNI, protobuf or dependency changes. History JSON gains a defaulted measurementsComplete field; old records decode as complete, with no data migration.
- integration owns shared models, RTT and classifier outcomes, reports/exports, Compose status, all locale sets, task/spec artifacts, API snapshots and final gates.
- ui-worker owns presenter/score and tests; ping-worker owns SystemPingProber and process tests; timing-worker owns TimingAnalysisChecker and tests. Each writer uses a separate worktree and delivers patches. Integration owns reviewed commits.
- Serialize shared models/locales under integration; apply ping/timing patches before dependent outcome changes.

## Risks / Trade-offs

- A stricter quorum yields more inconclusive results. Show reasons rather than a clean status.
- Platform ping differs by image. Source and host fixtures do not prove Android compatibility.
- System DNS may ignore interruption. Bound caller wait and concurrency without claiming the native platform call is killed.

## Migration Plan

Defaulted result fields preserve source compatibility. Export status becomes explicit. No settings migration. Validate core:detection JVM tests, affected app tests, applicable lint/locale parity, just ci-gates, strict task checks and independent review before integration. Device and external results remain unverified.
