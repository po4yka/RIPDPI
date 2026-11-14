# QUIC Initial Packetizer

QUIC Initial shaping is owned by a packetizer surface in `ripdpi-packets`. `ripdpi-desync` owns semantic planning and asks the packetizer for valid wire images instead of mutating encrypted Initial bytes directly.

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

- `QuicInitialSeed`: immutable header and ClientHello seed for one Initial family, including `version`, `dcid`, `scid`, `token`, and full TLS ClientHello bytes.
- `QuicInitialPacketLayout`: declarative layout request carrying CRYPTO split offsets, minimum datagram length, extra tail padding, and packet number.
- `QuicInitialBrowserProfile`: browser-like seed family. Current families are `ChromeAndroid` and `FirefoxAndroid`.

## Invariants

1. Semantic split offsets are defined in defragmented ClientHello byte space.
2. Version, connection ID, CRYPTO framing, packet-number, and datagram-length changes go through full Initial re-encryption and header protection.
3. Parsed-packet rewrites preserve the original Initial seed unless a tactic explicitly requests a browser-like synthetic seed.
4. Browser-like seeds reuse the committed TLS fake-profile corpus.
5. Weak mutation families stay explicit and demotable instead of silently entering the production probe pool.

## Layout Families

`quic_crypto_split` and `quic_sni_split` emit low-TTL preludes before the
unchanged original datagram. The prelude uses the layout packet number and is
expected to expire before the server. The client keeps ownership of packet
numbers, ACK state and retransmission. Split input must contain a complete
ClientHello in a UDP datagram of at least 1200 bytes. A split that exceeds the
input datagram budget is skipped. A local size or buffer error on an optional plain prelude send falls back
to the original with explicit fallback evidence when the default TTL can be
restored. Network/ICMP flow errors remain terminal.

Production-facing families:

- `quic_multi_initial_realistic`
- `quic_sni_split`
- `quic_crypto_split`
- `quic_padding_ladder`
- `quic_version_negotiation_decoy`
- `quic_fake_version`
- `quic_dummy_prepend`

Production-facing QUIC candidates that do not use the Initial packetizer:

- `quic_ipfrag2` when UDP raw-packet capability is available
- full-matrix `quic_ipfrag2_*` IPv6 extension-header variants when UDP raw-packet capability is available

Supported by the UDP/desync layer but not currently emitted by `ripdpi-diagnostics-candidates`:

- `quic_compat_burst`
- `quic_realistic_burst`
- `quic_cid_churn`
- `quic_packet_number_gap`

## Packet-number gap series

`quic_packet_number_gap` accepts counts from 0 to 64. The total requested count across the chain must not exceed 64. Zero disables the step. Direct model planning also limits active gap steps to 64 decoys per datagram and preserves the original datagram.

Each series uses a fresh 128-bit DCID from the OS. Its SCID and ClientHello fields are derived from separate OS entropy; the SCID matches the ClientHello transport parameter. Its packet numbers remain 2, 4, 6, and so on. If entropy is unavailable, no decoys are sent and the original datagram is preserved.

The UDP runtime retains up to 64 distinct real Initial CID pairs for response attribution. An unrelated long-header reply is forwarded without marking real traffic successful or extending its response deadline. On context overflow, delivery continues, but ambiguous replies and silence do not produce learning evidence. This correlation does not authenticate the peer or validate a full QUIC handshake.

## Verification

`ripdpi-packets` unit tests pin browser-like seed generation, parsed-seed round trips, CRYPTO split packetization, padding target preservation, and packet-number variation.
