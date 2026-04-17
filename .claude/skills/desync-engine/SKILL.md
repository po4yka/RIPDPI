---
name: desync-engine
description: >
  DPI desync evasion pipeline: planning and executing packet-level manipulation
  to bypass middlebox/DPI censorship. Use this skill when working with DesyncMode,
  DesyncGroup, DesyncAction, DesyncPlan, TcpChainStep, TcpChainStepKind,
  UdpChainStep, UdpChainStepKind, OffsetBase, OffsetExpr, ActivationFilter,
  ActivationContext, EntropyMode, QuicFakeProfile, SeqOverlapFakeMode,
  FakePacketPlan, PlannedStep, or any code in ripdpi-desync, ripdpi-config
  model layer, or ripdpi-runtime desync execution. Also use when adding new
  evasion techniques, debugging why a desync strategy fails, tuning fake TTL
  or offset parameters, or understanding the config-to-plan-to-execution flow.
---

## Conceptual model

DPI (Deep Packet Inspection) systems like Russia's middlebox inspect the first
packets of a TCP/UDP connection to fingerprint the protocol and destination.
They look for TLS ClientHello SNI fields, HTTP Host headers, and QUIC Initial
packet metadata. If the destination matches a blocklist, the DPI injects a
RST or drops the connection.

Desync evasion works by making the first packets unreadable to the DPI while
remaining valid to the destination server. Techniques include:

- **Splitting** the payload across multiple TCP segments so no single packet
  contains the full SNI/Host value.
- **Disordering** segments so the DPI reassembly engine sees garbage first.
- **Injecting fake packets** with low TTL that reach the DPI but expire before
  the destination, poisoning the DPI's reassembly buffer.
- **TLS record fragmentation** to split the ClientHello across TLS records
  within a single TCP segment.
- **UDP/QUIC** fake bursts and SNI splitting for QUIC Initial packets.

## Architecture overview

The desync engine is a three-layer pipeline:

### Layer 1: Configuration model (`ripdpi-config`)

File: `native/rust/crates/ripdpi-config/src/model.rs`

Defines the data model: `DesyncGroup`, `DesyncGroupActionSettings`,
`TcpChainStep`, `TcpChainStepKind`, `UdpChainStep`, `UdpChainStepKind`,
`OffsetBase`, `OffsetExpr`, `ActivationFilter`, `DesyncMode`, `EntropyMode`,
`QuicFakeProfile`, `SeqOverlapFakeMode`. These are pure data types with no
I/O -- they represent what the user configured.

Key relationships:
- `DesyncGroup` owns `DesyncGroupMatchSettings` (when to activate) and
  `DesyncGroupActionSettings` (what to do).
- `DesyncGroupActionSettings.tcp_chain: Vec<TcpChainStep>` defines the
  ordered chain of TCP desync operations.
- Each `TcpChainStep` has a `kind: TcpChainStepKind` and an
  `offset: OffsetExpr` that determines where to split.

### Layer 2: Planning (`ripdpi-desync`)

Files:
- `native/rust/crates/ripdpi-desync/src/plan_tcp.rs` -- `plan_tcp()`
- `native/rust/crates/ripdpi-desync/src/plan_udp.rs` -- `plan_udp()`
- `native/rust/crates/ripdpi-desync/src/offset.rs` -- `resolve_offset()`, `gen_offset()`
- `native/rust/crates/ripdpi-desync/src/fake.rs` -- `build_fake_packet()`, `build_hostfake_bytes()`
- `native/rust/crates/ripdpi-desync/src/tls_prelude.rs` -- `apply_tls_prelude_steps()`
- `native/rust/crates/ripdpi-desync/src/types.rs` -- `DesyncAction`, `DesyncPlan`, `PlannedStep`

The planner takes a `DesyncGroup` + raw payload + `ActivationContext` and
produces a `DesyncPlan` containing a `Vec<DesyncAction>`. No I/O happens here
-- pure computation over bytes.

### Layer 3: Execution (`ripdpi-runtime`)

File: `native/rust/crates/ripdpi-runtime/src/runtime/desync.rs`

Functions: `send_with_group()`, `execute_tcp_plan()`, `execute_tcp_actions()`.
Takes the `DesyncPlan` and executes `DesyncAction` variants against a real
`TcpStream`, calling platform-specific socket operations (TTL, MD5 sig,
window clamp, IP fragmentation).

## The desync pipeline

Step-by-step flow when a TCP payload is sent through `send_with_group()`:

1. **Activation filter check** -- `activation_filter_matches()` in
   `types.rs` checks round number, payload size, and stream byte range
   against the group's `ActivationFilter`. If the filter does not match,
   the payload is sent as-is (no desync).

