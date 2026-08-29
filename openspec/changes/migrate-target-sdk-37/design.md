## Context

The user approved the migration plan and implementation. See proposal.md and
specs/android-target-37/spec.md. Current target is 35; compile SDK is already 37.

## Goals / Non-Goals

- Goal: deliver target 37 with functional, demand-driven LAN access and safe TLS fallback.
- Non-goal: redesign UI, change routing policy, upgrade production dependencies, publish,
  or close other Android 17 tasks without their evidence.

## Decisions

- Extend existing permission orchestration with LocalNetwork, not a startup permission wall.
- Permission checks belong at actual direct-network boundaries; inner tunneled destinations
  do not imply direct LAN access. UI owns prompts, background work owns typed deferral.
- Loopback and system network DNS retain their exceptions; denial never triggers route bypass.
- Treat nested certificate/CT errors as terminal before any platform or native fallback.
- Translate internal opportunistic ECH policy to platform enabled XML without changing
  product-level REQUIRE_CONFIRMED semantics. Do not claim platform NSC config affects rustls/Go.
- Pin test-only Robolectric 4.17-beta-4; preserve SDK 35 screenshot baselines.
- Retain API 27/33/35, add 36/37; keep benchmark API 34. Real LAN smoke cannot use adb reverse.

## Contracts and ownership

Codex owns app permission types/UI, service admission, shared data contracts, diagnostics,
native error propagation as needed, all locale sets, gradle.properties, libs.versions.toml,
managed devices and CI. The isolated test subagent owns the service fallback, Android
LAN permission, MainViewModel and diagnostics preflight regression test files. No persisted settings or route migration is intended; extend existing
typed error channels and update every producer/consumer together where required.

## Risks / Trade-offs

- Beta test runner changes: verify existing Roborazzi and unit suites, do not bless regressions.
- Permission grouping and revocation: read OS state, cover upgrade, denial and regrant.
- A successful HTTP request is not proof of ECH negotiation; retain that evidence boundary.
- Physical API 37 device is currently absent; do not substitute an emulator for its acceptance.

## Migration Plan

Tests first, then implementation. Run targeted Gradle tests through build-gate, followed by
testDebugUnitTest, staticAnalysis, locale lint, debug/release builds, native tests/clippy
with --locked, ELF checks and Android matrix. All heavy jobs stay at or below four workers.
Revert the task slice only with owner authorization if rollback is needed. Main, integration,
push, signing and publication are outside the currently authorized local implementation.
