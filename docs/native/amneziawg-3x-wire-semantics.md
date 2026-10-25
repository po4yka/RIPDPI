# AmneziaWG 3.x wire semantics

This document gives the AmneziaWG 3.x ("protocol 3.1") rules that `ripdpi-warp-core` implements.
The reference is `amneziawg-go` v3.1.20260828 (commit `b5928ef`). File references are to that tree
unless the line names another repository. The Rust code is in
`native/rust/crates/ripdpi-warp-core/src/awg_v3.rs`.

## Configuration keys

| `.conf` key (`[Interface]`) | UAPI key | Value | Source |
| --- | --- | --- | --- |
| `HeaderProtectionKey` | `header_protection_key` | 32-byte key. Base64 in `.conf`, hex in UAPI. All zero = off. | `uapi.go:473-479`; tools `config.c:548-551` |
| `ContentPaddingAddition` | `content_padding_addition` | Range, bytes | `uapi.go:481-488` |
| `RekeyAfterTime`, `RekeyTimeout`, `RejectAfterTime`, `KeepaliveTimeout` | same, snake case | Range, seconds | `uapi.go:490-520` |
| `MaxHandshakeAttempts` | `max_handshake_attempts` | Range, count | `uapi.go:522-528` |
| `RandomTrailers`, `DisableCookies` | `random_trailers`, `disable_cookies` | `on`/`off` or `0`/`1` | `uapi.go:530-544`; tools `config.c:414-446` |
| `[Peer] PersistentKeepalive` | `persistent_keepalive_interval` | Range, seconds | `uapi.go:663-674`; tools `config.c:596-599` |

A range is `lo` or `lo-hi` in decimal, with `lo <= hi`. `x-x` is valid (`noise-types.go:106-131`).
The tools parse the 3.x ranges as 16-bit ranges (tools `type.c:40-60`). A zero range (`0`) means
"not set" (`noise-types.go:138-140`). Each use draws a new value uniformly from `[lo, hi]`
(`noise-types.go:142-145`). H1 to H4 use the same range type and must not overlap (`uapi.go:828-838`).

The UAPI peer key `protocol_version` accepts only `1` (`uapi.go:712-715`). It is the WireGuard
UAPI version. It does not select AWG behavior. The name "3.1" is a label of the Amnezia client
(amnezia-client `protocolConstants.h:303`). The client defaults are S1 to S4 = 12, Jc 4 to 6,
Jmin 10, Jmax 50, H1 to H4 = 1 to 4, `ContentPaddingAddition = 10-100`, `RandomTrailers = on`,
`DisableCookies = on`, and `PersistentKeepalive = 25-35` (amnezia-client `awgInstaller.cpp:37-63`,
`protocolConstants.h:282-313`).

## Datagram layout

Every AWG message has this layout: `S` random bytes, then the message, then an optional trailer.
`S` is S1, S2, S3, or S4 for initiation, response, cookie reply, and transport.

1. **Header protection.** When the key is set, the sender makes an IETF ChaCha20 stream
   (12-byte nonce, block counter 0) with the key and the first 12 bytes of the `S` prefix as the
   nonce (`noise-protocol.go:647-656`). It XORs the stream into the message after the MACs are
   final:
   - Initiation, response, and cookie reply: all message bytes (148, 92, or 64)
     (`send.go:149-171`, `send.go:199-228`, `send.go:258-276`).
   - Transport: only the 16-byte header (type, receiver, counter) (`send.go:632-640`).
     The ciphertext is not changed.
2. **Prefix size rule.** When the key is set, S1, S2, S3, and S4 must each be at least 12
   (`HeaderCipherNonceSize`, `noise-types.go:23`; check in `uapi.go:852-859`).
3. **Random trailer.** When `RandomTrailers` is on, the sender adds random bytes after a
   handshake or cookie message. The length is uniform in `[0, W - (S + size))`, or 0 when
   `W < S + size` (`send.go:548-569`). `W` is the peer UDP window (see below). A cookie reply uses
   the default window of 500 (`send.go:259`). A transport message has no trailer outside the
   ciphertext.
4. **Transport content padding.** The sender adds zero bytes to the plaintext before
   encryption (`send.go:604-630`). With `ContentPaddingAddition` set, the count is a new range
   value, capped at `W - packetSize`. Else, with `RandomTrailers` on, the count is uniform in
   `[0, W - packetSize)`. Else the plaintext is padded to a multiple of 16. `packetSize` is
   `plaintext + 32 + S4`; when `W < packetSize`, the count is 0 (`send.go:528-546`). The receiver
   uses the IP length field to remove the padding (`receive.go:529-556`). A padded keepalive has
   only zero bytes and is a keepalive (`receive.go:523`).

The UDP window `W` starts at 500 (`constants.go:27`, `peer.go:83`). It increases to the largest
`S4 + 32 + plaintext` sent (`send.go:584-587`) and to the largest `S4 + 16 + plaintext` received
(`receive.go:518-521`). It resets to 500 when the endpoint changes (`peer.go:288-291`).

## Receive classification

The receiver drops datagrams shorter than 32 bytes (`receive.go:137`). With header protection on,
it uses the first 12 datagram bytes as the nonce and gets a 4-byte type mask from the first
keystream bytes (`receive.go:144-154`). It then tests the types in this order: initiation,
response, cookie reply, transport (`receive.go:605-659`). A handshake or cookie type matches
when the size is `S + size`, or larger when `RandomTrailers` is on. A transport type matches when
the size is at least `S4 + 32`. The unmasked type must be in the H range of that type. The
receiver then removes the trailer and unmasks the rest of the protected bytes with the same
keystream (`receive.go:159-245`).

## Behavior outside the datagram

- `DisableCookies = on` stops the responder under-load path. The responder does not check MAC2
  and does not send cookie replies (`receive.go:372-387`). A client that only starts handshakes
  is not affected.
- Timer ranges replace the WireGuard constants: retransmit uses `RekeyTimeout`; send rekey uses
  `RekeyAfterTime`; key expiry uses the high bound of `RejectAfterTime`; passive keepalive uses
  `KeepaliveTimeout`; the retry limit uses `MaxHandshakeAttempts` (`timers.go:228-318`,
  `send.go:286-295`).
- The persistent keepalive timer draws a new interval from its range after each authenticated
  packet (`timers.go:198-203`).
- Junk packets have a length in `[Jmin, Jmax)` in this version (`noise-protocol.go:632-645`).

## RIPDPI implementation limits

- The Android arm64 S3/S4 floor stays active (`ARM64_S34_GUARD_REQUIRED`). Header protection
  needs S3 and S4 of at least 12, so header protection cannot start on Android arm64 until a
  physical device revalidation changes the floor. The Kotlin activation check rejects non-zero
  S3/S4 on all platforms.
- boringtun owns the Noise timers. RIPDPI applies `RekeyAfterTime` after each data send
  (earlier than the fixed 120 s only), and applies the `PersistentKeepalive` range with its own
  timer. `RekeyTimeout`, `RejectAfterTime`, `KeepaliveTimeout`, and `MaxHandshakeAttempts` are
  validated but boringtun keeps its fixed values.
- RIPDPI does not pad its own keepalives or pad transport data to a multiple of 16. boringtun
  reports a padded keepalive from the peer as an invalid packet after it updates the receive
  timer.
- RIPDPI does not send cookie replies as a client, so `DisableCookies` has no effect.
- Junk lengths stay in `[Jmin, Jmax]`. The receiver ignores junk, so this does not change
  interoperability.