2. **Adaptive hints resolution** -- `resolve_tcp_hints_with_evolver()` in
   the runtime resolves adaptive parameters (split offset base, TLS record
   offset, entropy mode) from the strategy evolution cache.

3. **Entropy padding** -- `apply_entropy_padding()` optionally prepends
   padding bytes to counter popcount/Shannon entropy detection by the DPI.

4. **Chain splitting** -- `split_tcp_chain()` in `plan_tcp.rs` separates
   TLS prelude steps (`TlsRec`, `TlsRandRec`) from send steps. Prelude
   steps must come before all send steps -- mixing is an error.

5. **TLS prelude application** -- `apply_tls_prelude_steps()` in
   `tls_prelude.rs` fragments the TLS record layer. Also applies HTTP
   modifications (`mod_http`) and TLS minor version overrides (`tlsminor`).

6. **Offset resolution** -- For each send step, `resolve_offset()` in
   `offset.rs` computes the byte position where the split occurs. Adaptive
   offsets (`AutoBalanced`, `AutoHost`, etc.) use
   `resolve_adaptive_offset()` which evaluates multiple candidate bases and
   picks the best one within a budget.

7. **Step planning** -- `plan_tcp()` iterates send steps, resolves offsets,
   and for each `TcpChainStepKind` variant builds the appropriate sequence
   of `DesyncAction` values: writes, fake injections, TTL changes, etc.

8. **Execution** -- The runtime walks `plan.actions` and executes each
   `DesyncAction` against the socket. `AwaitWritable` polls the socket
   between segments to ensure ordering.

## TCP chain steps

| Kind | What it does | Key detail |
|------|-------------|------------|
| `Split` | Splits payload into two TCP segments at offset | Simplest technique; just packet boundary |
| `SeqOverlap` | Sends overlapping TCP segments with fake prefix | Falls back to `Split` if unsupported |
| `Disorder` | Sends first chunk with low TTL (expires before dest) | DPI sees garbage; real data follows |
| `MultiDisorder` | Multiple disorder splits across several offsets | Requires 3+ resulting segments |
| `Fake` | Injects a fake packet (wrong content) at low TTL | Uses `build_fake_packet()` for content |
| `FakeSplit` | Split + inject fake copy of second segment | Degrades to `Split` at boundaries |
| `FakeDisorder` | Disorder + inject fake copy | Degrades to `Disorder` at boundaries |
| `HostFake` | Injects fake Host/SNI region with wrong hostname | Wraps real host with fake before/after |
| `Oob` | Sends chunk as TCP urgent (OOB) data | Urgent byte from `oob_data` config |
| `Disoob` | Disorder + OOB combined | Low TTL + urgent data |
| `TlsRec` | TLS record-layer split (prelude step) | Splits TLS record, not TCP segment |
| `TlsRandRec` | Random TLS record fragmentation (prelude step) | Multiple random-sized TLS records |
| `IpFrag2` | IP-layer fragmentation of the TCP segment | Only on round 1; kernel reassembly trick |

See `references/chain-step-catalog.md` for the full catalog with config fields.

## Strategy probe candidates

Candidates are ordered modern-first (most effective on censored networks tested first):

1. **TLS-record techniques**: `tlsrec_split_host`, `tlsrec_hostfake_split`, `tlsrec_fake_rich`
2. **Split**: `split_host`
3. **Disorder/OOB** (see below)
4. **Legacy parser tricks**: `parser_only`, `parser_unixeol`, `parser_methodeol`
5. **ECH**: `ech_split`, `ech_tlsrec`

### New TCP candidates added

| ID | Steps | requires_fake_ttl |
|----|-------|-------------------|
| `disorder_host` | `disorder` at host+2 | true |
| `tlsrec_disorder` | `tlsrec` + `disorder` at host+2 | true |
| `oob_host` | `oob` at host+2 (MSG_OOB, no TTL) | **false** |
| `tlsrec_oob` | `tlsrec` + `oob` at host+2 | **false** |
| `disoob_host` | `disoob` at host+2 | true |
| `tlsrec_disoob` | `tlsrec` + `disoob` at host+2 | true |
| `tlsrandrec_split` | `tlsrandrec` at sniext+4 + `split` at host+2 | **false** |
| `tlsrandrec_disorder` | `tlsrandrec` at sniext+4 + `disorder` at host+2 | true |
| `tlsrec_hostfake_random` | `tlsrec` + `hostfake` with `random_fake_host=true` | false |
| `split_delayed_50ms` | `split` at host+2 + `Delay(50)` between segments | false |
| `split_delayed_150ms` | `split` at host+2 + `Delay(150)` between segments | false |

