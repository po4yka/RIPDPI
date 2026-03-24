# pnet (libpnet) Evaluation for RIPDPI Fake Packet Pipeline

**Date:** 2026-03-24
**Scope:** All raw packet construction in `native/rust/`; pnet applicability assessment
**Verdict:** pnet is **not applicable**. RIPDPI's fake packet pipeline operates at the
socket level (setsockopt + vmsplice/splice) and TUN device level (etherparse + smoltcp),
neither of which requires raw socket packet crafting.

---

## 1. Fake Packet Construction Inventory

### 1.1 Proxy Layer (Socket-Level Evasion)

These techniques write application-layer payloads to connected TCP sockets. The kernel
constructs IP/TCP headers; RIPDPI only manipulates socket options to control header fields.

| Technique | Crate | File | Method | Notes |
|-----------|-------|------|--------|-------|
| Fake TCP send | `ripdpi-runtime` | `platform/linux.rs:251-326` | vmsplice/splice + setsockopt | Zero-copy; kernel builds headers |
| TTL manipulation | `ripdpi-runtime` | `platform/linux.rs:367-375` | `socket2::SockRef::set_ttl()` | IP_TTL / IPV6_HOPLIMIT |
| TCP MD5 signature | `ripdpi-runtime` | `platform/linux.rs:124-136` | `libc::setsockopt(TCP_MD5SIG)` | Kernel adds MD5 option to TCP header |
| TCP Fast Open | `ripdpi-runtime` | `platform/linux.rs:119-122` | `libc::setsockopt(TCP_FASTOPEN_CONNECT)` | Kernel handles TFO handshake |
| Drop SACK filter | `ripdpi-runtime` | `platform/linux.rs:173-195` | `SO_ATTACH_FILTER` (BPF) | Kernel-level packet filtering |
| Recv TTL probing | `ripdpi-runtime` | `platform/linux.rs:197-248` | `IP_RECVTTL` + recvmsg cmsg | Reads hop count from ancillary data |
| Original dst | `ripdpi-runtime` | `platform/linux.rs:159-171` | `SO_ORIGINAL_DST` | Transparent proxy destination |
| Socket protection | `ripdpi-runtime` | `platform/linux.rs:138-157` | SCM_RIGHTS via Unix socket | Android VPN bypass |

### 1.2 Desync Planning Layer

These modules decide *what* to send but never construct IP/TCP headers themselves. They
produce `DesyncAction` sequences executed by the proxy layer above.

| Technique | Crate | File | Planning Logic |
|-----------|-------|------|----------------|
| TCP fake + split | `ripdpi-desync` | `plan_tcp.rs:15-37` | `SetTtl` -> `Write(fake)` -> `SetMd5Sig` -> `RestoreDefaultTtl` |
| TCP disorder | `ripdpi-desync` | `plan_tcp.rs` | TTL=1 so kernel sends segment that expires en route |
| TCP OOB urgent | `ripdpi-desync` | `plan_tcp.rs` | `WriteUrgent { prefix, urgent_byte }` |
| UDP fake burst | `ripdpi-desync` | `plan_udp.rs:7-41` | N copies of fake payload with configured TTL |
| Host fake (SNI split) | `ripdpi-desync` | `fake.rs:12-30` | Splits hostname across TCP segments |

### 1.3 Application-Layer Payload Mutation

These modules construct fake *payloads* (TLS ClientHello, HTTP requests, QUIC Initial)
that are written to TCP/UDP sockets. No IP/TCP header construction occurs.

| Technique | Crate | File | LOC | Method |
|-----------|-------|------|-----|--------|
| TLS SNI mutation | `ripdpi-packets` | `tls.rs` | ~833 | Byte-level offset manipulation |
| TLS session ID duplication | `ripdpi-packets` | `tls.rs` | (included above) | Copy session ID from real to fake |
| TLS padding/encapsulation | `ripdpi-packets` | `tls.rs` | (included above) | Adjust padding extension length |
| TLS randomization | `ripdpi-packets` | `tls.rs` | (included above) | Seeded PRNG over ClientHello fields |
| HTTP header mutation | `ripdpi-packets` | `http.rs` | ~507 | ASCII parsing + header rewriting |
| QUIC Initial construction | `ripdpi-packets` | `quic.rs` | ~477 | HKDF + AES-128-GCM + header protection |
| Fake profiles (static) | `ripdpi-packets` | `fake_profiles.rs` | N/A | `include_bytes!()` + hardcoded arrays |
| Hostname generation | `ripdpi-desync` | `fake.rs:44-62` | ~20 | Random alnum with template matching |

### 1.4 Tunnel Layer (TUN Device Packet Construction)

These construct complete IP packets for injection into the TUN device. This is the only
layer that touches IP/TCP/UDP headers directly.

