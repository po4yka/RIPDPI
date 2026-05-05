---
title: Replace ResolvedRelayRuntimeConfig god struct with a per-variant enum payload
type: task
status: backlog
area: relay
priority: high
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Replace ResolvedRelayRuntimeConfig god struct with a per-variant enum payload #repo/RIPDPI #area/relay #status/backlog ⏫

## Objective

Replace the 52-field flat `ResolvedRelayRuntimeConfig` with a `RelayBackendConfig` enum whose variants carry only the fields relevant to each backend, wrapped in a thin `ResolvedRelayRuntimeConfig { common: CommonRelayConfig, backend: RelayBackendConfig }`.

## Context

`ResolvedRelayRuntimeConfig` (config.rs:76–160) holds every possible backend field (Hysteria2, TUIC, VLESS/Reality, MASQUE, ShadowTLS, NaiveProxy, chain-relay, finalmask) in a flat struct. At runtime only a subset is active based on `kind`, but callers must understand which fields are live. Adding a new backend silently adds dead fields for all existing variants. A parallel `RelayKind` enum already exists in the same file — config and kind are out of sync structurally.

Source: `native/rust/crates/ripdpi-relay-core/src/config.rs:76-160`

## Acceptance criteria

- [ ] `CommonRelayConfig` struct holds the fields shared across all backends (server, port, server_name, tls_fingerprint_profile, local_socks_host/port, finalmask).
- [ ] `RelayBackendConfig` enum has one variant per `RelayKind` variant, each carrying only its required fields in a named struct.
- [ ] `ResolvedRelayRuntimeConfig` wraps `common: CommonRelayConfig` and `backend: RelayBackendConfig`.
- [ ] All existing call sites that destructure or access config fields compile after the migration.
- [ ] No behavioral change; existing relay integration tests pass.

## Definition of done

`cargo build -p ripdpi-relay-core` succeeds; all relay tests green; no `#[allow(dead_code)]` on config fields.
