# Chain Step Catalog

Complete reference for all `TcpChainStepKind` and `UdpChainStepKind` variants.
Defined under `native/rust/crates/ripdpi-config/src/model/`, planned in
`native/rust/crates/ripdpi-desync/src/plan_tcp.rs` and `plan_udp.rs`.

## TCP chain steps (`TcpChainStepKind`)

### Split

**Description**: Splits the payload into two TCP segments at the resolved
offset. The first segment (bytes before offset) is sent, then `AwaitWritable`
polls the socket, then remaining data follows.

**Actions generated**: `Write(chunk)`, `AwaitWritable`

**Config fields**: `offset` (required)

**When to use**: Simplest evasion. Effective when the DPI only inspects the
first packet and does not reassemble. Low overhead, no fake packets needed.

---

### SeqOverlap

**Description**: Sends TCP segments with overlapping sequence numbers. A fake
prefix occupies the initial sequence space, then the real data retransmits
over it. The destination TCP stack keeps the first valid data; some DPI
implementations use the later (fake) data.

**Actions generated**: `WriteSeqOverlap { real_chunk, fake_prefix, remainder }`

**Config fields**: `offset`, `overlap_size` (min 1), `seqovl_fake_mode`
(`Profile` or `Rand`)

**Hard gate**: Only activates on round 1, stream_start >= 0, total <= 1500
bytes. Falls back to `Split` otherwise.

**Platform requirement**: `seqovl_supported()` must return true. It probes
`IpFragmentationCapabilities::tcp_repair` (`ripdpi-runtime-platform/src/retransmit.rs`),
which on Android is only satisfied through the privileged root helper
(`ripdpi-privileged-ops/src/linux/tcp_repair.rs`) -- this is a root-gated
capability, not merely a Linux-vs-other-OS check. `emitter_tier()` marks
`SeqOverlap` as `RootedProduction`. Falls back to `Split` when unsupported,
so a non-rooted device never hard-fails, but the step silently degrades
rather than "working" as configured.

**When to use**: When the DPI reassembles TCP but uses last-segment-wins
semantics, on hardware where the TCP_REPAIR capability is available.
More complex than Split but defeats reassembly-based DPI.

---

### Disorder

**Description**: Sends the first chunk with a low TTL so it expires before
reaching the destination. The DPI processes the expired chunk as part of the
stream, but the destination never sees it. The real data follows with normal
TTL.

**Actions generated**: `SetTtl(disorder_ttl)`, `Write(chunk)`,
`AwaitWritable`, `RestoreDefaultTtl`, optionally `SetTtl(default_ttl)`

**Config fields**: `offset`. TTL from `group.actions.ttl` (default 1 via
`disorder_ttl()`).

**When to use**: When the DPI tracks TCP streams and uses first-packet data.
The disorder causes the DPI to see garbage for the first segment's range.

---

### MultiDisorder

**Description**: Like Disorder but with multiple split points. Each step in
the chain provides an offset; all must be `MultiDisorder` kind. The resolved
offsets are sorted, boundaries at 0 and payload_len are added, producing N
segments. Requires at least 3 resulting segments.

**Actions generated**: Returns `PlannedStep` list only (no `DesyncAction`
list); execution handled by `execute_tcp_plan()` in the runtime which sends
segments in reverse order with TTL manipulation.

**Config fields**: Multiple `TcpChainStep` entries all with kind
`MultiDisorder`, each with a different `offset`.

**When to use**: When single-split disorder is insufficient. Multiple
overlapping disorder points maximally confuse DPI reassembly.

---

### Fake

**Description**: Injects a fake packet containing fabricated content at low
TTL. The fake covers the same byte range as the real segment. DPI sees fake
content; destination never receives it (TTL expired).

**Actions generated**: `SetTtl(fake_ttl)`, optionally `SetMd5Sig`,
`Write(fake_bytes)`, `AwaitWritable`, optionally `SetMd5Sig { key_len: 0 }`,
`RestoreDefaultTtl`, optionally `SetTtl(default_ttl)`

**Config fields**: `offset`. Fake content from `group.actions.fake_data`,
`fake_sni_list`, `fake_mod`, `fake_tls_size`, `http_fake_profile`,
`tls_fake_profile`.