| Technique | Crate | File | Method |
|-----------|-------|------|--------|
| UDP response (IPv4) | `ripdpi-tunnel-core` | `io_loop/packet.rs:104-133` | `etherparse::Ipv4Header` + `UdpHeader::with_ipv4_checksum()` |
| UDP response (IPv6) | `ripdpi-tunnel-core` | `io_loop/packet.rs:135-164` | `etherparse::Ipv6Header` + `UdpHeader::with_ipv6_checksum()` |
| ICMP port unreachable (v4) | `ripdpi-tunnel-core` | `io_loop/packet.rs:169-207` | Raw byte array (test-only, `#[cfg(test)]`) |
| ICMP port unreachable (v6) | `ripdpi-tunnel-core` | `io_loop/packet.rs:209-250+` | Raw byte array (test-only) |
| TCP SYN (test helper) | `ripdpi-tunnel-core` | `io_loop/packet.rs` | Raw byte array (test-only) |
| Packet classification | `ripdpi-tunnel-core` | `classify.rs` | `etherparse::SlicedPacket::from_ip()` |
| TCP flag detection | `ripdpi-tunnel-core` | `io_loop/packet.rs:36-41` | `etherparse::TcpSlice::syn()` / `rst()` |
| Injected RST detection | `ripdpi-tunnel-core` | `io_loop/packet.rs:57-68` | `etherparse` parse + IP identification check |
| Checksum helpers | `ripdpi-tunnel-core` | `io_loop/packet.rs:72-101` | Manual fold-carry (test-only, `#[cfg(test)]`) |

---

## 2. Architecture Analysis: Why Socket Options, Not Raw Sockets

RIPDPI runs on Android without root. The fake packet pipeline is designed around this
fundamental constraint:

```
App payload (fake TLS/HTTP/QUIC bytes)
        |
        v
  setsockopt(IP_TTL, low_value)       <-- kernel sets IP TTL field
  setsockopt(TCP_MD5SIG, fake_key)    <-- kernel adds TCP MD5 option
        |
        v
  vmsplice + splice (zero-copy)       <-- kernel builds IP+TCP headers
        |
        v
  Connected TCP socket                <-- kernel manages seq/ack/window
        |
        v
  setsockopt(IP_TTL, default)         <-- restore for real traffic
  setsockopt(TCP_MD5SIG, key_len=0)   <-- disable MD5
```

The kernel constructs all IP and TCP headers. RIPDPI never needs to:
- Build IP headers for the proxy layer
- Compute TCP checksums (kernel handles this)
- Manage TCP sequence numbers (kernel handles this)
- Construct TCP option fields (kernel handles this via setsockopt)

The only place IP headers are constructed is the tunnel layer, for TUN device responses
(DNS replies, ICMP errors). This is already handled by `etherparse`.

---

## 3. pnet Evaluation

### 3.1 What pnet Provides

- Typed packet builders for Ethernet, IPv4, IPv6, TCP, UDP, ICMP, ARP
- Mutable packet views with field accessors
- Checksum computation
- Raw socket send/receive via `pnet_transport`
- libpcap-based capture via `pnet_datalink`

### 3.2 Why pnet Is Not Applicable

| Concern | Detail |
|---------|--------|
| **libpcap dependency** | `pnet_datalink` links against libpcap. Unacceptable for Android NDK cross-compilation and APK size. Even `pnet_packet` alone pulls the full crate graph. |
| **Raw socket model** | pnet assumes raw socket access (`SOCK_RAW` + `CAP_NET_RAW`). Android apps without root cannot open raw sockets. |
| **Wrong abstraction level** | RIPDPI's proxy layer writes payloads to connected TCP sockets. pnet builds complete packets for injection. These are fundamentally different operations. |
| **Tunnel layer already covered** | `etherparse` already provides zero-copy typed parsing and header builders for the tunnel layer, with a smaller dependency footprint. |
| **No TCP option builder** | pnet's TCP option support is limited. It cannot set MD5SIG, Fast Open, or BPF filters -- these require `setsockopt`. |

### 3.3 Dependency Comparison

| Library | Features Used | Size Impact | Android Compatible |
|---------|--------------|-------------|-------------------|
| `etherparse 0.19` | IPv4/IPv6/TCP/UDP parsing + building | ~50KB | Yes |
| `socket2 0.5` | TTL, unicast hops | ~30KB | Yes |
| `libc 0.2` | setsockopt, vmsplice, splice | ~0 (sys bindings) | Yes |
| `nix 0.29` | pipe, splice, mmap, sendmsg | ~100KB | Yes |
| **`pnet 0.36`** | **Packet building (if used)** | **~500KB + libpcap** | **No (libpcap)** |

---

## 4. Alternatives Assessed

### 4.1 etherparse (Already Used -- Recommended to Keep)

Currently used in `ripdpi-tunnel-core` for:
- Parsing: `SlicedPacket::from_ip()` for classification
- Building: `Ipv4Header::new()`, `UdpHeader::with_ipv4_checksum()` for UDP responses

