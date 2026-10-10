# Change: Show readable Google Play artwork with real feature screens

Task ID: `UIX-1791637564152260`

## Why

The current posters preserve Android pixels but do not clearly show the advertised functions. Small UI, partial scroll blocks, mixed languages and repeated Home frames weaken the listing.

## What Changes

- Show real current screens for connection, completed diagnostics, relay selection, DNS, strategies and local tools.
- Use readable headings and feature explanations, distinct layouts and a coherent RTL banner.
- Localize visible generic UI labels and use an explicitly pinned Persian font.
- Show larger posters in all README galleries. Record real capture provenance and reject stale outputs.
- No breaking protocol, storage or service contracts change.

## Capabilities

### New Capabilities

- `play-listing`: Accurate and readable localized store artwork and capture provenance.

### Modified Capabilities

- None.

## Impact

- App UI text resources and presentation mapping; marketing capture and renderer; generated PNGs; READMEs.
- No required backend, production dependency, JNI, protobuf or native networking change.
