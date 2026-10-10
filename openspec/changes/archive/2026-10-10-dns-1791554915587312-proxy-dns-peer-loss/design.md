## Context

The failover controller observes native DNS telemetry. It can classify a reset as a resolver block and choose a new resolver. The telemetry coordinator then refreshes the tunnel. See proposal.md for the observed recovery race.

## Goals / Non-Goals

- Goal: Prevent resolver changes and DNS-driven tunnel rebuilds for ambiguous shared proxy failures.
- Goal: Preserve resolver-specific, direct-path, and local native proxy endpoint failover.
- Non-goal: Change proxy routing, weaken strict DNS policy, or add a test-only production route.

## Decisions

- Read the effective DNS route from active tunnel evidence, including strict and forced proxy routing. The standalone preference cannot determine the actual route alone.
- Also read shared upstream ownership from consumed runtime evidence. Native proxy startup supplies its consumed upstreams. Xray startup supplies its actual Xray upstream. A local native proxy without a shared upstream must retain endpoint timeout failover even when strict policy routes DNS through its SOCKS listener.
- Carry this ownership through tunnel start, rebuild, and bridge readiness. A DNS-only refresh retains the consumed ownership. Do not infer ownership from a pending settings selection.
- Gate ambiguous transport failure attribution before block-store mutation and resolver selection. A reset or SOCKS failure cannot identify the resolver endpoint. Keep endpoint-specific evidence eligible under the current thresholds.
- Verify an ambiguous-error sequence followed by endpoint-specific evidence. Excluded route errors must not accumulate toward a later resolver failover threshold.
- Observe the real tunnel provider through a delegating test counter. Add a DNS request during peer loss and compare resolver identity, tunnel establishment, runtime identity, and server receipts across recovery.
- Retain original clean-main evidence. Run the new regression before the production fix, then repeat all affected acceptance tests after the fix.

## Contracts and ownership

- Android writer owns :core:service DNS failover policy, consumed runtime evidence, service tests, XrayProviderE2ETest, and NetworkPathE2ETest.
- Coordinator owns planning artifacts, Simple UI test corrections, and combined-source integration.
- No Rust crate, JNI, protobuf, wire version, locale, or persistence schema changes. No migration or shared lockfile edits.
- Tests retain the real VPN route, native tunnel, Xray implementation, separate test UID, and peer receipts.

## Risks / Trade-offs

- An ambiguous failure may also originate at the resolver. With a shared route, defer automatic attribution until resolver-specific evidence exists. Verify direct failure and endpoint-specific failure paths in unit tests.
- An effective SOCKS route alone does not prove a shared remote upstream. The fe47d56d acceptance run exposed this distinction: local native DNS timeout receipts and failure telemetry were present, but the broad guard suppressed the existing endpoint recovery. Retain the original recovery assertions and capture the consumed upstream in regression tests.
- Old telemetry may straddle peer loss and recovery. Observe the actual resolver and tunnel across the entire failure window and retain the event timeline.
- Emulator results do not establish physical-device or carrier acceptance. Keep those boundaries in the report.

## Migration Plan

No data migration is required. Revert the isolated service policy commit to roll back. Before integration, run :core:service:testDebugUnitTest, :core:service:lintDebug, staticAnalysis, the full Android local acceptance profile, separate routed Android Xray acceptance, manifest validation, and report verification. Repeat the peer-loss regression and retain its pre-fix failure. Check the combined clean commit and remote CI after publication.
