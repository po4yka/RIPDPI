# Mieru wire protocol — RIPDPI implementation contract

Byte-exact notes for the `ripdpi-mieru` engine, transcribed from upstream
[enfein/mieru](https://github.com/enfein/mieru/tree/155ebbd60f86e472586a60d7ffe58ec8f8682cb1)
v3.36.0, commit `155ebbd60f86e472586a60d7ffe58ec8f8682cb1`:
`docs/protocol.md`, `pkg/cipher/api.go`, `pkg/protocol/{metadata,segment,session,padding}.go`.

> **Scope of this crate.** TCP carrier only (
> upstream `MaxFragmentSize` returns `maxPDU` for the stream transport — no
> KCP/ARQ fragmentation on TCP). The UDP carrier (KCP-like reliable ARQ) is
> **not implemented** and returns `MieruError::UdpUnsupported` before DNS or dialing.
> UDP carrier support remains an open epic requirement.
>
> **Verification status.** The cipher/codec primitives are covered by
> deterministic unit vectors and in-crate peers. The pinned upstream Go server
> also exchanges TCP payloads with the production Rust client on loopback,
> including concurrent streams and runtime-owned carrier shutdown. This proves
> host interoperability, not Android VPN routing or external deployment.

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
  Each AEAD operation **increments the nonce by 1** (big-endian counter over
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
| 22 | 2 | payloadLen (raw stream payload, excl. auth tag) |
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

Upstream bounds the payload an open-session segment may piggyback to
`MaxSessionOpenPayload = 1024` (`pkg/protocol/metadata.go`). The client writes
`payloadLen = 0` on open.

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

## 4. TCP carrier payload

The segment contains raw stream bytes before AEAD encryption. The upstream
UDP-association encapsulation (`00`, length, payload, `ff`) does not apply to
TCP stream data.

## 5. Session establishment (TCP, `pkg/protocol/session.go`)

1. Client, before its first write, sends **one** `openSessionRequest` segment
   (`sessionID` = random non-zero u32, `seq` = `nextSend++`). It MAY carry the
   first ≤ `MaxSessionOpenPayload` bytes of payload.
2. Server replies `openSessionResponse` (same `sessionID`); session → established.
3. Data flows as `dataClientToServer` / `dataServerToClient` segments. The server
   has already authenticated the carrier, so the RIPDPI client sends `CONNECT`
   (`05 01 00 ATYP ADDR PORT`, expect `05 00 ...`) over the session to reach
   `target`, after which the session is a raw bidirectional byte pipe. There is
   no SOCKS method-negotiation greeting inside this authenticated session.
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

- **One `Encryptor`** owned by the bounded writer queue: every sub-session's segments are
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
direction) is faithful to upstream; the ceilings are RIPDPI policy. `off` uses
the same owned carrier implementation with one stream and a non-reusable
relay-pool entry.

**Robustness properties and known limitations (loopback tier; adversarially reviewed):**

- *Handshake is time-bounded.* The open-session + in-tunnel SOCKS5 handshake runs
  under a 10 s timeout, so a wedged or half-broken (writes-accepted, no-response)
  carrier cannot hang `open_stream` forever — important because a multiplexed
  carrier is reused for every stream. On timeout the sub-session is closed and the
  mailbox released. (The non-mux path applies the same bound in `tcp_connect`.)
- *Per-segment freshness.* Every segment's metadata timestamp is stamped with the
  current network time (from the shared `NetworkTimeProvider`), not a value frozen
  at carrier open, so a long-lived carrier's segments stay fresh against a server
  that enforces per-segment timestamps. (The replay key is still derived once at
  handshake, per the per-carrier-direction model.)
- *Stream teardown owns its pumps.* Closing a stream joins its child work;
  dropping it aborts the pumps and unregisters its mailbox. Carrier shutdown
  aborts all children before joining, retains joins across cancellation, and
  rejects new work. Factory shutdown also owns evicted and pending carriers.
- *Slow consumers are isolated.* A full bounded mailbox closes that logical
  stream instead of blocking the shared reader. The shared TCP carrier still
  has transport-level head-of-line blocking.
- *`sessionID` reuse on close (residual).* A `closeSession*` is routed to whatever
  mailbox currently holds that random `u32` id. If an id were re-rolled for a new
  sub-session in the window after the old one was retired, a delayed close could
  truncate the new one (spurious EOF). Accidental probability is ~2⁻³² per open;
  an adversarial server could target it, but a relay server can already truncate
  any stream by dropping bytes, so this grants no new capability. A per-session
  generation tag would close it (the wire has no generation field today).

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
- **Pinned upstream loopback** (`tests/upstream_interop.rs` and
  `scripts/tests/run-outbound-interop.py`): real upstream protocol/SOCKS server,
  64 KiB payloads, and concurrent isolated streams for low/middle/high.
- **Runtime stop** (`ripdpi-relay-core` backend fixture): two SOCKS flows in
  `off` mode exchange payloads, then stop leaves zero accepted upstream carriers
  active while the Rust runtime object and peer process remain alive.
- Android routing/protection, UDP carrier operation, and external-server
  acceptance require separate evidence; these host tests do not establish them.