### TTL requirements

`config_requires_fake_ttl()` checks for: `"fake"`, `"fakedsplit"`, `"fakeddisorder"`, `"hostfake"`, `"disorder"`, `"disoob"`. Pure `"oob"` does NOT require TTL (uses `MSG_OOB` flag).

### Non-rooted Android constraints

**Not feasible** without root (`SOCK_RAW` + `TCP_REPAIR`): `FakeRst`, `MultiDisorder`. All other techniques work on non-rooted Android.

### TTL fallback on Android VPN/tun

`send_fake_tcp()` gracefully falls back to sending original data (without fake packet) when `setsockopt(IP_TTL)` fails on Android VPN/tun mode, instead of aborting the connection.

### Tournament bracket

Round 1 qualifier tests each candidate against 1 domain (2 probes: HTTP+HTTPS). Candidates failing both are eliminated before the full-matrix Round 2, saving ~70% of probe time on censored networks.

## UDP desync

UDP desync (`plan_udp()` in `plan_udp.rs`) targets QUIC Initial packets.
The chain uses `UdpChainStepKind` variants:

- **FakeBurst** -- Sends N copies of a fake QUIC packet before the real one.
  Fake content comes from `udp_fake_payload()` which selects between
  `QuicFakeProfile::CompatDefault` (static fake), `RealisticInitial`
  (crafted QUIC Initial with fake host), or profile-based bytes.
  Burst count is adjusted by `AdaptiveUdpBurstProfile` (Conservative/
  Balanced/Aggressive).

- **DummyPrepend** -- Sends random 64-byte non-QUIC packets (high bit
  cleared) to confuse DPI state machines.

- **QuicSniSplit** -- Sends a tampered copy of the QUIC Initial with the
  SNI split at `host_start`, causing DPI SNI extraction to fail.

- **QuicFakeVersion** -- Sends a copy with a fake QUIC version number,
  poisoning DPI version detection.

- **IpFrag2Udp** -- IP-layer fragmentation of the UDP datagram (round 1
  only, QUIC traffic only).

All UDP fake packets are sent at low TTL (default 8) so they expire before
reaching the destination.

## Offset system

The offset system determines WHERE in the payload to split. `OffsetExpr`
combines an `OffsetBase` (what the offset is relative to) with a `delta`
(signed adjustment), `proto` filter (Any or TlsOnly), and optional
`repeats`/`skip` for multi-split.

Key categories:
- **Absolute**: `Abs` -- raw byte position (negative = from end)
- **Payload-relative**: `PayloadEnd`, `PayloadMid`, `PayloadRand`
- **Host/SNI-relative**: `Host`, `EndHost`, `HostMid`, `HostRand`
- **SLD (second-level domain)**: `Sld`, `MidSld`, `EndSld`
- **Protocol markers**: `Method` (HTTP), `ExtLen`, `EchExt`, `SniExt` (TLS)
- **Adaptive**: `AutoBalanced`, `AutoHost`, `AutoMidSld`, etc. -- evaluated
  at planning time by trying multiple candidates within a budget

See `references/offset-system.md` for the full table.

## Fake packet mechanics

Fake packets are the core deception mechanism. They must reach the DPI but
NOT the destination server. Several mechanisms ensure this:

### TTL manipulation
`DesyncAction::SetTtl(fake_ttl)` sets the IP TTL to a value that expires
between the DPI and the destination. Default fake TTL is 8 (configurable via
`group.actions.ttl`). `disorder_ttl()` in `plan_tcp.rs` uses the configured
`fake_ttl`, falling back to 1 for disorder steps. After sending the fake,
`RestoreDefaultTtl` restores the original TTL. Auto-TTL
(`AutoTtlConfig`) can dynamically compute the right TTL from traceroute data.

### MD5 signature
`DesyncAction::SetMd5Sig { key_len: 5 }` enables the TCP MD5 signature
option (RFC 2385). The destination rejects packets with unexpected MD5
options, but the DPI typically ignores them. Toggled via `group.actions.md5sig`.

### Entropy padding
`EntropyMode` counters DPI entropy analysis. `Popcount` mode adjusts byte
popcount to bypass GFW-style detection. `Shannon` mode targets Shannon
entropy levels. `Combined` does both. Controlled by
`entropy_padding_target_permil` and `shannon_entropy_target_permil`.

### Fake content generation
`build_fake_packet()` in `fake.rs` constructs fake payload bytes:
1. Base content from `fake_data` (user-provided), HTTP profile, or TLS profile.
2. If TLS: optionally applies SNI replacement (`fake_sni_list`), randomization
   (`FM_RAND`), session ID duplication (`FM_DUPSID`), padding encapsulation
   (`FM_PADENCAP`), and size tuning (`fake_tls_size`).