**When to use**: When the DPI does full payload reassembly and checks content.
The fake packet poisons the reassembled stream in the DPI's buffer.

---

### FakeSplit

**Description**: Splits the payload AND injects a fake copy of the second
segment before sending it. The DPI sees the fake second segment (low TTL);
the real second segment follows with normal TTL.

**Actions generated**: Same as `Split` -- `Write(chunk)`, `AwaitWritable`.
The fake injection for the second segment happens in `execute_tcp_plan()`.

**Boundary degradation**: Falls back to `Split` when offset is at position
0 or payload end (no second segment to fake).

**Config fields**: `offset`. Must be the LAST step in the send chain.

**When to use**: Combines split evasion with fake injection for stronger
defense against reassembly-capable DPI.

---

### FakeDisorder

**Description**: Disorder variant that also injects a fake copy. Sends first
chunk at low TTL (like Disorder), plus a fake second segment.

**Actions generated**: `SetTtl(disorder_ttl)`, `Write(chunk)`,
`AwaitWritable`, `RestoreDefaultTtl`, optionally `SetTtl(default_ttl)`

**Boundary degradation**: Falls back to `Disorder` at boundaries.

**Config fields**: `offset`. Must be the LAST step in the send chain.

**When to use**: Maximum confusion: both disorder and fake injection.
Effective against sophisticated DPI with both reassembly and retransmission
tracking.

---

### HostFake

**Description**: Specifically targets the Host/SNI region. Injects fake
packets containing a different hostname before and after the real host
bytes. Optionally splits the real host at a midpoint.

**Actions generated**: Complex sequence:
1. If bytes before host region: `Write` + `AwaitWritable`
2. Fake host injection (with TTL manipulation)
3. Real host bytes (split at midhost if configured)
4. Second fake host injection
5. Remaining bytes after host region

**Config fields**: `offset`, `midhost_offset` (optional secondary split
within the host), `fake_host_template` (custom fake hostname pattern),
`random_fake_host` (when true, generate OS-entropy-seeded random domain
per connection instead of deterministic seed).

**Fallback**: If host range cannot be resolved or is outside the step
boundaries, degrades to `Split`.

**When to use**: When the DPI specifically extracts and checks the SNI/Host
value. Surrounds the real hostname with fake copies to poison extraction.

---

### Oob

**Description**: Sends the chunk as TCP urgent (out-of-band) data. The
urgent byte (from `group.actions.oob_data`, default `'a'`) is appended.
Many DPI systems do not correctly handle TCP urgent data.

**Actions generated**: `WriteUrgent { prefix, urgent_byte }`, `AwaitWritable`

**Config fields**: `offset`, `oob_data` in group actions.

**When to use**: When the DPI does not handle TCP urgent pointer correctly.
The urgent byte displaces payload interpretation.

---

### Disoob

**Description**: Combines Disorder and OOB. Sends the chunk as urgent data
at low TTL.

**Actions generated**: `SetTtl(disorder_ttl)`, `WriteUrgent { prefix,
urgent_byte }`, `AwaitWritable`, `RestoreDefaultTtl`, optionally
`SetTtl(default_ttl)`

**Config fields**: `offset`, `oob_data`.

**When to use**: Double evasion: both TTL expiry and urgent data confusion.

---

### TlsRec (prelude step)

**Description**: Splits the TLS ClientHello into multiple TLS records at
the specified offset(s). This happens at the TLS record layer, NOT the TCP
segment layer. The result is still a single TCP payload but contains
multiple TLS record headers.

**Processing**: Handled by `apply_tlsrec_prelude_step()` in
`tls_prelude.rs`. Converts single TLS record to multiple records by
inserting boundaries into `TlsPreludeState`.

**Config fields**: `offset` with `repeats` (how many splits) and `skip`
(stride between splits). Supports adaptive offsets.

**Constraint**: Must precede all send steps in the chain.

**When to use**: When the DPI parses TLS records and expects a single
ClientHello record. Splitting records confuses TLS-aware DPI parsers.

---

### TlsRandRec (prelude step)

**Description**: Fragments the TLS record into N randomly-sized records
starting from a marker offset. Uses `random_tail_fragment_lengths()` to
distribute bytes across fragments.

