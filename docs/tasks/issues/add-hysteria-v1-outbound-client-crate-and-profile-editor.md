---
title: Add Hysteria v1 outbound client crate and profile editor
type: task
status: backlog
area: outbound
priority: low
owner: unassigned
parent: epic-extended-outbound-protocol-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Add Hysteria v1 outbound client crate and profile editor #repo/RIPDPI #area/outbound #status/backlog 🔽

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-hysteria-v1-outbound-client-crate-and-profile-editor`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-hysteria2`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-hysteria2/**`, `core/data/runtime-state/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add a `ripdpi-hysteria-v1` Rust crate (distinct from the existing `ripdpi-hysteria2`) for legacy Hysteria v1 subscriptions, plus a `HysteriaV1ProfileScreen` editor. Mark the crate with an explicit sunset decision date.

## Context

Hysteria v1 is being replaced by v2 in the upstream ecosystem but remains present in older subscriptions. v1 protocol framing, auth, and congestion control differ enough that forcing them into `ripdpi-hysteria2` would regress that crate's simplicity. Ship as a thin, clearly-deprecated crate rather than hacking v1 into v2.

## Acceptance criteria

- [ ] `ripdpi-hysteria-v1` crate compiles and passes v1 reference test vectors.
- [ ] Crate has a top-of-file comment stating the sunset target (date to be decided during implementation but committed to repo).
- [ ] `HysteriaV1ProfileScreen` prominently marks the profile as legacy and suggests Hysteria2 migration.
- [ ] Subscription import still routes v1 entries to this crate without user intervention.
- [ ] Shutdown joins bounded handler work; no background QUIC sockets leak.
- [ ] Auth token is redacted in all diagnostic surfaces.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/fmt/hysteria/HysteriaBean.java` — shared v1+v2 bean; `protocolVersion` field distinguishes (`1` or `2`). v1-only fields: `protocol` (`udp`/`wechat-video`/`faketcp`), `authPayloadType` (string/base64), `authPayload`, `obfuscation`, `uploadMbps`, `downloadMbps`.
- `app/src/main/java/io/nekohasekai/sagernet/fmt/hysteria/HysteriaFmt.kt` — `hysteria://` URI codec (v1). v2 is `hysteria2://` / `hy2://`.
- `app/src/main/java/io/nekohasekai/sagernet/ui/profile/HysteriaSettingsActivity.kt` — editor handles both versions.

**Outbound engine (NOT from reference implementation):** RIPDPI already ships `ripdpi-hysteria2` (v2). For v1, upstream [`HyNetwork/hysteria`](https://github.com/HyNetwork/hysteria) is Go. Hysteria v1 uses a custom framing over QUIC incompatible with v2; a separate Rust crate is needed. reference implementation launches Hysteria v1 as an external process via `hysteria-plugin`.

**Adapt:** Bean fields (v1 subset), URI codec, bandwidth fields (v1 requires them, v2 derives them). **Skip:** Reference implementation's external-process plugin path. **Sunset:** commit an explicit removal date in the crate header.

## Links

- [[Epic - Extended outbound protocol support]]
