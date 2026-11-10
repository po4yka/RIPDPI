## Context

Task ID: `RLY-1794300094981437`. The audit reproduced the resolver order mismatch, the parser failure event mismatch, and invalid Basic identity encoding.

## Goals / Non-Goals

- Goal: Fix F1–F4 with one implementation commit per finding and regression coverage.
- Non-goal: Add UDP payload, change Chromium artifacts, claim browser parity, or change unverified H3 behavior.

## Decisions

- Use the first valid ordered resolver address. The resolver already owns reachability preference.
- Use existing structured failure events for known proxy errors; preserve destination-side behavior and message redaction.
- Put one Kotlin Basic credential predicate in runtime-state and use it at all Kotlin boundaries. Check the same rule at Rust stdin parsing. Preserve valid whitespace and empty passwords.
- Correct the existing two owning documents. No new documentation layer is needed.

## Contracts and ownership

- Modules: core:data:runtime-state, app, core:service; crate: ripdpi-naiveproxy.
- The portfolio record defines independent writer paths. The coordinator alone edits planning records and commits changes.
- No schema, JNI, storage, dependency, golden, locale, baseline, registry, or lockfile changes are planned.

## Risks / Trade-offs

- Invalid saved credentials will fail validation. Test restored records and keep errors free of secrets.
- Proxy and destination errors must stay distinct. Test typed events and actual health transitions.
- Host tests do not prove Android VPN routing or Chromium H3 compatibility. Keep these evidence boundaries explicit.

## Migration Plan

No data migration is required. Revert the individual fix commit to roll it back. Before integration run affected unit tests, Rust helper tests with --locked, staticAnalysis, just ci-gates, architecture health, cargo metadata --locked, and independent review. Device and external-server probes need separate authorization and are not merge evidence for these local fixes.