Strengths: zero-copy slicing, correct checksum computation, no unsafe, small footprint.
Limitation: no ICMP header builder (hence manual bytes for test-only ICMP construction).

### 4.2 smoltcp (Already Used -- Recommended to Keep)

Provides a full userspace TCP/IP stack for the tunnel layer. Handles TCP state machine,
sequence numbers, congestion control, and retransmission. Not a packet builder library
per se, but generates complete IP packets via its `Device` trait.

### 4.3 Custom Typed Builders (Potential Improvement)

The `ripdpi-packets` crate performs extensive byte-level mutation of TLS ClientHello,
HTTP requests, and QUIC Initial packets. This code (~1,800 lines) uses raw byte offsets
and manual length field adjustments. A typed builder layer could:

- Reduce off-by-one risks in TLS extension offset arithmetic
- Make SNI/session-ID/padding mutations more readable
- Provide compile-time guarantees on field sizes

However, the mutation semantics are inherently byte-level: "change bytes 47-63 of a
TLS ClientHello while adjusting length fields at offsets 3, 7, and ext_len_start."
A typed API would need to expose the same offset-based access, adding abstraction
without eliminating the core complexity.

**Verdict:** Not recommended as a standalone effort. The existing code works correctly
and has been validated against real DPI systems. Typed wrappers would be appropriate
only if the mutation logic is being rewritten for other reasons.

---

## 5. Recommendations

### 5.1 Keep Current Architecture (No pnet)

The socket-option + vmsplice/splice approach is correct for Android's permission model.
pnet would add a large, incompatible dependency for functionality RIPDPI does not need.

### 5.2 Keep etherparse for Tunnel Layer

`etherparse` covers all production packet construction needs (UDP responses). The manual
checksum helpers and ICMP builders are test-only (`#[cfg(test)]`) and don't justify a
new dependency.

### 5.3 Consider: Extract Checksum Helpers If Production Use Emerges

The `checksum_sum()` / `finalize_checksum()` functions at `packet.rs:72-91` are currently
test-gated. If a future feature requires production ICMP or manual checksum computation,
extract these into a shared utility. Until then, `#[cfg(test)]` is appropriate.

### 5.4 No Action Required on TLS/HTTP/QUIC Mutation Code

The byte-level mutation code in `ripdpi-packets` is purpose-built for DPI evasion.
It operates at the application-layer payload level, which is outside pnet's scope
entirely. The code is functional and tested -- no refactoring needed.

---

## 6. Risk Register

| Risk | Severity | Mitigation |
|------|----------|------------|
| libpcap dependency from pnet | **Blocker** | Do not adopt pnet. Already mitigated by this decision. |
| Manual checksum bugs in test code | Low | Test-only code (`#[cfg(test)]`); validated by test assertions. |
| Byte-level TLS mutation fragility | Medium | Covered by existing tests + real-world DPI validation. Consider typed wrappers only if rewriting mutation logic. |
| etherparse API breakage on upgrade | Low | Pinned to 0.19; semver-compatible. Review on major version bumps. |
| vmsplice/splice portability | Low | Linux/Android only; gated behind `#[cfg(any(target_os = "linux", target_os = "android"))]` via `platform/mod.rs`. |

---

## 7. File Reference

| Purpose | Path |
|---------|------|
| Fake TCP send (vmsplice/splice) | `native/rust/crates/ripdpi-runtime/src/platform/linux.rs` |
| Platform abstraction | `native/rust/crates/ripdpi-runtime/src/platform/mod.rs` |
| TCP desync planning | `native/rust/crates/ripdpi-desync/src/plan_tcp.rs` |
| UDP desync planning | `native/rust/crates/ripdpi-desync/src/plan_udp.rs` |
| Fake packet builder | `native/rust/crates/ripdpi-desync/src/fake.rs` |
| Desync action types | `native/rust/crates/ripdpi-desync/src/types.rs` |
| TLS mutation | `native/rust/crates/ripdpi-packets/src/tls.rs` |
| HTTP mutation | `native/rust/crates/ripdpi-packets/src/http.rs` |
| QUIC Initial construction | `native/rust/crates/ripdpi-packets/src/quic.rs` |
| Fake profiles | `native/rust/crates/ripdpi-packets/src/fake_profiles.rs` |
| Tunnel packet construction | `native/rust/crates/ripdpi-tunnel-core/src/io_loop/packet.rs` |
| Packet classification | `native/rust/crates/ripdpi-tunnel-core/src/classify.rs` |
| TUN device bridge | `native/rust/crates/ripdpi-tunnel-core/src/device.rs` |
| IO loop (6-phase) | `native/rust/crates/ripdpi-tunnel-core/src/io_loop.rs` |
| Workspace dependencies | `native/rust/Cargo.toml` |
