# Architecture Decision Records

This directory indexes protocol and architecture decisions that should be treated as settled unless their revisit trigger fires. Living feature docs should link these records instead of restating the full rationale.

| Decision | Status | Date | Operational effect |
| --- | --- | --- | --- |
| [ADR 0001: VLESS REALITY ECH Policy](0001-reality-ech.md) | Accepted | 2026-05-28 | VLESS Reality does not use real ECH. Only GREASE-only ECH parity may be considered by TLS profile policy; the outbound ECH facade remains separate from the Reality transport. |
| [Snowflake Native Rust Port Decision](../architecture/snowflake-native-rust-decision.md) | Approved no-go | 2026-05-27 | Snowflake remains the external Go `ripdpi-snowflake` pluggable-transport binary. There is no native Rust `ripdpi-snowflake` crate under `native/rust/crates/`. |
| [ADR 0002: Tor (Arti) Relay Backend Feasibility](0002-tor-feasibility.md) | Approved go (opt-in, bridge+PT only) | 2026-05-29 | The `arti-client`-backed `RelayKind::Tor` backend stays wired. Arti adds ~1.2 MiB of `.text` (arm64) and is linked unconditionally; the size gate is GO. No direct-bootstrap default, no UDP over Tor, not a default relay. |
| [ADR 0003: Native Runtime-Readiness Push](0003-native-readiness-push.md) | Accepted | 2026-05-30 | Readiness is delivered by a one-shot `onRuntimeReady()` JNI callback (clone of the VPN-protect generation-token pattern), not the 50 ms telemetry poll. The poll stays as a fallback; the callback is a strict lifecycle-class one-shot, never reused for higher-frequency events. JNI symbol export + `jni-symbols.baseline` approval and on-device latency are gated to CI/device. |
| [ADR 0004: Protocol Support Policy](0004-protocol-support-policy.md) | Accepted | 2026-06-01 | RIPDPI maintains support only for current/actual protocols. VMess, Trojan-Go, and Hysteria v1 are removed from code and docs (never carried traffic — stubbed wire engines). SSH and Mieru remain backlog (not legacy). Relay schema ceiling `7 → 8`; removed `relay_kind` values are rejected, subscription nodes naming them are skipped. |
| [ADR 0005: Native relay-core crash isolation](0005-native-core-crash-isolation.md) | Accepted (NO-GO) | 2026-06-05 | The Rust relay-core stays **in-process** in the `VpnService`; it is not moved out-of-process like xivpn. The panic-prone surface is the latency-critical smoltcp data path, so isolating it would IPC the ~1 Gbps hot path without improving availability (an LMK-eligible child can be killed asymmetrically; Android 17's memory cap is app-wide). Hardening stays `catch_unwind` sentinels + SIGPIPE handler + `SupervisorExitCause`; only foreign PT binaries run as supervised subprocesses. |

## Cross-Link Rules

- Feature docs that mention VLESS Reality and ECH should link [ADR 0001](0001-reality-ech.md) and avoid implying that Reality performs real ECH.
- Feature docs that mention Snowflake should link the [Snowflake native Rust no-go decision](../architecture/snowflake-native-rust-decision.md) and avoid implying that Snowflake is a native Rust relay backend.
- Feature docs that mention the Tor relay backend should link [ADR 0002](0002-tor-feasibility.md) for the recorded size-feasibility gate, and must not imply Tor supports UDP, uses a direct (non-bridge) bootstrap, or is a default relay.
- Docs that enumerate supported outbound/relay protocols should link [ADR 0004](0004-protocol-support-policy.md) and must not imply RIPDPI supports VMess, Trojan-Go, or Hysteria v1 — they were removed. SSH and Mieru are backlog (not-yet-implemented), not legacy.
- ADRs are dated decision records. Update living reference docs when implementation changes; create a new ADR or add a small superseding note only when a decision itself changes.
