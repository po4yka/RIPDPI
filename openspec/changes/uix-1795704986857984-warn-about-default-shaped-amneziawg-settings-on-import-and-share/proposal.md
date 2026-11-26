# Change: Warn about default-shaped AmneziaWG settings on import and share

Task ID: `UIX-1795704986857984`

## Why

Importing or sharing an AmneziaWG config warns the user when its handshake looks like plain WireGuard, when its header values are defaults, or when its I1 packet equals a publicly known default.

## What Changes

- Import warns when the parsed handshake shape is identical to plain WireGuard because no obfuscation field is set.
- Import and share warn when the header values equal the documented defaults.
- Import and share warn when the I1 packet matches the digest of a publicly known default packet.
- Warnings are advisory: the user can continue, and the warning text and logs hold no packet bytes or key material.

## Capabilities

### New Capabilities

- `uix-1795704986857984-warn-about-default-shaped-amneziawg-settings-on-import-and-share`: Warn about default-shaped AmneziaWG settings on import and share

### Modified Capabilities

- None.

## Impact

- Portfolio area: `ui`.
- Filed at proposal level only. Design and implementation decisions are open until the task starts.
