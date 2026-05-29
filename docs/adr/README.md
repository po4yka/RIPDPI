# Architecture Decision Records

This directory indexes protocol and architecture decisions that should be treated as settled unless their revisit trigger fires. Living feature docs should link these records instead of restating the full rationale.

| Decision | Status | Date | Operational effect |
| --- | --- | --- | --- |
| [ADR 0001: VLESS REALITY ECH Policy](0001-reality-ech.md) | Accepted | 2026-05-28 | VLESS Reality does not use real ECH. Only GREASE-only ECH parity may be considered by TLS profile policy; the outbound ECH facade remains separate from the Reality transport. |
| [Snowflake Native Rust Port Decision](../architecture/snowflake-native-rust-decision.md) | Approved no-go | 2026-05-27 | Snowflake remains the external Go `ripdpi-snowflake` pluggable-transport binary. There is no native Rust `ripdpi-snowflake` crate under `native/rust/crates/`. |
| [ADR 0002: Tor (Arti) Relay Backend Feasibility](0002-tor-feasibility.md) | Approved go (opt-in, bridge+PT only) | 2026-05-29 | The `arti-client`-backed `RelayKind::Tor` backend stays wired. Arti adds ~1.2 MiB of `.text` (arm64) and is linked unconditionally; the size gate is GO. No direct-bootstrap default, no UDP over Tor, not a default relay. |

## Cross-Link Rules

- Feature docs that mention VLESS Reality and ECH should link [ADR 0001](0001-reality-ech.md) and avoid implying that Reality performs real ECH.
- Feature docs that mention Snowflake should link the [Snowflake native Rust no-go decision](../architecture/snowflake-native-rust-decision.md) and avoid implying that Snowflake is a native Rust relay backend.
- Feature docs that mention the Tor relay backend should link [ADR 0002](0002-tor-feasibility.md) for the recorded size-feasibility gate, and must not imply Tor supports UDP, uses a direct (non-bridge) bootstrap, or is a default relay.
- ADRs are dated decision records. Update living reference docs when implementation changes; create a new ADR or add a small superseding note only when a decision itself changes.
