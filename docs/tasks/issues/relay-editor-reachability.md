---
id: RLY-1790339260163788
title: Expose dedicated relay profile editors from VPN settings
kind: bug
status: review
area: relay
priority: high
owner: Relay UI agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: relay-editor-reachability
created: 2026-09-25
updated: 2026-09-25
status_detail: Local app unit and lint passed; Roborazzi fixture refresh awaits explicit approval after UI integration
---

## Goal

Users can open the existing AnyTLS, Mieru, SSH, and AmneziaWG profile creation screens from VPN configuration without an external launch request.

## Acceptance criteria

- VPN configuration has visible links to the four existing dedicated profile editors.
- The links navigate to the registered destinations without changing saved profiles.
- Compose navigation and screen regression tests pass.

## Ownership

- Writer: Relay UI agent in `fix/relay-editor-reachability` worktree.
- Owned paths: `ConfigScreen.kt`, `VpnConfigScreen.kt`, `RipDpiNavHost.kt`, `RipDpiTestTags.kt`, focused app tests, this task, and `openspec/changes/relay-editor-reachability/`.
- The separate inline relay editor task owns Mode Editor, relay draft validation and persistence, and any new locale resources.
- No other writer edits these owned paths until this branch is integrated.