3. `fake_offset` determines where in the fake packet to start the overlay.

### Delay
`DesyncAction::Delay(u16)` sleeps for N milliseconds between steps for
timer-based evasion. Used by delayed-split variants (`split_delayed_50ms`,
`split_delayed_150ms`) to exploit DPI timeout windows. Capped at 500ms.
Implemented via `tokio::time::sleep()` in the async execution path.

### SeqNum rollback (SeqOverlap)
`DesyncAction::WriteSeqOverlap` sends overlapping TCP data: a fake prefix
occupies the sequence space, then the real data retransmits over it. The
destination's TCP stack uses the first valid data; the DPI may use the fake.
Gated by `seqovl_hard_gate_matches()` (round 1 only, small payloads).

### PcapHook
Optional `PcapHook` callback invoked on each outbound packet during
`execute_tcp_actions()` and `execute_tcp_plan()`. When registered, captures
raw packet bytes to a `PcapRecordingSession` in `ripdpi-monitor` for
diagnostic PCAP recording. No-op when not registered; zero overhead on the
hot path.

## Adding a new desync technique

End-to-end walkthrough for adding a hypothetical "TcpReorder" step:

### 1. Add config variant
In `native/rust/crates/ripdpi-config/src/model.rs`:
- Add `TcpReorder` to the `TcpChainStepKind` enum.
- Implement `as_mode()` mapping (or return `None` if no legacy mode).
- Add any new fields to `TcpChainStep` if needed.
- Update `is_tls_prelude()` to return `false`.

### 2. Add parsing support
In `native/rust/crates/ripdpi-config/src/parse/` (the CLI/config parser):
- Add parsing for the new step kind string (e.g., "tcpreorder").
- Wire it into `TcpChainStep` construction.

### 3. Implement planning
In `native/rust/crates/ripdpi-desync/src/plan_tcp.rs`:
- Add a `TcpChainStepKind::TcpReorder => { ... }` arm in the `match step.kind`
  block inside `plan_tcp()`.
- Generate the appropriate `DesyncAction` sequence. If it needs a new action
  type, add a variant to `DesyncAction` in `types.rs`.

### 4. Implement execution
In `native/rust/crates/ripdpi-runtime/src/runtime/desync.rs`:
- Add handling for any new `DesyncAction` variant in `execute_tcp_actions()`.
- If the step needs special multi-disorder-style execution, add a handler in
  `execute_tcp_plan()` and update `requires_special_tcp_execution()`.
- Update `primary_tcp_strategy_family()` to return a label for the new step.

### 5. Add tests
In `native/rust/crates/ripdpi-desync/src/tests/` -- add plan-level tests.
In `native/rust/crates/ripdpi-runtime/src/runtime/` -- add execution tests
if the new action involves socket operations.

### 6. Update adaptive layer (if applicable)
If the new technique has tunable parameters, add fields to
`AdaptivePlannerHints` and wire them through the strategy evolution system
in `native/rust/crates/ripdpi-runtime/src/runtime/adaptive.rs`.

## Common pitfalls

### Activation filter ordering
Activation filters on individual `TcpChainStep` items are checked
independently from the group-level filter. A step can be skipped even when
the group is active. If a required step is filtered out, the plan may produce
fewer segments than expected, potentially causing `DesyncError` for
`MultiDisorder` (which requires 3+ segments).

### Offset miss on non-TLS traffic
Host-relative offsets (`Host`, `EndHost`, `Sld`, etc.) require protocol
detection to find the SNI/Host header. If the traffic is neither HTTP nor
TLS (e.g., raw TCP proxy), these offsets return `None`. Non-adaptive offsets
cause `DesyncError` on miss; adaptive offsets silently skip. Always pair
host-relative offsets with appropriate `detect` or `proto` match settings
on the group.

