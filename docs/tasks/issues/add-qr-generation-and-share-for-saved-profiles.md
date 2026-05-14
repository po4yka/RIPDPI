---
title: Add QR generation and share for saved profiles
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: epic-qr-code-and-clipboard-profile-import
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add QR generation and share for saved profiles #repo/RIPDPI #area/ui #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-qr-generation-and-share-for-saved-profiles`
- **Verify:** `just test-module app`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Let users generate a QR code (and plain URI) from any saved profile, with
an explicit one-time warning that the QR contains secrets.

## Context

Generation is offline; no network round-trip. Use the same URI codec that
the scanner consumes. Warning is dismissible but cannot be permanently
suppressed — secret-sharing risk is high enough that nagging is warranted.

## Acceptance criteria

- [ ] "Share profile" entry in the profile-detail menu emits both a QR
    bitmap and a plain URI string.
- [ ] First invocation shows a non-dismissible-for-5s warning modal that
    credentials are embedded in the output.
- [ ] QR is generated offline via zxing-core (no Play Services dep).
- [ ] Share sheet lets the user choose "Copy URI" or "Share image".
- [ ] Image share uses `FileProvider` at `profile.fileprovider`; file is
    cleaned up after share completion.
- [ ] Clear-text URI is not written to app logs; share intent is logged
    as metadata only.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/ui/QRCodeDialog.kt` — QR bitmap generation via `BarcodeEncoder` from `zxing-lite`. Replace with `zxing-core` directly (lighter) to keep off `zxing-lite`.
- `app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt` — the "share profile" menu entry and its intent-build path: emits both QR bitmap and plain URI via share sheet.
- `app/src/main/java/io/nekohasekai/sagernet/fmt/UniversalFmt.kt` — `toLink()` emits `sn://<type-slug>?<zlib+base64url Kryo>`. RIPDPI should use per-protocol URIs (not `sn://`) since Kryo is not in the RIPDPI stack.

**Adapt:** The two-action share sheet (copy URI / share image), FileProvider cleanup on share completion. **Skip:** `sn://` universal link (invent nothing; always emit the canonical per-protocol scheme like `vless://`, `ss://`, `hy2://`).

## Links

- [[Epic - QR code and clipboard profile import]]
- [[Add QR scanner screen with CameraX and ML Kit]]