**Processing**: Handled by `apply_tlsrandrec_prelude_step()`. Fragment
sizes are bounded by `min_fragment_size` and `max_fragment_size`, adjusted
by `AdaptiveTlsRandRecProfile` (Balanced/Tight/Wide).

**Config fields**: `offset`, `fragment_count`, `min_fragment_size`,
`max_fragment_size`.

**Constraint**: Must precede all send steps in the chain.

**When to use**: When TlsRec with fixed splits is detected by the DPI.
Random fragmentation creates unpredictable record boundaries.

---

### IpFrag2

**Description**: Sends the entire payload as two IP fragments. This is
below the TCP layer -- the kernel reassembles at the destination, but the
DPI may not reassemble IP fragments.

**Actions generated**: `WriteIpFragmentedTcp { bytes, split_offset }`

**Config fields**: `offset` (determines the IP fragment boundary).

**Constraint**: Only activates on round 1 with a valid split position.
Falls back to normal write otherwise.

**Platform requirement**: Routed through `transport_io::raw_socket::send_ip_fragmented_tcp()`,
which needs raw-socket (`CAP_NET_RAW`) access. `emitter_tier()` marks
`IpFrag2` as `RootedProduction`, the same tier as `SeqOverlap`.

**When to use**: When the DPI does not perform IP fragment reassembly,
on hardware with raw-socket access. Effective against simpler DPI but
increasingly rare.

---

### SynData

**Description**: Plans identically to `Split` (`plan_tcp.rs` matches
`Split | SynData` in the same arm) -- the payload is written as a normal
ordered segment. The distinct behavior lives one layer up, at connection
setup: `group_uses_direct_syn_data_tfo()` (in
`ripdpi-proxy-runtime-adapter/src/model/config/tcp_connect.rs`) checks
whether the desync group has a `SynData` step and no `ext_socks` upstream
configured; if so, the FIRST outbound connect on that route requests
direct TCP Fast Open (TFO), sending the request payload inside the SYN
packet itself instead of after the three-way handshake.

**Actions generated**: Same as `Split` -- `Write(chunk)`, `AwaitWritable`.
The TFO request happens at the socket-connect layer, not in the
`DesyncAction` sequence.

**Config fields**: `offset` (same as `Split`).

**Fallback**: `first_write_failure_retries_syn_data_without_tfo()` retries
the first outbound connect without TFO if the initial TFO write fails,
so a network path that rejects TFO SYN data does not hard-fail the
connection.

**Emitter tier**: `NonRootProduction` -- no root or raw-socket capability
required; TFO is a standard Linux socket option.

**When to use**: To save a round trip on the very first request of a
connection when the upstream is reached directly (no SOCKS hop) and the
network path is known to support TFO.

---

### FakeRst

**Description**: Sends a fake, wire-layer TCP RST (`platform::send_fake_rst()`)
immediately before writing the real chunk with its own optional TCP flag
overrides. Unlike the other fake-packet steps, this constructs and injects
the RST at the raw-socket layer rather than through ordinary
`TcpStream::write`.

**Actions generated**: Not expressed as a portable `DesyncAction` sequence;
executed directly by `tcp_plan/execution/fake_rst.rs`, which calls
`platform::send_fake_rst()` then `write_strategy_payload_with_optional_flags_named()`.

**Config fields**: `offset`; `fake_flags` (`TcpFlagOverrides`) control the
injected RST's TCP flags, `original_flags` control the following real
write's flags.

**Emitter tier**: `LabDiagnosticsOnly` -- the most restrictive tier in
`EmitterTier`/`StrategyEmitterTier` (stricter than `RootedProduction`).
Requires raw-socket access and is not eligible for non-root production
builds; see the "Non-rooted Android constraints" section of `SKILL.md`.

**When to use**: Lab/diagnostics evaluation of RST-injection evasion only,
on rooted or desktop-Linux test hardware. Do not wire this into a
production strategy-probe candidate pool intended for non-root Android.

---

## UDP chain steps (`UdpChainStepKind`)

### FakeBurst

**Description**: Sends N copies of a fake QUIC-like packet before the real
UDP payload. All fakes are sent at low TTL.

