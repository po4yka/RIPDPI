# Chain Step Catalog

Complete reference for all `TcpChainStepKind` and `UdpChainStepKind` variants.
Defined in `native/rust/crates/ripdpi-config/src/model.rs`, planned in
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

**Platform requirement**: `seqovl_supported()` must return true (Linux only).

**When to use**: When the DPI reassembles TCP but uses last-segment-wins
semantics. More complex than Split but defeats reassembly-based DPI.

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

**When to use**: When the DPI does not perform IP fragment reassembly.
Effective against simpler DPI but increasingly rare.

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

**Description**: Sends N random 64-byte packets that are NOT valid QUIC
(high bit cleared). Intended to confuse DPI state machines that track
UDP flow state.

**Config fields**: `count`.

**When to use**: When the DPI uses flow-level state tracking for UDP.
Random non-QUIC packets may reset or corrupt the DPI's flow state.

---

### QuicSniSplit

**Description**: Sends a tampered copy of the QUIC Initial with the SNI
split at `host_start`. Uses `tamper_quic_initial_split_sni()` from
`ripdpi-packets`.

**Config fields**: `count`.

**Prerequisite**: Payload must be a valid QUIC Initial with parseable TLS
ClientHello inside. Returns empty if not QUIC.

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

### IpFrag2Udp

**Description**: Sends the UDP datagram as two IP fragments. Only
activates on round 1 for QUIC traffic.

**Config fields**: `split_bytes` (fragment boundary in bytes).

**When to use**: When the DPI does not reassemble IP fragments for UDP.
Similar to TCP IpFrag2 but for QUIC/UDP traffic.

---

## Cross-cutting notes

All TCP chain steps support `inter_segment_delay_ms` (0-500ms) for
timer-based evasion via `DesyncAction::Delay`. When set, the execution
runtime inserts a `tokio::time::sleep()` between segments of that step.
Used by `split_delayed_50ms` and `split_delayed_150ms` probe candidates.
