---
title: Add last-active-profile persistence in direct-boot storage
type: task
status: backlog
area: service
priority: medium
owner: unassigned
parent: epic-boot-autostart-and-session-persistence
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Add last-active-profile persistence in direct-boot storage #repo/RIPDPI #area/service #status/backlog 🔼

## Summary

Persist a non-sensitive pointer to the last-active profile (id + service mode) in a device-protected (direct-boot-aware) storage location so `LOCKED_BOOT_COMPLETED` can resume before the user unlocks.

## Context

The profile bean itself (which contains secrets) must stay in user- protected Credential Encrypted storage. Only a stable id and the service mode go into the direct-boot path. The service resumes with the pointer; secret-bearing fields are read after unlock completes, and the tunnel is refreshed at that point if anything had to hold.

## Acceptance criteria

- [ ] A `DeviceProtectedSettings` store holds `{ profileId, serviceMode }` — no secrets.
- [ ] The full profile bean never lands in device-protected storage.
- [ ] Resume logic: at `LOCKED_BOOT_COMPLETED`, start the service with the pointer only; at user unlock, re-materialize the full profile and trigger the supervisor's existing reload path.
- [ ] If the referenced profile is deleted, the pointer clears silently and the service does not attempt to start.
- [ ] Unit tests cover the before/after-unlock transition.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt` — `selectedProxy: Long` property, persisted in the Room-backed PreferenceDataStore.
- `app/src/main/java/io/nekohasekai/sagernet/BootReceiver.kt` — on `LOCKED_BOOT_COMPLETED`, reads the last-active profile and starts the service before user unlock. reference implementation does NOT split storage into device-protected vs user-protected — **this is a deviation point** for RIPDPI.

**Android reference** — for direct-boot storage split, follow Android docs on `createDeviceProtectedStorageContext()`. The profile ID (Long) is non-sensitive; the profile bean (with keys) is sensitive. Split at that boundary.

**Adapt:** The `selectedProxy` pointer concept. **Improve over reference implementation:** split device-protected (ID only) vs credential-protected (full bean). reference implementation stores the full Room DB in user-protected by default but does not surface the boundary.

## Links

- [[Epic - Boot autostart and session persistence]]
- [[Add boot-completed receiver with dynamic enable]]
