---
title: Add base64 and plain URI-list subscription parser
type: task
status: done
area: outbound
priority: critical
owner: unassigned
parent: epic-subscription-profile-import
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [x] #task Add base64 and plain URI-list subscription parser #repo/RIPDPI #area/outbound #status/done 🔺

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-base64-and-plain-uri-list-subscription-parser`
- **Verify:** `just test-module core:data:runtime-state`
- **Scope (only modify these + this file + the ledger):** `core/data/runtime-state/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Parse subscription payloads that are either base64-encoded newline-delimited
proxy URIs, or already-decoded plain URI lists.

## Context

This is the fallback path when the payload is not YAML, not JSON, and not
INI. reference implementation attempts base64 URL-safe decode first, then plain text. Per-
URI parsing uses the same standard URI codec that profile share links use,
so this task coexists with per-protocol URI codecs.

## Acceptance criteria

- [ ] Attempt URL-safe base64 decode; on failure, fall through to plain
    text parsing.
- [ ] Split by any of `
`, `
`, `
`; trim whitespace per line; skip
    empty lines and comment lines starting with `#`.
- [ ] Per-URI parse via the shared codec; unknown schemes emit a typed
    warning and skip that line.
- [ ] Parser is streaming line-by-line.
- [ ] Unit tests cover: pure base64, plain text, mixed (some base64-decoded
    lines accidentally re-encoded), invalid URIs, whitespace-only lines.

## Source references

**Reference implementation notes:**

- `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — method `parseProxies()`. Tries URL-safe base64 decode first (`Base64.decode(text, URL_SAFE)`); on failure falls through to plain-text line split.
- `app/src/main/java/io/nekohasekai/sagernet/ktx/Network.kt` — `decodeBase64UrlSafe()` helper with padding-tolerant fallback.
- `app/src/main/java/io/nekohasekai/sagernet/fmt/KryoConverters.kt` and the per-protocol `*Fmt.kt` files (`ShadowsocksFmt.kt`, `TrojanFmt.kt`, `HysteriaFmt.kt`, `TuicFmt.kt`, `V2RayFmt.kt`, etc.) — each has a `parseXxx(url: String)` function that is the per-URI-scheme codec. **These are the single most important set of files to port.**

**Adapt:** The base64-then-fallback detection, per-line trimming, comment-line skip. **Skip:** Reference implementation's Kryo serialization round-trip — the URI codec should go directly to the Protobuf profile bean.

## Links

- [[Epic - Subscription and profile import]]
- [[Add share-sheet handler for proxy URI schemes]]
