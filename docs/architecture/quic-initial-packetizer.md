# QUIC Initial Packetizer

QUIC Initial shaping is owned by a packetizer surface in `ripdpi-packets`.
`ripdpi-desync` owns semantic planning and asks the packetizer for valid wire
images instead of mutating encrypted Initial bytes directly.

## Responsibilities

The packetizer owns:

- browser-like Initial seed generation;
- CRYPTO frame segmentation;
- padding target selection;
- packet-number variation;
- header-version decoys built from valid Initial seeds.

`ripdpi-desync` owns:

- authority split versus CRYPTO split selection;
- browser-like profile selection;
- padding ladder sizes;
- repeated or decoy Initial scheduling.

## Core Types

- `QuicInitialSeed`: immutable header and ClientHello seed for one Initial
  family, including `version`, `dcid`, `scid`, `token`, and full TLS
  ClientHello bytes.
- `QuicInitialPacketLayout`: declarative layout request carrying CRYPTO split
  offsets, minimum datagram length, extra tail padding, and packet number.
- `QuicInitialBrowserProfile`: browser-like seed family. Current families are
  `ChromeAndroid` and `FirefoxAndroid`.

## Invariants

1. Semantic split offsets are defined in defragmented ClientHello byte space.
2. Version, connection ID, CRYPTO framing, packet-number, and datagram-length
   changes go through full Initial re-encryption and header protection.
3. Parsed-packet rewrites preserve the original Initial seed unless a tactic
   explicitly requests a browser-like synthetic seed.
4. Browser-like seeds reuse the committed TLS fake-profile corpus.
5. Weak mutation families stay explicit and demotable instead of silently
   entering the production probe pool.

## Layout Families

Production-facing families:

- `quic_multi_initial_realistic`
- `quic_sni_split`
- `quic_crypto_split`
- `quic_padding_ladder`
- `quic_version_negotiation_decoy`
- `quic_fake_version`
- `quic_dummy_prepend`

Lab-only or demotable families:

- `quic_compat_burst`
- `quic_realistic_burst`
- `quic_cid_churn`
- `quic_packet_number_gap`

## Verification

`ripdpi-packets` unit tests pin browser-like seed generation, parsed-seed round
trips, CRYPTO split packetization, padding target preservation, and
packet-number variation.
