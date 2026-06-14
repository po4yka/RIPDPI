# Mieru wire protocol — RIPDPI implementation contract

Byte-exact notes for the `ripdpi-mieru` engine, transcribed from upstream
[enfein/mieru](https://github.com/enfein/mieru) `main` (fetched 2026-06-05):
`docs/protocol.md`, `pkg/cipher/api.go`, `pkg/protocol/{metadata,segment,session,padding}.go`.

> **Scope of this crate.** TCP carrier only (the recommended, faster transport;
> upstream `MaxFragmentSize` returns `maxPDU` for the stream transport — no
> KCP/ARQ fragmentation on TCP). The UDP carrier (KCP-like reliable ARQ) is
> **out of scope** and returns `MieruError::UdpUnsupported`.
>
> **Verification status.** The cipher/codec primitives are covered by
> deterministic unit vectors; the framed session is exercised by a
> spec-faithful in-crate loopback peer (client ⇄ server using this same code) —
> i.e. **self-consistency** is verified. **On-wire interoperability with a real
> mieru server is NOT verified** (no server available offline). Treat interop as
> unconfirmed until a live `mita` server test is run.

All integers are **big-endian** unless stated.

## 1. Cipher & key derivation (`pkg/cipher/api.go`)

- AEAD = **XChaCha20-Poly1305** (nonce **24 bytes**, key **32 bytes**, tag **16 bytes**).
- `hashedPassword = SHA256(rawPassword ‖ 0x00 ‖ username)` — the `0x00` separates
  password and username (`HashPassword`).
- `timeSalt`: take `unixTime` (seconds); round to the **nearest 2 minutes**
  (`round(unix/120)*120`); encode as **8-byte big-endian u64**; `timeSalt = SHA256(those 8 bytes)`.
- `key = PBKDF2-HMAC-SHA256(password = hashedPassword, salt = timeSalt, iterations = 64, dkLen = 32)`.
- Clock skew tolerance ≤ 4 min; a server tries up to 3 `timeSalt`s (T-1, T, T+1
  at 2-min granularity). The client derives with its own `T`.
- **Nonce**: 24 random bytes, then **the last 4 bytes are replaced** by the first
  4 bytes of `SHA256(username ‖ nonce[0..16])` (user-lookup acceleration).
  Each AEAD operation **increments the nonce by 1** (little-endian counter over
  the 24 bytes, matching Go's `increaseNonce`) and the incremented value is used
  for the next operation.

## 2. Metadata (`pkg/protocol/metadata.go`) — always 32 bytes (`MetadataLength = 32`)

`protocolType` (1 byte, byte 0):
`closeConnRequest=0, closeConnResponse=1, openSessionRequest=2, openSessionResponse=3,
closeSessionRequest=4, closeSessionResponse=5, dataClientToServer=6, dataServerToClient=7,
ackClientToServer=8, ackServerToClient=9`.

`timestamp` (bytes 2..6, u32) = `unixSeconds / 60` (minute granularity); peer
accepts within ±1 minute.

**dataAckStruct** (data/ack — types 6/7/8/9):
| off | size | field |
|----|----|----|
| 0 | 1 | protocol |
| 1 | 1 | unused |
| 2 | 4 | timestamp |
| 6 | 4 | sessionID |
| 10 | 4 | seq |
| 14 | 4 | unAckSeq |
| 18 | 2 | windowSize |
| 20 | 1 | fragment (0 = last) |
| 21 | 1 | prefixLen (padding 1 length) |
| 22 | 2 | payloadLen (encapsulated payload, excl. auth tag) |
| 24 | 1 | suffixLen (padding 2 length) |
| 25 | 7 | unused |

**sessionStruct** (open/close session — types 2/3/4/5):
| off | size | field |
|----|----|----|
| 0 | 1 | protocol |
| 1 | 1 | unused |
| 2 | 4 | timestamp |
| 6 | 4 | sessionID |
| 10 | 4 | seq |
| 14 | 1 | statusCode |
| 15 | 2 | payloadLen |
| 17 | 1 | suffixLen |
| 18 | 14 | unused |

Upstream bounds the payload an open-session segment may piggyback
(`MaxSessionOpenPayload`); its exact value is unverified here, so this crate does
NOT hard-reject an inbound session `payloadLen` (a `u16` is inherently bounded) —
it only ever writes `payloadLen = 0` on open. Pin the real bound once a live
server vector is available.

## 3. Segment wire frame (`docs/protocol.md`, `pkg/protocol/segment.go`)

```
[ padding 0 ][ nonce ][ enc metadata ][ tag ][ padding 1 ][ enc payload ][ tag ][ padding 2 ]
     pad0      0 or 24       32          16       pad1        fragment       16      pad2
```
- `nonce` present **only on the first segment of each direction** on TCP; omitted
  (length 0) afterwards — both ends keep the running incremented nonce.
- Two AEAD ops per segment: (1) encrypt the 32-byte metadata → 32 + 16 tag;
  (2) if `payloadLen > 0`, encrypt the payload → payloadLen + 16 tag. Nonce
  increments between ops and between segments.
- `padding 1` length = `prefixLen`, `padding 2` length = `suffixLen` (from
  metadata). `padding 0`/`1`/`2` are random, **unencrypted** entropy/anti-DPI
  filler (`newPadding`, bounded by `maxPaddingSize`); the receiver skips them
  using the decrypted metadata lengths. `maxPDU = 32 * 1024`.

## 4. Inner payload encapsulation (relayed data)

Relayed application bytes are wrapped before encryption as:
`[ marker1 = 0x00 ][ data length = X (u16 BE) ][ data (X bytes) ][ marker2 = 0xff ]`,
then this blob is the segment payload that gets AEAD-encrypted.

## 5. Session establishment (TCP, `pkg/protocol/session.go`)

1. Client, before its first write, sends **one** `openSessionRequest` segment
   (`sessionID` = random non-zero u32, `seq` = `nextSend++`). It MAY carry the
   first ≤ `MaxSessionOpenPayload` bytes of payload.
2. Server replies `openSessionResponse` (same `sessionID`); session → established.
3. Data flows as `dataClientToServer` / `dataServerToClient` segments whose
   (encapsulated) payload is a **plain end-to-end SOCKS5 stream**: the mieru
   server runs a SOCKS5 server inside the tunnel, so the RIPDPI client performs a
   SOCKS5 client handshake (`05 01 00`, expect `05 00`) then `CONNECT`
   (`05 01 00 ATYP ADDR PORT`, expect `05 00 ...`) over the session to reach
   `target`, after which the session is a raw bidirectional byte pipe.
4. Close: `closeSessionRequest` / `closeSessionResponse`.

## 6. RIPDPI mapping

- The engine runs over any `AsyncRead + AsyncWrite` transport; the **relay layer
  owns the protected dial** (`VpnService.protect()` invariant) and hands the
  engine an already-protected `TcpStream`. The engine never opens a raw socket
  itself (keeps `#![forbid(unsafe_code)]` and the protect invariant intact).
- **Replay clock = network time, not the device clock.** The handshake key
  (`timeSalt`) and segment timestamps come from a shared
  `ripdpi_network_time::NetworkTimeProvider`, never a direct `SystemTime::now()`.
  The engine calibrates that provider once per session from the server's first
  AEAD-verified segment timestamp (`metadata[2..6]`, minute granularity, so the
  estimate is `minutes*60 + 30`). Residual risk: before any calibration the first
  handshake derives its key from the device wall clock; Mieru's ±1-window probing
  (~3 min) tolerates a moderately-wrong clock, and once any session calibrates the
  shared provider (Mieru *or* Shadowsocks SIP022), subsequent handshakes use
  network time. No SNTP is used (offline/no-backend rule).

## 7. Multiplexing (`low` / `middle` / `high`)

Many logical sub-sessions share **one** carrier connection. The AEAD nonce is per
carrier *direction* (§3), so multiplexing must reuse the single per-direction
cipher context, not create one per stream:

- **One `Encryptor`** behind an async mutex: every sub-session's segments are
  sealed through one serialized writer, so the per-direction nonce is used exactly
  once no matter how many streams write concurrently (nonce-reuse-safe under
  reuse).
- **One `Decryptor`** in a single reader task that routes each inbound segment to
  the owning sub-session's mailbox by `sessionID` (`metadata[6..10]`). A
  sub-session reads only its own mailbox, so streams never cross-contaminate.
- Each `open_stream` allocates a fresh non-zero `sessionID`, performs its own
  `openSessionRequest` + in-tunnel SOCKS5 `CONNECT`, and runs as an independent
  byte pipe demultiplexed off the shared carrier. Half-closing the write side
  sends `closeSessionRequest` but keeps the mailbox until inbound completes.

**Level → concurrency.** Upstream `enfein/mieru` scales the *number of carrier
connections* with the level; RIPDPI's relay pool caches one carrier per backend,
so the level maps instead to a **per-carrier concurrent-stream ceiling**
(`off`→1, `low`→8, `middle`→32, `high`→128); beyond it, `open_stream` applies
backpressure. The wire multiplexing (`sessionID`-tagged sub-sessions over one AEAD
direction) is faithful to upstream; the ceilings are RIPDPI policy. `off` keeps
the legacy one-stream-per-carrier path (non-reusable).

## 8. Verification tier

- **Primitive vectors** (`cipher.rs`, `metadata.rs`, `segment.rs`): deterministic
  unit vectors pin the byte-exact derivation/framing.
- **Self-consistency loopback** (`loopback.rs`): the real client against a
  spec-faithful in-crate server — open handshake, in-tunnel SOCKS5, a 1 MiB data
  round-trip, and network-time calibration from server segments.
- **Multiplexing loopback** (`mux.rs`): concurrent sub-sessions with strict
  per-stream isolation (no cross-contamination) and sequential carrier reuse; the
  single-`Decryptor` server proves nonce safety under reuse (a reused nonce would
  fail the server's decrypt).
- **NOT verified: on-wire interop with a real upstream `mita` server.** That
  requires a live server and is infeasible offline; no live-interop claim is made.
  Standing up a containerized `mita` fixture in CI is the path to close this and
  remains future work.
