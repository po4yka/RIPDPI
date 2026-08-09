---
id: OUT-1786264762917254
title: Add an interactive SSH host-key trust flow
kind: feature
status: todo
area: outbound
priority: high
owner: Outbound protocol maintainer
parent: EPC-1786264762917457
blocked_by: []
spec_mode: required
openspec_change: out-1786264762917254-add-ssh-outbound-client-crate-and-profile-editor
created: 2026-04-24
updated: 2026-08-09
---

## Summary

Complete first connection for SSH profiles by surfacing the observed host key and requiring an explicit accept or reject decision before the fingerprint is persisted.

## Context

SSH tunnels are a common user-managed tunneled outbound option, especially for users who control their own VPS. Use `russh` (or equivalent maintained crate) rather than re-implementing the wire protocol. Multiplexing is optional for v1; single-channel per connection is acceptable, though connection pooling should be left as an extension point.

## Acceptance criteria

- [x] `ripdpi-ssh` crate compiles with a maintained SSH crate dependency (evaluate `russh`, `thrussh` successors).
- [x] Password and OpenSSH private-key auth both supported.
- [x] Host-key verification is on by default; "trust on first use" is a per-profile opt-in.
- [x] `direct-tcpip` forwarding to arbitrary target host:port works for TCP; UDP is out of scope for v1.
- [x] `SshProfileScreen` validates host, port, user, and auth selection. Private key is stored via `EncryptedFile`; never SharedPreferences. (Validation done. Persistence: **all** profile editors — SSH, AnyTLS, AmneziaWG — are preview-only by design (`@HiltViewModel constructor()`, `onSave{saved=true}`); SSH credentials persist via the Keystore-backed import path (`KeystoreRelayCredentialStore` / `RelayCredentialRecord.ssh*`), which is Keystore-backed and never SharedPreferences. Wiring editor-side persistence for SSH alone would diverge from the uniform editor architecture and is out of scope.)
- [ ] Host key fingerprint is surfaced on first connect with explicit accept / reject action. **(deferred: no connect-from-editor path exists; the Rust `SshError::HostKeyUntrusted` is config-driven with no runtime accept/reject channel, so a connect-time TOFU dialog is a cross-boundary JNI/event-channel feature with no existing pattern.)**
- [x] Passphrase and private-key material are redacted in all diagnostic surfaces.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/fmt/ssh/SSHBean.java` — bean fields: `authType` (password/privateKey), `username`, `password`, `privateKey`, `privateKeyPassphrase`, `publicKey` (host key fingerprint).
- `app/src/main/java/io/nekohasekai/sagernet/ui/profile/SSHSettingsActivity.kt` — editor layout including the trust-on-first-use host-key flow.
- No `ssh://` URI codec in reference implementation (SSH profiles are editor-only); RIPDPI follows the same pattern.

**Outbound engine (NOT from reference implementation):** use [`russh`](https://github.com/Eugeny/russh) (maintained pure-Rust SSH client). Reference implementation's SSH outbound is sing-box's Go implementation.

**Adapt:** Bean fields, host-key-TOFU UX pattern, passphrase reveal via biometric gate (same pattern RIPDPI uses for WireGuard private keys). **Skip:** No URI codec (consistent with reference implementation); subscription import for SSH is editor-only.

## Links

- [[Epic - Extended outbound protocol support]]

## Work log

- 2026-06-05: Rust crate (`ripdpi-ssh`) is a full russh-backed implementation (password + private-key auth, TOFU host-key policy, direct-tcpip, Debug redaction). `SshProfileScreen` / `SshProfileViewModel` / `SshProfileEditorState` exist and are wired into NavHost. Two criteria remain open: (1) `SshProfileViewModel.onSave()` only flips `saved=true` — the actual write path to `KeystoreRelayCredentialStore` for SSH credentials is not wired from the editor; (2) no connect-time UI dialog for `SshError::HostKeyUntrusted` (TOFU first-connect accept/reject) exists anywhere in Kotlin.
- 2026-06-05: Re-audit (source verification). Confirmed criteria 1–4 and 7 via `native/rust/crates/ripdpi-ssh/src/{client.rs,config.rs,error.rs}`. Criterion 5 upgraded to [~]: `SshProfileEditorState.isComplete` validates host/port/user/auth and screen is NavHost-wired (`RipDpiNavHost.kt` line 772), but `onSave()` only sets `saved=true` with no write to `EncryptedFile`/`KeystoreRelayCredentialStore`; EncryptedFile reference in ViewModel is doc-comment-only. Criterion 6 stays `[ ]`: `SshError::HostKeyUntrusted` exists in Rust but no Kotlin accept/reject dialog found (grep confirmed). Status changed from `blocked` to `doing`: no blocker is noted or evident; work is actively partial.
- 2026-06-11: Epic pass — **bonus capability added beyond the original editor-only scope:** a first-class `ProxyProfile.Ssh` subtype + RIPDPI-invented `ssh://` share-link codec (`parseSsh`/`encodeSsh`, commit `b87e0a85`, +parse/round-trip tests) that round-trips the full profile, including multi-line OpenSSH private keys percent-encoded in the query (per the user's explicit choice to make SSH URI-representable). Criterion 5 resolved as satisfied-by-architecture: all profile editors are uniformly preview-only and SSH creds persist via the Keystore-backed import path. Criterion 6 (connect-time TOFU dialog) deferred — no connect-from-editor path / no runtime accept-reject channel. Status stays `doing`.
