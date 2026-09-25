---
id: RLY-1790329719797199
title: Make saved relay profiles selectable and editable
kind: bug
status: doing
area: relay
priority: high
owner: UI agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: saved-relay-profile-management
created: 2026-09-25
updated: 2026-09-25
---

## Goal

Users can reach and select every saved VPN relay profile, and edit kinds supported by the Mode Editor without losing another profile.

## Acceptance criteria

- Every saved relay profile is reachable from the VPN summary, including profiles after the third item.
- Selecting a profile activates that exact saved profile without changing saved credentials. Supported kinds open their own settings and credentials for editing.
- Changing a draft ID to an existing different profile cannot overwrite that profile.
- Existing profiles keep their relay kind and imported metadata when edited.
- App unit tests and static analysis cover the changes.

## Ownership

The relay-profile agent owns Config/VpnConfig/ModeEditor, relay profile persistence, the profile mutation coordinator CAS API, and their tests in its own worktree. The PCAP agent owns diagnostic capture files in a separate worktree. Locale resources were serialized after PCAP changes finished.