**Fake content selection** (in `udp_fake_payload()`):
1. If `QuicFakeProfile::RealisticInitial` and QUIC detected: crafts a
   realistic QUIC Initial with fake host.
2. If `QuicFakeProfile::CompatDefault`: static fake QUIC bytes.
3. Otherwise: `fake_data` or `udp_fake_profile` bytes.

**Config fields**: `count` (burst size), adjusted by
`AdaptiveUdpBurstProfile`.

**When to use**: Primary UDP/QUIC evasion. Floods the DPI with fake QUIC
Initials before the real one.

---

### DummyPrepend

**Description**: Sends N browser-like QUIC Initial filler packets
(`build_dummy_prepend_packets()` in `plan_udp/packet_family/split.rs`)
before the real payload. Each copy grows in size (`QUIC_INITIAL_MIN_PREFIX
+ idx * 32` bytes, plus `idx * 8` bytes of tail padding) and alternates
`ChromeAndroid`/`FirefoxAndroid` browser profiles via
`quic_browser_profile_for_index()`. Intended to confuse DPI state machines
that track packet count or expect the SNI in a specific packet index --
this is not raw filler data, it is valid-looking QUIC.

**Config fields**: `count`.

**When to use**: When the DPI uses flow-level or packet-index state
tracking for UDP/QUIC. The varying, browser-realistic filler packets
displace where the DPI expects to find the real Initial.

---

### QuicSniSplit

**Description**: Sends a copy of the QUIC Initial re-packetized with a
split at `authority_split_offset` (the SNI/authority boundary parsed by
`normalized_quic_plan_input()`). Built via `QuicInitialPacketLayout::split_at()`
+ `packetize_input_quic_initial()` (`plan_udp/quic.rs`), which re-emits
from the parsed `QuicInitialSeed` rather than byte-patching the original
packet. `ripdpi-packets::tamper_quic_initial_split_sni()` implements the
same split concept as a standalone byte-tampering function and remains
covered by its own unit tests, but the current planner path does not call
it directly -- verify with `grep -rn tamper_quic_initial_split_sni
native/rust/crates/ripdpi-desync` before assuming it is still wired in.

**Config fields**: `count`.

**Prerequisite**: Payload must be a valid QUIC Initial with parseable TLS
ClientHello inside (`normalized_quic_plan_input()` returns `None`
otherwise, and the step produces no packets).

**When to use**: When the DPI extracts SNI from QUIC Initial packets.
The split SNI confuses the extraction.

---

### QuicFakeVersion

**Description**: Sends a copy of the QUIC packet with a spoofed version
field (`group.actions.quic_fake_version`, default `0x1a2a3a4a`). Uses
`tamper_quic_version()`.

**Config fields**: `count`, `quic_fake_version` in group actions.

**Prerequisite**: Payload must be QUIC.

**When to use**: When the DPI uses QUIC version to decide whether to
inspect the packet. A fake version may cause the DPI to skip inspection.

---

### QuicCryptoSplit

**Description**: Sends a copy of the QUIC Initial re-packetized with a
split at `crypto_split_offset` -- the CRYPTO/ClientHello frame boundary
computed by `normalized_quic_plan_input()` (first entry of
`crypto_frame_boundaries` if in range, otherwise the ClientHello midpoint).
This is a different split point than `QuicSniSplit`: it targets the QUIC
CRYPTO frame structure rather than the TLS SNI/authority field.

**Config fields**: `count`.

**When to use**: When the DPI reassembles the QUIC CRYPTO stream before
extracting SNI, so a split at the SNI boundary alone is insufficient --
splitting the underlying CRYPTO frame confuses reassembly one layer
earlier.

---

### QuicPaddingLadder

**Description**: Sends N copies of the QUIC Initial with increasing tail
padding (`extra_tail_padding = 8 * (idx + 1)` bytes per copy, via
`build_quic_padding_ladder_packets()`), producing a "ladder" of datagram
lengths around the real Initial.

**Config fields**: `count`.

**When to use**: When the DPI fingerprints QUIC flows by a fixed or
narrow Initial-datagram-length signature. The length ladder defeats
length-based matching without altering the ClientHello content itself.

