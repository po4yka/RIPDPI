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

1. **Header protection.** When a valid nonzero key is set, the sender makes an IETF ChaCha20 stream
   (12-byte nonce, block counter 0) with the key and the first 12 bytes of the `S` prefix as the
   nonce (`noise-protocol.go:647-656`). It XORs the stream into the message after the MACs are
   final:
   - Initiation, response, and cookie reply: all message bytes (148, 92, or 64)
     (`send.go:149-171`, `send.go:199-228`, `send.go:258-276`).
   - Transport: only the 16-byte header (type, receiver, counter) (`send.go:632-640`).
     The ciphertext is not changed.
2. **Prefix size rule.** When a valid nonzero key is set, S1, S2, S3, and S4 must each be at least 12
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

## Reference receive classification

The receiver drops datagrams shorter than 32 bytes (`receive.go:137`). With header protection on,
it uses the first 12 datagram bytes as the nonce and gets a 4-byte type mask from the first
keystream bytes (`receive.go:144-154`). It then tests the types in this order: initiation,
response, cookie reply, transport (`receive.go:605-659`). A handshake or cookie type matches
when the size is `S + size`, or larger when `RandomTrailers` is on. A transport type matches when
the size is at least `S4 + 32`. The unmasked type must be in the H range of that type. The
receiver then removes the trailer and unmasks the rest of the protected bytes with the same
keystream (`receive.go:159-245`).

## Reference behavior outside the datagram

- `DisableCookies = on` stops the responder under-load path. The responder does not check MAC2
  and does not send cookie replies (`receive.go:372-387`). RIPDPI admission also handles
  received handshake responses, as described below.
- Timer ranges replace the WireGuard constants: retransmit uses `RekeyTimeout`; send rekey uses
  `RekeyAfterTime`; key expiry uses the high bound of `RejectAfterTime`; passive keepalive uses
  `KeepaliveTimeout`; the retry limit uses `MaxHandshakeAttempts` (`timers.go:228-318`,
  `send.go:286-295`).
- The persistent keepalive timer draws a new interval from its range after each authenticated
  packet (`timers.go:198-203`).
- Junk packets have a length in `[Jmin, Jmax)` in this version (`noise-protocol.go:632-645`).

## RIPDPI behavior and deliberate limits

RIPDPI uses `awg-3.1-ranges-v1` as its bounded input profile. It does not claim full
Go parser equivalence. The source owners are `awg_profile.rs`, `awg_v3.rs`,
`amneziawg.rs`, the `wireguard` module, and the patched backend under
`native/rust/vendor/boringtun`. Kotlin validates the same profile before activation.

- All five timer fields reach the opt-in boringtun AWG policy. The backend owns
  session age, initiator/responder roles, retries, attempt exhaustion, idle keepalive,
  and expiry at the high bound of `RejectAfterTime`. Persistent keepalive uses a new
  sampled interval after authenticated traffic. Standard WireGuard keeps its default policy.
- Every outgoing transport path applies padding before AEAD: payload, queued data,
  explicit empty transport, automatic response, and timer keepalive. Nonzero CPA
  takes precedence over RT; with both off, plaintext aligns to 16 bytes within MTU.
- The backend reports authenticated payload, padded all-zero keepalive, malformed
  plaintext, and rejected authentication separately. Malformed authenticated transport
  consumes its replay counter but does not mark readiness, deliver data, count as a
  keepalive, or grow the UDP window. IPv4 header and declared-length checks also apply
  to the standard WireGuard path.
- Receive classification tries at most four layouts: transport, initiation, response,
  then cookie. Each candidate must authenticate. A cookie challenge is provisional
  while later candidates are tried. An accepted candidate commits codec context. If none
  accepts, only the selected provisional challenge commits its cookie context.
  This differs from the reference's first matching layout and handles ambiguous H/S layouts.
- The window starts at 500. Local outgoing plaintext size and accepted incoming transport
  can grow it. Receive learning
  subtracts the 16-byte AEAD tag from the datagram length; the bound is effective MTU
  plus S4 plus 32. Rekey keeps the window; a fresh tunnel/endpoint codec resets it.
  RIPDPI uses a configured fixed endpoint and rejects foreign UDP sources before decoding.
- `DisableCookies` bypasses only under-load MAC2 admission. Both flag states retain
  MAC1 and Noise authentication, plus independent limits of ten expensive handshake
  attempts and ten cookie challenges per second per peer. Cookies bind to the actual
  UDP address/port or a private WebSocket connection identity. Incoming cookies do
  not by themselves establish readiness.

## Portable input subset

| Input | RIPDPI contract |
| --- | --- |
| Six v3 range fields | Canonical ASCII decimal `lo` or `lo-hi`, `0 <= lo <= hi <= 65535`. No sign, leading zeros, internal spaces, extra separators, or overflow. Empty means unset. `0` and `0-0` disable CPA or select timer defaults. Other zero-low ranges are valid. |
| PersistentKeepalive | `0`/`0-0` means off. Positive scalar, equal, and ordered ranges through 65535 are valid. `0-N` with positive N is rejected. Absent and `.conf off` remain off. |
| H1-H4 | Canonical unsigned 32-bit values/ranges, including 4294967295. Ranges must be disjoint. Each is above 4 or is its own standard singleton (H1=1 through H4=4). |
| Jc | Integer 0 through 128. |
| Jmin, Jmax, S1-S4 | Integers 0 through 1280. Jmin must not exceed Jmax, even with Jc=0. Initiation and response totals must differ: `148+S1 != 92+S2`. HP requires every S to be at least 12. |
| Junk size | Inclusive `[Jmin, Jmax]`, including equal bounds. This differs from the pinned Go exclusive upper bound. No exact uniform-distribution claim is made for junk. |
| HP key | Empty means off; otherwise a Base64 value that decodes to 32 bytes. All-zero key means off. The editor masks it; credential storage keeps it outside Room profile JSON. |
| Flags | `.conf` accepts on/off, true/false, and ASCII decimal integers (zero=false). The RIPDPI URI accepts only on/off. |

