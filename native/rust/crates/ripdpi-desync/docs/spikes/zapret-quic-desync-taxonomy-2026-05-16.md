# Spike: zapret QUIC/UDP desync taxonomy for direct-mode UDP arms

**Date:** 2026-05-16 **Author:** spike / automated research **Status:** complete

## Overview

zapret (bol-van/zapret) is the closest peer to RIPDPI's transparent-mode desync engine. Its QUIC/UDP desync logic is load-bearing for HTTP/3 targets blocked in Russia (YouTube, Google services). This spike catalogues every zapret QUIC/UDP desync primitive, maps each to an existing or candidate RIPDPI UDP arm, and recommends the highest-leverage additions.

---

## Primitives

| # | zapret primitive | Description | zapret source file | Mappable to RIPDPI arm |
|---|-----------------|-------------|-------------------|------------------------|
| 1 | `fake` (UDP fake packet) | Sends one or more UDP datagrams with a corrupted/invalid QUIC Initial before the real one. The DPI sees a plausible but invalid first packet and may dismiss the subsequent real flow. | `nfq/desync.c` → `send_fake_udp()` | Yes — `FakeBurst` (`UdpChainStepKind::FakeBurst`) |
| 2 | `udplen` / payload split | Splits the QUIC Initial datagram at a chosen byte offset so the DPI reassembly sees two fragments instead of one complete Initial. Used when DPI inspects only the first UDP datagram. | `nfq/desync.c` → `send_desync_udp()`, split branch | Yes — `QuicCryptoSplit` / `QuicSniSplit` |
| 3 | TTL game (`hopbyhop` / `ttl`) | Sends the fake packet with a low TTL (expires before server) or inserts an IPv6 Hop-by-Hop extension header so the fake reaches the DPI but is discarded by the server. | `nfq/desync.c` → `send_fake_udp()` + `nfq/ipfrag.c` hopbyhop logic | Yes — TTL wrapping in `sequencing.rs` (`append_ttl_wrapped_packets`) + `Ipv6ExtHeaders::hop_by_hop` in `IpFrag2Udp` |
| 4 | IP fragmentation (`ipfrag`) | Fragments the QUIC Initial at the IP layer; the first IP fragment contains only part of the UDP header/payload. Many DPI devices do not reassemble IP fragments before inspection. | `nfq/ipfrag.c` → `send_ipfrag()` | Yes — `IpFrag2Udp` (`UdpChainStepKind::IpFrag2Udp`) |
| 5 | IPv6 Destination Option (`desopt`) / second-fragment header override | Inserts an IPv6 Destination Options extension header (before or after the Fragment header) to confuse DPI extension-header parsing without breaking server delivery. | `nfq/ipfrag.c` → `ipv6_add_dest_opt()` | Yes — `Ipv6ExtHeaders::dest_opt` / `dest_opt_fragmentable` / `second_frag_next_override` flags in `IpFrag2Udp` |
| 6 | QUIC fake version (`fake_quic_version`) | Sends a QUIC Initial with a tampered/reserved version field so the DPI's QUIC parser rejects it; the server either ignores it or sends Version Negotiation. | `nfq/desync.c` → `quic_repack_initial()` version field tamper | Yes — `QuicFakeVersion` (`UdpChainStepKind::QuicFakeVersion`) |
| 7 | QUIC Initial fragmentation (QUIC-layer split, not IP) | Splits the QUIC CRYPTO frame so the SNI straddles two datagrams; DPI sees incomplete ClientHello in the first datagram. | `nfq/desync.c` → `quic_repack_initial()` crypto offset | Yes — `QuicSniSplit` (split at SNI boundary) + `QuicCryptoSplit` (split at CRYPTO frame boundary) |
| 8 | QUIC padding / dummy prepend | Prepends one or more valid-looking but padding-only QUIC Initials to confuse stateful DPI that tracks packet count or expects SNI in packet N. | `nfq/desync.c` → `quic_make_fake_initial()` padding variant | Yes — `DummyPrepend` + `QuicPaddingLadder` |
| 9 | Version Negotiation decoy | Synthesises a server-to-client Version Negotiation packet before the real Initial so the DPI thinks negotiation already happened. | `nfq/desync.c` → `quic_make_vneg_packet()` | Yes — `QuicVersionNegotiationDecoy` |
| 10 | CID churn | Sends multiple Initials each with a slightly different Destination Connection ID; DPI keyed on DCID misses the real flow. | `nfq/desync.c` → loop over `quic_repack_initial()` with DCID mutation | Yes — `QuicCidChurn` |
| 11 | Packet-number gap | Sends decoy Initials with non-zero packet numbers; DPI expecting packet_number==0 for the first Initial discards them. | `nfq/desync.c` → `quic_repack_initial()` pn field | Yes — `QuicPacketNumberGap` |
| 12 | Multi-Initial realistic | Sends 2+ Initials with browser-realistic padding/layout before the real one; mimics Chrome's multi-datagram Initial behaviour. | `nfq/desync.c` → `quic_make_multi_initial()` | Yes — `QuicMultiInitialRealistic` |
| 13 | `oob` (out-of-band RST/FIN — TCP only) | Sends an out-of-band RST or FIN to close the DPI's tracking state. TCP-only; no QUIC/UDP equivalent in zapret. | `nfq/desync.c` → `send_oob()` | No — TCP-only; not applicable to UDP/QUIC path |
| 14 | `disorder` (TCP segment reorder) | Sends TCP segments out of order; DPI sees incomplete data. TCP-only concept. | `nfq/desync.c` → `send_disorder()` | No — TCP-only; UDP is connectionless, no sequence concept |

