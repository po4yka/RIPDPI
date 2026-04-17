# QUIC Initial Packetizer

## Purpose

Phase 5 moves QUIC Initial shaping out of `ripdpi-desync` byte hacks and into a
single packetizer-owned wire-image surface in `ripdpi-packets`.

The packetizer owns:

- browser-like Initial seed generation
- CRYPTO frame segmentation
- padding target selection
- packet-number variation
- header-version decoys built from valid Initial seeds

`ripdpi-desync` owns only semantic planning:

- choose authority split vs CRYPTO split
- choose browser-like profile mix
- choose padding ladder sizes
- choose when to emit repeated or decoy Initials

## Core Types

- `QuicInitialSeed`
  - immutable header and ClientHello seed for one Initial family
  - carries `version`, `dcid`, `scid`, `token`, and a full TLS ClientHello
- `QuicInitialPacketLayout`
  - declarative packet layout request
  - carries CRYPTO split offsets, minimum datagram length, extra tail padding,
    and packet number
- `QuicInitialBrowserProfile`
  - current browser-like seed families
  - `ChromeAndroid`
  - `FirefoxAndroid`

## Invariants

1. Semantic split offsets are defined in defragmented ClientHello byte space.
2. Any change to version, connection IDs, CRYPTO framing, packet number, or
   datagram length must go through full Initial re-encryption and header
   protection.
3. Parsed-packet rewrites preserve the original Initial seed material unless a
   tactic explicitly requests a browser-like synthetic seed.
4. Browser-like seeds reuse the committed TLS fake-profile corpus rather than
   duplicating a separate QUIC profile catalog.
5. Weak mutation families stay explicit and demotable. If a tactic is not a
   stable Initial-layout strategy, it should not remain in the production probe
   pool.

## Initial Layout Families

The current Phase 5 production-facing layout families are:

- `quic_multi_initial_realistic`
- `quic_sni_split`
- `quic_crypto_split`
- `quic_padding_ladder`
- `quic_version_negotiation_decoy`
- `quic_fake_version`
- `quic_dummy_prepend`

The current lab-only / demoted mutation families are:

- `quic_compat_burst`
- `quic_realistic_burst`
- `quic_cid_churn`
- `quic_packet_number_gap`

## Packetizer Migration Order

1. Keep compatibility wrappers in `ripdpi-packets`:
   - `build_realistic_quic_initial`
   - `tamper_quic_initial_split_sni`
   - `tamper_quic_initial_split_crypto`
   - `tamper_quic_version`
2. Move `plan_udp.rs` QUIC step builders onto `QuicInitialSeed` +
   `QuicInitialPacketLayout`.
3. Export the exact QUIC layout family through strategy-probe candidate
   summaries and recommendation traces.
4. Remove weak mutation families from production candidate defaults while
   leaving the underlying step kinds available for explicit lab use.

## Verification Plan

- `ripdpi-packets` unit tests pin:
  - browser-like seed generation
  - parsed-seed round trips
  - CRYPTO split packetization
  - padding target preservation
  - packet-number wire-image variation
- `ripdpi-desync` unit tests pin:
  - `DummyPrepend`
  - `QuicSniSplit`
  - `QuicCryptoSplit`
  - `QuicMultiInitialRealistic`
  - `QuicFakeVersion`
  - `QuicPaddingLadder`
  - `QuicVersionNegotiationDecoy`
- `ripdpi-monitor` tests pin:
  - production candidate pool excludes demoted QUIC mutation families
  - strategy summaries carry exact `quicLayoutFamily`
