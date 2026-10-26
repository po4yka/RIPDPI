## Context

The upstream reference is amneziawg-go v3.1.20260828 (`device/send.go`, `receive.go`, `uapi.go`, `timers.go`). RIPDPI uses its own Rust codec on boringtun 0.7.1, which owns the Noise timers.

## Decisions

- Header protection uses IETF ChaCha20 (counter 0) with the first 12 datagram bytes as nonce. It masks whole handshake and cookie messages after the MACs are final, and only the 16-byte header of transport messages.
- Content padding goes inside the ciphertext and is capped by a UDP window that grows only from authenticated transport packets.
- boringtun keeps its fixed `RekeyTimeout`, `RejectAfterTime`, `KeepaliveTimeout` and `MaxHandshakeAttempts`. These keys are validated and carried but do not change behaviour yet. `RekeyAfterTime` can only rekey earlier, with a handshake rate limit.
- Unknown `.conf` keys fail closed with the key name. Keys that earlier builds ignored (wg-quick host keys, Android app lists, AWG 1.5 `J1`-`J3`/`Itime`) stay ignored.
- The native and Kotlin S3/S4 floor is removed. The arm64 emulator interop matrix and the cross-repo policy record the evidence; a physical device run has not been done.
- The tunnel UDP socket asks for 7 MiB send and receive buffers, as wireguard-go does. With the kernel default, bulk-download bursts overflowed the receive buffer and the lost runs stalled TCP inside the tunnel.

## Risks / Trade-offs

- The emulator does not reproduce amneziawg-go#110 on physical arm64 hardware; that risk stays until a device run.
- The padded keepalives of an upstream peer pass through boringtun's error path; they are classified so that they do not warn.

## Verification plan

Locked `ripdpi-warp-core` tests and Clippy, the core and service JVM suites, the app gate, architecture health and native budgets, plus the live interop run against the upstream Go peer. The floor lift rests on the arm64 emulator matrix; a physical device run is still required.