The scalar J/S parser is not the canonical range-text parser. Do not infer the
range text restrictions for those scalar fields. Known ignored `.conf` interface
keys remain separate from unknown keys, which fail with typed errors without values.

CPS supports these tags in I1-I5:

- `<b 0x...>`: nonempty lowercase hex, with an even digit count.
- `<r N>`: random bytes; `<rd N>`: ASCII digits; `<rc N>`: ASCII letters.
  N is canonical decimal from 1 through 1280.
- `<t>`: four-byte Unix timestamp in network byte order.

A program has at most 64 tags, 4096 ASCII text bytes, and 1280 output bytes.
Tags are adjacent with no intervening whitespace. Empty I fields mean no packet.
`d`, `ds`, and `dz` are unsupported. Malformed counts, integer overflow, excess
output, and unsupported tags fail before packet generation. Each send generates
fresh dynamic values. CPS and junk packets do not receive AWG message trailers.

## Acceptance scope

RIPDPI applies no runtime Android arm64 S3/S4 floor. The preserved emulator result
at `ba228c17c7cfb1f90a09f6af03a1c5837e1a100c` is historical evidence for the earlier
implementation, not acceptance of these changes. See
[the preserved observation](../../core/data/src/test/resources/awg-arm64-emulator-observation.json).
The shared `contract/amneziawg-arm64-version-floor.json` deployment guard remains
unchanged. Fresh host peers, current-source emulator runs, physical arm64 production
service tests, exact artifacts, and hosted CI are separate required evidence in
`openspec/changes/awg31-behavior-parity/verification.md`. Local unit tests do not
prove those acceptance categories or production DPI behavior.

## Pinned host peer runners

`build-gate --lane ripdpi -- python3 scripts/tests/run-standalone-awg-interop.py`
checks both Go revisions, seven named profiles, MTU 1280/1420, and four sessions
per case. `--peer` and `--profile` select a smaller diagnostic run; its receipt
lists the selected cases. Use `--candidate-patch` only for local development; new
files must be staged so the archived patch includes them. JSON and logs are kept
under `build/reports/awg-standalone/`. The fixture performs one empty TUN read after
IpcSet so the pinned Go reader reloads S4 before its first actual payload. The
upstream-control test proves the old offset; this does not patch upstream.

Linux acceptance uses `scripts/tests/run-linux-awg-interop.py` inside a new VM
created by `test-lab/acceptance/vm/lab_vm.py`. It refuses root execution, a missing
VM receipt, a receipt for a different machine-id/hostname, or a pre-existing AWG
module. Keep the original owner receipt and record the guest hostname plus
SHA-256 of the stripped `/etc/machine-id` as `guest_hostname` and
`guest_machine_id_sha256` in the kernel run's receipt, through the owned VM command.
Do not use a shared host or a container that shares its kernel.

The pinned sources are:

- Kernel `4569c4c67f3a57414969260cafbbd04694fbaae0`, from
  `https://codeload.github.com/amnezia-vpn/amneziawg-linux-kernel-module/tar.gz/4569c4c67f3a57414969260cafbbd04694fbaae0`.
- Tools `ee0f0a9aa34ff0a0da4b3433b9512781cfe02843` (`v3.1.20260812`), from
  `https://codeload.github.com/amnezia-vpn/amneziawg-tools/tar.gz/ee0f0a9aa34ff0a0da4b3433b9512781cfe02843`.

The runner checks each archive digest before extraction. Install matching kernel
headers, build-essential, kmod, iproute2, tcpdump, pkg-config, libssl-dev, and rustup
inside the owned VM. Build the Rust `standalone_awg_interop` test with the repository's
pinned toolchain, `--locked --features awg-interop --no-run --message-format=json`,
and a writable target directory. Build the Go fixture for Linux with the Go version
from its `go.mod`; cross-compilation from the host is supported. Record the source
SHA, any candidate patch digest, and compiler outputs before the run.

Pass `--vm-owner`, `--identity`, `--kernel-archive`, `--tools-archive`, `--test-binary`,
`--fixture`, and a new `--output-dir` to the Linux runner. It builds module/tools
without root, loads only its module, creates one namespace per case, verifies netlink
readback, and exercises the same payload test through ordinary OS echo sockets.
It records module/tools/test/fixture hashes, traffic counters, and per-case PCAP,
then removes namespaces and unloads its module. The fixed public fixture keys are
not production credentials; readback reports exclude keys.

The Linux pin still uses a fixed 180-second age limit in `send.c` and `receive.c`.
Its retry-exhaustion zeroization uses three times `RekeyAfterTime`; session-created
zeroization uses three times `RejectAfterTime`. Treat these as reference-peer
limitations, not RIPDPI requirements. Payload success and configuration readback
do not prove the timer, cookie, trailer, or padding feature assertions assigned to
the separate wire-validation gate.
