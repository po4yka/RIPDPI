---
id: AND-1794728744166445
title: Correct profile and QR exchange audit findings
kind: bug
status: doing
area: android
priority: high
owner: Profile exchange maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: imp05-profile-exchange-corrections
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Correct the remaining defects from the IMP-05 profile exchange audit.

## Acceptance criteria

- Each confirmed import gets a separate saved profile. The active selection changes only through an explicit activation action.
- Supported links keep their connection settings. Unsupported representations and invalid input give a safe error.
- QR output has a four-module quiet zone. Import errors and clipboard previews protect credentials.
- Focused regression tests cover each corrected path.

## File ownership

- Confirm worker owns profile confirmation, relay save integration, and settings strings for confirmation.
- Intake worker owns inbound intents, the common classifier, AWG configuration intake, and QR series assembly.
- Export worker owns the saved-profile mapper, share snapshot reads, and sensitive clipboard writes.
- Integration owner owns URI query validation, QR encoding and image UI, strict file decoding, clipboard import errors, and the combined translation-key export. Merge locale changes before regenerating the translation-key file.
