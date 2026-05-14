---
title: Epic - QR code and clipboard profile import
type: epic
status: done
area: ui
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [x] #task Epic - QR code and clipboard profile import #repo/RIPDPI #area/ui #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `epic-qr-code-and-clipboard-profile-import`
- **Verify:** `all child rows in GOAL_LEDGER.md are DONE or BLOCKED`
- **Scope (only modify these + this file + the ledger):** _epic — coordination only; child tasks carry the file scope_
- **Blocked-by (must be DONE in the ledger first):** `epic-nekobox-subscription-and-profile-import`
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Goal

Make single-profile import frictionless. Users should be able to scan a QR
code, paste a link from the clipboard, or tap a share-sheet entry and land on
a populated profile-edit screen.

## Why now

Every real-world bypass community distributes individual nodes as
`vless://…` / `hy2://…` / `tuic://…` share links, often inside a QR image.
Without these paths, onboarding requires typing server addresses by hand.
This is the second-largest onboarding gap after subscription import.

## Key decisions

- **Use CameraX + ML Kit barcode scanner,** not zxing, to keep dependency
count low and match the existing androidx posture.
- **Share the URI codec with the subscription epic,** not a duplicate parser.
- **Clipboard watcher is opt-in,** not default, to avoid violating the
minimum-permission stance. Triggered by a notification action when the
app is foregrounded, not on paste-in-other-apps.
- **QR output (for sharing) is generated offline,** no network round-trip.

## Scope

- **In scope:** camera-based QR scan, image-file QR decode, paste-from-
clipboard flow, share-sheet target for `ripdpi://` and common proxy URI
schemes, QR generation for exporting a single profile.
- **Out of scope:** batch QR scanning (a subscription-in-QR is unusual and
covered by the URL import path), QR-code deep linking to Google Play (no
distribution coupling), OCR of non-QR images.

## Ship definition

- [ ] User can scan a QR containing `vless://`, `vmess://`, `trojan://`,
    `ss://`, `hysteria2://`, `tuic://`, `anytls://`, or `ripdpi://` and
    land on a populated profile-edit screen.
- [ ] User can decode a QR from an image picked via SAF.
- [ ] User can paste a proxy URI from the clipboard via an explicit "Import
    from clipboard" menu; clipboard is never read silently.
- [ ] User can generate a shareable QR code and standard URI from any saved
    profile, with secrets redaction warning shown once.
- [ ] Camera permission flow degrades gracefully to image-file path when
    denied.
- [ ] Profile-URI export is intercepted by the system share sheet.

## Child tasks

- [[Add QR scanner screen with CameraX and ML Kit]]
- [[Add QR generation and share for saved profiles]]
- [[Add clipboard-import menu action with explicit user consent]]
- [[Add share-sheet handler for proxy URI schemes]]

## Dependencies

- Depends on: [[Epic - NekoBox subscription and profile import]] (shared URI
codec must exist first).

## Risks / open questions

- Camera permission rejection rate is high; make sure the image-file path
feels first-class, not a fallback.
- ML Kit pulls a modelled barcode scanner; verify final APK size impact
against the "no Play Services" posture and consider the on-device unbundled
model variant.
- Share-sheet interception can conflict with the browser; register a low-
priority filter and only claim specific proxy schemes.

## Links

- [[ripdpi-android]]
- [[Epic - NekoBox subscription and profile import]]
- Child issues: 4
