# ripdpi-packets

**Responsibility:** packet parsing, protocol classification, marker extraction, and fake-payload profile data shared by runtime, diagnostics, and desync crates.

This crate exposes TLS/HTTP/QUIC helpers, `ProtocolClassifier` plumbing, extracted protocol fields, entropy utilities, and fake packet payload profiles. Desync code depends on its marker offsets for SNI, ECH extension, host, payload, and record-boundary decisions.

## Boundaries

- No Android/JNI ownership.
- No network I/O.
- Packet mutation helpers must preserve parser invariants that are pinned by crate tests and golden packet seeds.

Run focused packet checks with `cargo test -p ripdpi-packets`.
