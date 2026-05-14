---
title: Add QR scanner screen with CameraX and ML Kit
type: task
status: done
area: ui
priority: high
owner: unassigned
parent: epic-qr-code-and-clipboard-profile-import
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [x] #task Add QR scanner screen with CameraX and ML Kit #repo/RIPDPI #area/ui #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-qr-scanner-screen-with-camerax-and-ml-kit`
- **Verify:** `just test-module app`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add a Compose scanner screen that reads a QR containing a proxy URI
(`vless://`, `vmess://`, `trojan://`, `ss://`, `hysteria2://`, `tuic://`,
`anytls://`, `ripdpi://`) and routes to the profile-edit screen with
populated fields.

## Context

Shared URI codec lives in the subscription epic; this task is strictly the
UI and camera plumbing. Denied camera permission must not brick the flow —
offer an "import from image" fallback using SAF.

## Acceptance criteria

- [ ] `ScannerScreen` composable with CameraX preview + ML Kit barcode
    scanner configured for QR only.
- [ ] On decode, validate scheme against the allowlist and dispatch to
    profile-edit via Compose Navigation.
- [ ] Camera permission rationale rendered inline, not a modal.
- [ ] Fallback "pick image" via `ActivityResultContracts.OpenDocument`
    decodes QR from a still image.
- [ ] Invalid QR content shows a redacted error (first 16 chars only);
    never log the full payload.
- [ ] RTL-safe layout; Roborazzi screenshot tests for en / ar / fa / zh-CN.

## Source references

**Reference implementation notes:**

- `app/src/main/java/io/nekohasekai/sagernet/ui/ScannerActivity.kt` — entire flow: camera permission gate, `CaptureManager` lifecycle, decoded-text dispatch. Port the flow, not the library (reference implementation uses `zxing-lite`; RIPDPI should use CameraX + ML Kit barcode scanner for smaller APK and no camera-permission hang on vendor ROMs).
- `app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt` — the "scan result received" callback path: `onScanResult(text)` validates, dispatches to per-protocol URI codec, falls back to `UniversalFmt.parseLink` for `sn://` scheme.

**amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — the image-file QR decode path is cleaner than Reference implementation's:

- `ui/src/main/java/org/amnezia/awg/util/QrCodeFromFileScanner.kt` — decodes a QR from a picked image URI via `QRCodeReader` (no camera dependency). **Port this pattern** for the SAF-file-picker fallback path.

**Adapt:** Permission-gate UX, image-file fallback, decoded-text dispatch. **Skip:** zxing-lite dependency (use ML Kit unbundled-model variant to stay Play-Services-free).

## Links

- [[Epic - QR code and clipboard profile import]]
