---
id: RLY-1794486295363382
title: Repair remaining TUN-10 managed transport contracts
kind: bug
status: doing
area: relay
priority: high
owner: PT repair coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: tun10-contract-repairs
created: 2026-11-12
updated: 2026-11-12
---

## Goal

Close the remaining TUN-10 findings on the current main branch without changing Tor bridge identities or adding a new transport protocol.

## Acceptance criteria

- Standalone Snowflake and WebTunnel fail before activation and direct users to Tor; saved profiles remain readable.
- WebTunnel negotiates PT version lists and reports normative startup errors.
- Managed PT readiness honors the caller deadline, control output is separate from diagnostics, and stop uses bounded stdin shutdown.
- Existing TUN-07 repairs for lifecycle, argument encoding, endpoint parsing and socket ownership retain passing regression coverage.
- Record the Lyrebird half-close scope accurately; do not claim an upstream implementation change.
- Each new atomic repair passes owning tests and independent review before commit; combined source gates pass before main integration.

## Ownership

- Relay worker: standalone rejection, app selection and owning tests. This worker exclusively owns affected locale resources and translation exports.
- Rust worker: native/rust/crates/ripdpi-webtunnel/src/pt.rs, src/main.rs and tests/pt_managed_client.rs.
- Lifecycle worker: PluggableTransportManager readiness, SubprocessRelayProcessSupervisor, launch planner, manager cleanup and owning tests. Coordinate shared PluggableTransportManagerTest before editing.
- Coordinator: portfolio/OpenSpec records, evidence, F3 scope document, integration and combined verification.
- Cargo.lock, dependency manifests, schemas, registry baselines and golden fixtures have no assigned writer and must remain unchanged.

## Prior repairs

TUN-07 already repaired F2/F5 in 92378fc509, F4 in 833374d53c, socket admission in 940c9a752d, and standalone obfs4 in e96a3753d1. Recheck their tests; do not duplicate these commits.
