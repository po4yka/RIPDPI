---
title: Add SSH outbound client crate and profile editor
type: task
status: review
area: outbound
priority: medium
owner: unassigned
parent: epic-extended-outbound-protocol-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-31
---

## Summary

Add a `ripdpi-ssh` Rust crate that opens direct-tcpip forwarding via SSH (password or private-key auth), plus a `SshProfileScreen` editor.

## Context

SSH tunnels are a common hobbyist bypass primitive, especially for users who control their own VPS. Use `russh` (or equivalent maintained crate) rather than re-implementing the wire protocol. Multiplexing is optional for v1; single-channel per connection is acceptable, though connection pooling should be left as an extension point.

## Acceptance criteria

- [ ] `ripdpi-ssh` crate compiles with a maintained SSH crate dependency (evaluate `russh`, `thrussh` successors).
- [ ] Password and OpenSSH private-key auth both supported.
- [ ] Host-key verification is on by default; "trust on first use" is a per-profile opt-in.
- [ ] `direct-tcpip` forwarding to arbitrary target host:port works for TCP; UDP is out of scope for v1.
- [ ] `SshProfileScreen` validates host, port, user, and auth selection. Private key is stored via `EncryptedFile`; never SharedPreferences.
- [ ] Host key fingerprint is surfaced on first connect with explicit accept / reject action.
- [ ] Passphrase and private-key material are redacted in all diagnostic surfaces.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/fmt/ssh/SSHBean.java` — bean fields: `authType` (password/privateKey), `username`, `password`, `privateKey`, `privateKeyPassphrase`, `publicKey` (host key fingerprint).
- `app/src/main/java/io/nekohasekai/sagernet/ui/profile/SSHSettingsActivity.kt` — editor layout including the trust-on-first-use host-key flow.
- No `ssh://` URI codec in reference implementation (SSH profiles are editor-only); RIPDPI follows the same pattern.

**Outbound engine (NOT from reference implementation):** use [`russh`](https://github.com/Eugeny/russh) (maintained pure-Rust SSH client). Reference implementation's SSH outbound is sing-box's Go implementation.

**Adapt:** Bean fields, host-key-TOFU UX pattern, passphrase reveal via biometric gate (same pattern RIPDPI uses for WireGuard private keys). **Skip:** No URI codec (consistent with reference implementation); subscription import for SSH is editor-only.

## Links

- [[Epic - Extended outbound protocol support]]