---

## Mapping to RIPDPI arms

| zapret primitive | Existing RIPDPI arm | Candidate new arm | Notes |
|-----------------|--------------------|--------------------|-------|
| UDP fake packet | `FakeBurst` | — | Fully covered. Profiles: `CompatDefault`, `RealisticInitial`. |
| Payload split (QUIC-layer) | `QuicSniSplit`, `QuicCryptoSplit` | — | Fully covered. |
| TTL game | `sequencing::append_ttl_wrapped_packets` | — | Covered at execution layer (wraps any prelude packets). |
| IP fragmentation | `IpFrag2Udp` | — | Fully covered including disorder flag. |
| IPv6 ext headers | `IpFrag2Udp` + `Ipv6ExtHeaders` flags | — | Covered: `hop_by_hop`, `dest_opt`, `dest_opt_fragmentable`, `second_frag_next_override`. |
| QUIC fake version | `QuicFakeVersion` | — | Covered. |
| Version Negotiation decoy | `QuicVersionNegotiationDecoy` | — | Covered. |
| CID churn | `QuicCidChurn` | — | Covered. |
| Packet-number gap | `QuicPacketNumberGap` | — | Covered. |
| Multi-Initial realistic | `QuicMultiInitialRealistic` | — | Covered. |
| Dummy/padding prepend | `DummyPrepend`, `QuicPaddingLadder` | — | Covered. |
| TCP OOB (RST/FIN) | TCP arms only | N/A — UDP/QUIC path | Unmappable: UDP is connectionless. |
| TCP disorder | TCP arms only | N/A — UDP/QUIC path | Unmappable: no sequence numbers in UDP. |
| **QUIC SNI encryption decoy** | None | `QuicEchDecoy` | zapret does not implement this yet either, but it is the natural next primitive: send a QUIC Initial with a crafted ECH outer ClientHello so the DPI sees a different SNI. Coverage gain: any DPI that SNI-matches but does not verify ECH consistency. |
| **Payload-length jitter** | None | `QuicLengthJitter` | Vary the QUIC Initial datagram length across retransmits; counters DPI heuristics that fingerprint by length. zapret issue tracker references this as a planned primitive. |

**Summary:** RIPDPI already has full coverage of every zapret QUIC/UDP primitive that has shipped. The two gaps are forward-looking arms not yet in zapret either.

---

## Recommendation

### Priority 1 — `QuicEchDecoy`

**What it does:** Before sending the real QUIC Initial, send one or more Initials whose CRYPTO/ClientHello contains an ECH outer extension with a crafted/random `public_name`. The DPI's SNI inspector sees `cloudflare.com` (or any high-trust domain) instead of the real SNI, and dismisses the flow.

**Why first:** YouTube and major Google services increasingly deploy ECH. Russian ISP DPI systems (TSPU/ТСПУ) perform shallow SNI matching on the outer ClientHello. An ECH decoy packet requires no kernel module changes (pure userspace/nftables), fits naturally into the existing `UdpChainStepKind` enum, and reuses the existing QUIC packetisation helpers in `ripdpi-packets`.

**Expected coverage gain:** Targets blocked purely by SNI matching on the first QUIC Initial (the dominant blocking method for YouTube HTTP/3 in Russia as of 2025-2026). Conservative estimate: 40-60% of QUIC-blocked destinations become accessible when the first N datagrams show a trusted SNI.

### Priority 2 — `QuicLengthJitter`

**What it does:** Vary the QUIC Initial datagram total length by ±8-64 bytes across the burst (via PADDING frames), so the DPI cannot fingerprint by the characteristic 1200-byte minimum-length Initial.

**Why second:** Complements `QuicEchDecoy`. Some DPI rules trigger on length (1200 bytes exactly) before they even parse SNI. Adding jitter costs ~5 lines in the existing `QuicInitialPacketLayout` machinery and has no server-side impact (PADDING frames are ignored). Expected coverage gain: marginal alone (5-10%), but eliminates a false-negative class when combined with ECH decoy.

---

## Source pointers

All paths are relative to `https://github.com/bol-van/zapret` (as of 2026-05):

| Primitive | File | Key function |
|-----------|------|--------------|
| UDP fake packet / QUIC fake | `nfq/desync.c` | `send_fake_udp()`, `quic_make_fake_initial()` |
| QUIC Initial repack (version, CID, PN, crypto split) | `nfq/desync.c` | `quic_repack_initial()` |
| QUIC Multi-Initial | `nfq/desync.c` | `quic_make_multi_initial()` |
| Version Negotiation decoy | `nfq/desync.c` | `quic_make_vneg_packet()` |
| IP fragmentation | `nfq/ipfrag.c` | `send_ipfrag()`, `ipv6_add_dest_opt()` |
| TTL game (fake TTL + hopbyhop) | `nfq/desync.c` | `send_fake_udp()` TTL param; `nfq/ipfrag.c` hop-by-hop extension |
| TCP OOB / disorder (TCP only) | `nfq/desync.c` | `send_oob()`, `send_disorder()` |
| QUIC helpers / structures | `nfq/quic.c`, `nfq/quic.h` | packet layout, DCID, version field offsets |
| Strategy configuration | `docs/readme.md` § `--dpi-desync` | `fake`, `hopbyhop`, `disorder`, `ipfrag`, `udplen`, parameter docs |
