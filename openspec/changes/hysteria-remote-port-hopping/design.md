## Context

Task `OUT-1792820412611017` implements the behavior in proposal.md. Current import stores dormant strings; the native helper draws intervals but connect never starts a schedule and has no remote port set. Existing rebind tests prove local source migration only. The engine is the existing Rust Hysteria crate with Quinn 0.11.12. The independent peer stays pinned at app/v2.9.0.

## Goals / Non-Goals

- Goal: one validated policy from standard import to sustained authenticated TCP and UDP over changing remote and local UDP ports.
- Goal: preserve IPv4, IPv6, TLS pin/auth, Salamander, VPN socket protection and cancellation guarantees.
- Non-goal: replace the relay backend or selector, add dependencies, upgrade the peer, expand producer operator fields, deploy, or use private fleet/device credentials.

## Decisions

- Kotlin owns a validated Hysteria2PortHopping model. Canonical ports are sorted, deduplicated and merged, with comma-separated scalar ports or hyphen ranges. The finite 1..65535 domain bounds storage and validation work. Empty, reversed or malformed input fails. The first canonical port supplies a scalar endpoint only where existing APIs require it; it does not replace runtime port selection.
- Native JSON carries hysteriaPortHopPorts (nullable string), hysteriaPortHopIntervalMillis (nullable integer) and hysteriaPortHopIntervalMaxMillis (nullable integer). Rust uses Option<String> and Option<u64>. No interval strings cross this boundary. Absent ports require absent intervals. Present ports default to 30000 ms; minimum is 5000 ms; maximum is 9223372036854 ms, the whole-millisecond portion of a signed 64-bit nanosecond duration. Durations with finer precision fail. Equal interval bounds normalize to fixed cadence. Maximum below minimum fails. Native repeats all checks and checked monotonic deadline addition before DNS or socket creation.
- Standard and bundle-extension values must agree after normalization when both exist. Official URI authority contains the port union. URI interval query parameters are explicit application extensions, preserve exact values on export, and reject fixed-plus-random conflicts. Clash uses its documented ports and hop-interval seconds/range format. Scalar profiles remain scalar.
- One Hysteria-specific AsyncUdpSocket adapter owns the logical Quinn remote endpoint, real current/previous UDP sockets, selected destination port and timer. Outbound sends use the current destination; inbound source addresses must match the resolved server IP and configured port set before normalization. Every hop chooses a different destination when more than one is available and creates a newly protected local socket. Keep the previous receive socket until the next rotation. The same adapter applies Salamander to every send/receive path.
- Schedule state belongs to the transport lifetime. Poll-driven timer ownership or an abort-on-drop worker with weak ownership must not create a strong-reference cycle. Shutdown releases both sockets, timers and pending pollers. Socket replacement wakes relevant pollers. No lock is held across an await or callback into the same state. Protection failure closes the replacement and propagates a terminal session error; it never emits unprotected traffic.
- Existing migration.rs source rebind must replace the adapter's underlying socket without replacing the adapter or losing the port policy. Rollback must preserve the same adapter and schedule. Test the combined flags explicitly.
- Independent upstream acceptance uses local UDP forwarders with counters and retained mappings, in front of the pinned real peer. TCP stream and UDP session remain open across at least three scheduled hops. Receipts show local/remote port changes and destination payload; connection identity/authentication evidence excludes reconnect. Exercise fixed/random windows, Salamander, IPv4/IPv6 and prior-path delayed replies.

The acceptance peer is the stable app/v2.13.0 release. It includes the upstream
QUIC maximum-datagram-size fix and matches the public Hysteria version example
in `po4yka/ripdpi-vpn-deploy` at `secrets/prod.secrets.example.yaml`. The previous
app/v2.9.0 peer failed the same direct 2736-byte UDP reply even without hopping.
Only the test binary pin changes; native dependencies and production pins do not.
The matrix retains default peer QUIC settings, an 8208-byte TCP exchange and
three distinct 2736-byte UDP exchanges per round, including fragmentation.

## Contracts and ownership

- Kotlin lane owns runtime-state model/import/storage/validation, data selector mapping, service direct/chain resolution, engine-api flat/section/chain DTOs and codec tests; it also owns affected backup classification. It integrates the separate stored-health-policy prerequisite without replacing that work.
- Native lane owns this task and OpenSpec change, ripdpi-hysteria2 sources/tests, ripdpi-relay-core flat/typed config and conversions, direct/chain builders and affected tests, peer fixture additions, `test-lab/acceptance/generate_manifest.py` and its generated manifest, the acceptance README, and the hopping PR job in `.github/workflows/local-acceptance.yml`.
- Root owns producer changes, contract mirror coordination, shared board integration, combined-tree validation, external PR and main integration. Producer schema stays range plus fixed interval; remove its rejection only after client acceptance.
- Native and Kotlin wire changes are serialized within their assigned lane. Cargo.lock, dependency pins, protobuf, locales and golden baselines are outside these lanes unless separately coordinated.

## Risks / Trade-offs

- Remote address normalization can admit unrelated packets: filter server IP and port union before QUIC input, retaining TLS/QUIC authentication.
- Quinn UDP GRO can require larger receive buffers than one datagram: preserve the initial receive-segment capability across replacements; verify plain Linux GRO and Salamander separately.
- Socket rotation can lose delayed responses: retain one previous receive socket and test a delayed response after rotation.
- Timer or poller ownership can leak tasks or stall writes: test drop, cancellation, idle periods, repeated restart and writable notification after socket replacement.
- Existing source migration can remove the adapter: cover migration plus hopping and its rollback paths.
- Long intervals can overflow a platform deadline: exact parsing and checked deadline arithmetic fail before I/O.
- Host peer proof does not prove Android device or production acceptance. Report those scopes separately.

## Migration Plan

Convert old stored portHopPorts/portHopInterval once at the persistence boundary into the new validated model. Reject inconsistent persisted data instead of preserving a second runtime model or falling back to a scalar profile. Imported producer extras retain their existing wire shape. New native JSON fields are optional for scalar profiles; updated Kotlin and native artifacts ship together.

Rollback reverts the feature commits as a unit; profiles with the new hopping model require reimport in an older binary. Do not introduce a legacy runtime path. Keep producer refusal until combined client acceptance passes.

Validation starts with import/model and relay JSON tests, then build-gate -- cargo test --locked -p ripdpi-hysteria2 -p ripdpi-relay-core --jobs 4 and affected Gradle module tests under build-gate. Run the controlled peer matrix through its canonical runner and gate. Combined-tree checks include static analysis, architecture health, task-check, cargo metadata --locked, applicable config/backup/fixture contract checks and Android JNI build. Exact command selectors are recorded in verification.md after discovery; a failed or unrun gate remains open.
