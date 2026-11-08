## Context

The current TUN path uses etherparse 0.21.0, vendored smoltcp 0.14.0 and a custom raw egress parser. The packet boundary carries unaligned byte slices. No unsafe parsing is needed.

## Goals / Non-Goals

- Goal: repair F1-F8 with tests and separate fix commits.
- Non-goal: IPsec, source routing, TCP fragment reassembly, jumbograms, root activation or a required backend.

## Decisions

- Validate IPv4 headers before the UDP reassembly split. Validate IPv6 option policy after complete reassembly as well as for ordinary packets.
- Reassembly must retain the supported pre-fragment chain and fragmentable bytes. It must not parse non-first fragment data as extension headers. An orphan has no known transport until offset-zero arrives. Hold it without exposing it to the interceptor or smoltcp; do not count it as ICMP from its non-authoritative Next Header. Known non-UDP first fragments retain the existing policy path.
- Use bounded interval and rejection state for IPv6 overlap. Use a fixed lifetime so duplicate traffic cannot pin the pool.
- Normalize only supported safe IPv6 extensions for smoltcp. Reject active Routing Header semantics rather than compute a checksum with the wrong destination. Keep unsupported AH/ESP outside scope.
- Raw egress uses checked packet and transport ranges. Preserve declared data boundaries and the existing original-packet fallback.
- SYNACK checksum uses the TCP header length, not the whole TcpSlice length. Check IPv6 output size before building it.

## Contracts and ownership

- Core validation writer owns classify, routing and packet validation/normalization helpers and tests.
- Reassembly writer owns only defrag.rs and its helper/test subtree.
- Intercept writer owns only ripdpi-tunnel-intercept/src.
- Integrator owns planning records, packet write handling and combined integration tests.
- No shared manifest, lockfile, schema, vendor or baseline edit is planned. These paths have one owner: the integrator.
- Tokio production loop is current_thread; host test suites may use multi_thread. Packet parsing remains synchronous and single-owner.

## Risks / Trade-offs

- Stricter input validation may expose malformed test fixtures. Replace invalid fixtures with valid independent vectors; do not weaken validation.
- Header removal can change error quotes. Preserve wire bytes for UDP errors; normalize only the TCP path after policy checks.
- Host socketpair tests cannot establish Android or external compatibility. Record these evidence categories separately.

## Migration Plan

No migration. Apply separate reversible commits. Run the affected Rust suites and Clippy, packet loop tests and just ci-gates before integration. Use an independent read-only reviewer. Roll back a specific fix commit if its acceptance fails; do not change quality baselines.
