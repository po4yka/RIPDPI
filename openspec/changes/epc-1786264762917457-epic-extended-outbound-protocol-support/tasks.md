# EPC-1786264762917457: Epic - Extended outbound protocol support

## Objective

Epic - Extended outbound protocol support

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [ ] EPC-1786266573979241 Complete upstream or live-server reference coverage for every remaining protocol implementation #epic !high @item:EPC-1786264762917457
- [ ] EPC-1786266573979087 Verify bounded start and stop behavior for every protocol supervisor, including incomplete Mieru modes #epic !high @item:EPC-1786264762917457
- [x] EPC-1786264762918286 Each protocol has a profile-edit screen with schema-backed validation. (SshProfileScreen.kt, MieruProfileScreen.kt, AnyTlsProfileScreen.kt under app/src/main/kotlin/com/poyka/ripdpi/ui/screens/.) #epic !high @item:EPC-1786264762917457
- [x] EPC-1786264762918536 Each protocol can be parsed from its standard URI scheme into a valid RIPDPI profile and round-tripped back to URI. (anytls:// + mieru:// pre-existing; ssh:// added 2026-06-11 — first-class ProxyProfile.Ssh + parseSsh/encodeSsh round-trip… #epic !high @item:EPC-1786264762917457
- [x] EPC-1786264762918979 Strategy-pack metadata includes per-protocol compatibility hints (e.g. Trojan inside xHTTP, SSH direct vs SSH-over-TLS). (StrategyPackProtocolHint + bundled catalog.json ssh/mieru/anytls entries, load-bearing via StrategyPackSnapshot.proto… #epic !high @item:EPC-1786264762917457
- [x] EPC-1786264762918523 Secrets (passwords, UUIDs, private keys) are redacted in logs, diagnostics, and crash reports, not only at export time. (SSH + Mieru redact in Debug (pre-existing); AnyTLS closed 2026-06-11 — Rust Debug for AnyTlsClientConfig masks passwor… #epic !high @item:EPC-1786264762917457

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