---

### QuicCidChurn

**Description**: Sends N Initials, each with the last byte of the
Destination Connection ID (DCID) mutated
(`*last ^= (idx as u8).wrapping_add(version as u8).max(1)`, via
`build_quic_cid_churn_packets()`), so consecutive decoys carry different
DCIDs from each other and from the real Initial.

**Config fields**: `count`.

**When to use**: When the DPI keys its per-flow tracking state on QUIC
DCID. Churning the DCID across decoys prevents the DPI from correlating
them into one flow or keying onto the real DCID early.

---

### QuicPacketNumberGap

**Description**: Sends N decoy Initials with non-zero, incrementing
packet numbers (`packet_number = (idx + 1) * 2`, via
`build_quic_packet_number_gap_packets()`), rather than the `0` a real
first Initial always carries.

**Config fields**: `count`.

**When to use**: When the DPI expects `packet_number == 0` on the first
Initial of a connection and discards or deprioritizes anything else.
The gapped packet numbers cause the decoys to be silently skipped by
that heuristic while still occupying the DPI's early-packet inspection
window.

---

### QuicVersionNegotiationDecoy

**Description**: Sends a copy of the QUIC Initial with its version field
XORed against `0x0f0f_0f0f` (via `build_quic_version_negotiation_decoy_packets()`
+ `tamper_quic_version()`), simulating a version the DPI has not seen
negotiated yet.

**Config fields**: `count`.

**When to use**: When the DPI's QUIC parser tracks version-negotiation
state per flow and treats an unexpected version as "negotiation already
happened, skip re-inspection." Distinct from `QuicFakeVersion`, which
uses the group's configured `quic_fake_version` constant rather than an
XOR of the real version.

---

### QuicMultiInitialRealistic

**Description**: Sends 2+ Initials with browser-realistic per-index
padding (`extra_tail_padding = idx * 8`) and alternating browser profile
(`quic_browser_profile_for_index()`), via
`build_quic_multi_initial_realistic_packets()`. Mimics Chrome's own
multi-datagram Initial behavior (real Chrome QUIC clients sometimes split
a large ClientHello across more than one Initial datagram).

**Config fields**: `count` (minimum 2 packets regardless of configured
count).

**When to use**: When the DPI treats a single-Initial connection as
suspicious/atypical and a multi-Initial burst better matches real browser
traffic shape, or to combine with other decoy variants for a fuller
realistic-traffic profile.

---

### IpFrag2Udp

**Description**: Sends the UDP datagram as two IP fragments. Only
activates on round 1 for QUIC traffic.

**Config fields**: `split_bytes` (fragment boundary in bytes).

**Platform requirement**: `emitter_tier()` marks `IpFrag2Udp` as
`RootedProduction` -- the only UDP step at that tier; every other
`UdpChainStepKind` variant is `NonRootProduction`. Same raw-socket
requirement class as TCP `IpFrag2`.

**When to use**: When the DPI does not reassemble IP fragments for UDP,
on hardware with raw-socket access. Similar to TCP IpFrag2 but for
QUIC/UDP traffic.

---

## Cross-cutting notes

All TCP chain steps support `inter_segment_delay_ms` (0-500ms) for
timer-based evasion via `DesyncAction::Delay`. When set, the execution
runtime inserts a `tokio::time::sleep()` between segments of that step.
Used by `split_delayed_50ms` and `split_delayed_150ms` probe candidates.

Every non-`IpFrag2Udp` `UdpChainStepKind` step's generated packets are
wrapped in a low-TTL bracket by `append_ttl_wrapped_packets()`
(`plan_udp/sequencing.rs`): `SetTtl(group.actions.ttl.unwrap_or(8))`,
then each packet, then `RestoreDefaultTtl`. This applies uniformly
regardless of which packet-family builder produced the packets, so a new
UDP variant gets TTL wrapping "for free" as long as it is planned through
`build_udp_prelude_packets()`.

When adding or removing a `TcpChainStepKind` or `UdpChainStepKind`
variant, regenerate the matching table in `SKILL.md` in the same change
-- both are meant to describe the same enum from `ripdpi-config`, and
letting them diverge is how the last round of drift happened.