### Fake TTL too low or too high
TTL=1 may be dropped by the first hop (the user's own router). TTL too high
reaches the destination and corrupts the connection. The sweet spot is
typically 4-12, depending on network topology. Use `auto_ttl` for dynamic
calculation. `disorder_ttl()` defaults to 1 when unconfigured -- this works
for disorder (where the first chunk is intentionally dropped) but is too
aggressive for fake injection.

### Fragment reassembly interference
`IpFrag2` (TCP) and `IpFrag2Udp` (UDP) rely on the DPI not reassembling
IP fragments. Some DPI systems do reassemble, making this technique
ineffective. These steps only activate on round 1 to avoid repeated
fragmentation on retries.

### TLS prelude must come first
`split_tcp_chain()` enforces that all TLS prelude steps (`TlsRec`,
`TlsRandRec`) precede all send steps. Mixing returns `DesyncError`.
Prelude steps modify the TLS record structure before send steps split
the result into TCP segments.

### FakeSplit/FakeDisorder boundary degradation
When the offset falls at position 0 or at the payload end, `FakeSplit`
degrades to `Split` and `FakeDisorder` degrades to `Disorder`. The
fake injection requires a non-empty second segment to copy. This is
intentional graceful degradation, not a bug.

### SeqOverlap hard gate
`SeqOverlap` only activates on round 1, with stream_start >= 0 and
total payload <= 1500 bytes. Outside these bounds it silently falls back
to `Split`. This prevents sequence overlap on large or resumed streams
where the kernel's TCP state makes overlap unreliable.

### MultiDisorder minimum segments
`plan_multi_disorder_steps()` requires at least 3 resulting segments
(from the resolved offsets + payload boundaries). If fewer offsets
resolve, it returns `DesyncError`. Configure at least 2 offset points.

## QUIC anti-fingerprinting (2026)

New in the 2025-2026 QUIC-evasion research cycle:

### quinn `pad_to_mtu` option

`quinn` 0.11.x merged [PR #2274](https://github.com/quinn-rs/quinn/pull/2274) (June 2025) adding a `pad_to_mtu` transport config flag. When enabled, every application-data QUIC packet is padded to the path MTU regardless of payload size. This defeats size-based QUIC flow fingerprinting on censoring middleboxes that profile short application packets as distinct from Initial/Handshake packets.

**RIPDPI applicability**: the QUIC-carrying crates (`ripdpi-hysteria2`, `ripdpi-masque`, `ripdpi-tuic`, and the optional QUIC probe path in `ripdpi-monitor`) should expose `pad_to_mtu` as a `QuicFakeProfile` variant or a per-session toggle. Size normalisation is cheap at the quinn layer — no application changes needed once the flag is set.

### USENIX Security 2025: GFW QUIC censorship

Peer-reviewed research at USENIX Security 2025 (see [net4people/bbs#505](https://github.com/net4people/bbs/issues/505)) documented the Great Firewall's current QUIC censorship techniques:

- SNI fingerprinting on the QUIC Initial packet (same attack class as TLS SNI blocking; the QUIC Initial carries an unencrypted ClientHello).
- Initial-packet size/shape profiling (the motivation for `pad_to_mtu` above).
- QUIC version negotiation probing.

Implications for `DesyncMode` extensions:

- New QUIC Initial-packet fragmentation variant (`UdpChainStepKind`-level) should mirror the TCP split / fake-TTL approach adapted for QUIC Initial framing.
- QUIC SNI scrambling must coordinate with the TLS layer — splitting the ClientHello across multiple QUIC Initial packets requires the TLS record boundary to align with a QUIC frame boundary.
- Version negotiation: do not advertise a QUIC version that the censor flags; RIPDPI's current `QuicFakeProfile` enum should gain a `VersionPolicy` field.

### No uTLS in Rust yet

TLS ClientHello fingerprint mimicry (JA3/JA4 evasion) requires byte-precise control over the TLS ClientHello extension order, GREASE values, and cipher suite ordering. As of April 2026, **no Rust crate offers this as a library** — the Go ecosystem (`tlsmask`, `httpcloak`, `mic`) still dominates the space.

RIPDPI's answer is `ripdpi-tls-profiles` wrapping BoringSSL directly (via the `boring` crate) with profiles for `chrome_stable`, `firefox_stable`, `safari_stable`, `edge_stable`. The profile set carries an invariant `AvoidsBlocked517ByteClientHello` which all profile weights respect — a ClientHello with a total length of exactly 517 bytes is known to be flagged by certain DPI implementations. New profiles added to the set MUST maintain this invariant; the `desync-engine` test suite enforces it.

If a Rust-native uTLS equivalent emerges (a port of utls to rustls or a ClientHello-builder crate over BoringSSL), it replaces `ripdpi-tls-profiles`' manual boring wrapping. Until then, this is the known gap.

## Related skills

- `ws-tunnel-telegram` — the WS-over-TLS tunnel consumes the same TLS fingerprint profiles and shares the 517-byte invariant concern.
- `rust-panic-safety` — desync execution paths must handle all panic cases at the JNI boundary; `ripdpi-desync` errors are typed via `thiserror`.
- `rust-io-loop` — UDP desync interacts with the tunnel io_loop; when adding UDP fake-packet injection, consult the io_loop skill for the correct integration point.
