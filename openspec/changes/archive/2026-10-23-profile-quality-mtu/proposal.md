# Change: Select profiles using bounded transfer quality and scoped MTU diagnostics

Task ID: `TRN-1792759596946903`

## Why

The existing selector verifies complete payloads but represents every successful
path as a single elapsed time. A healthy yet slow path prevents examination of
reserves, so it cannot select a better sustained transfer. Active QUIC PMTU facts
already exist but need clear path-specific interpretation alongside quality.

## What Changes

- Extend the existing selector with explicitly enabled, bounded verified-body
  goodput and baseline/loaded HTTP transaction latency; retain unknown facts.
- Apply repeated quality improvements through the existing guarded selection
  path, with traffic budgets, cooldown, network fences and owned native cleanup.
- Reuse the existing Reality measurement owner for eligible Reality candidates.
- Present current scoped quality and existing PMTU evidence without claiming a
  raw QUIC observation measures a relay or changing AWG tunnel parameters.
- BREAKING: admit only schema 2 of the optional payload-failover extension,
  coordinated with `po4yka/ripdpi-vpn-deploy#OPS-1792759217301702`. Ordinary
  subscriptions without the extension retain their existing URLTest behavior;
  schema-2 policies without quality opt-in retain payload-health behavior.

## Capabilities

### New Capabilities

- `profile-transfer-quality`: verified transfer measurements, budgeted selection
  and scoped evidence presentation within the existing selector.
- `diagnostics/pmtu-interpretation`: explain acknowledged payload/IP lower bounds
  and incomplete observations using the existing authenticated QUIC evidence.

### Modified Capabilities

- `payload-aware-selector-failover`: schema-2 admission and optional quality
  selection while preserving encrypted health fallback and lifetime guarantees.

## Impact

- Kotlin runtime-state, service, app subscription and diagnostic/UI modules.
- Canonical deployment bundle policy and its copied Android contract fixtures.
- Real socket and Android native candidate tests; no new required backend,
  production dependency, JNI/protobuf shape or automatic AWG profile switch.
