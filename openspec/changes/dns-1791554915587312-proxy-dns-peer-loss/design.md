## Context

The failover controller observes native DNS telemetry. It can classify a reset as a resolver block and choose a new resolver. The telemetry coordinator then refreshes the tunnel. See proposal.md for the observed recovery race.

## Goals / Non-Goals

- Goal: Prevent resolver changes and DNS-driven tunnel rebuilds for ambiguous shared proxy failures.
- Goal: Preserve resolver-specific and direct-path failover.
- Non-goal: Change proxy routing, weaken strict DNS policy, or add a test-only production route.

## Decisions

- Read the effective DNS route from active tunnel evidence, including strict and forced proxy routing. The standalone preference cannot determine the actual route alone.
- Gate ambiguous transport failure attribution before block-store mutation and resolver selection. A reset or SOCKS failure cannot identify the resolver endpoint. Keep endpoint-specific evidence eligible under the current thresholds.
- Verify an ambiguous-error sequence followed by endpoint-specific evidence. Excluded route errors must not accumulate toward a later resolver failover threshold.
- Observe the real tunnel provider through a delegating test counter. Add a DNS request during peer loss and compare resolver identity, tunnel establishment, runtime identity, and server receipts across recovery.
- Retain original clean-main evidence. Run the new regression before the production fix, then repeat all affected acceptance tests after the fix.

## Contracts and ownership

- Android writer owns :core:service DNS failover policy and tests, XrayProviderE2ETest, and this specification after planning handoff.
- Coordinator owns unrelated Simple UI test corrections and combined-source integration.
- No Rust crate, JNI, protobuf, wire version, locale, or persistence schema changes. No migration or shared lockfile edits.
- Tests retain the real VPN route, native tunnel, Xray implementation, separate test UID, and peer receipts.

## Risks / Trade-offs

- An ambiguous failure may also originate at the resolver. With a shared route, defer automatic attribution until resolver-specific evidence exists. Verify direct failure and endpoint-specific failure paths in unit tests.
- Old telemetry may straddle peer loss and recovery. Observe the actual resolver and tunnel across the entire failure window and retain the event timeline.
- Emulator results do not establish physical-device or carrier acceptance. Keep those boundaries in the report.

## Migration Plan

No data migration is required. Revert the isolated service policy commit to roll back. Before integration, run :core:service:testDebugUnitTest, :core:service:lintDebug, staticAnalysis, the full Android local acceptance profile, separate routed Android Xray acceptance, manifest validation, and report verification. Repeat the peer-loss regression and retain its pre-fix failure. Check the combined clean commit and remote CI after publication.
