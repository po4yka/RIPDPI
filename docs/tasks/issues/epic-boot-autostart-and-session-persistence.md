---
title: Epic - Boot autostart and session persistence
type: epic
status: done
area: service
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-29
---

- [x] #task Epic - Boot autostart and session persistence #repo/RIPDPI #area/service #status/done 🔼

## Goal

Resume the user's active RIPDPI session after device reboot without the user needing to open the app. Today the app has no boot receiver; every reboot forces a manual reconnect.

## Why now

Censorship-bypass clients are expected to be always-on. A user who reboots overnight should wake up tunneled. This is a small, well-scoped epic that materially changes daily-driver ergonomics.

## Key decisions

- **Boot receiver is opt-in and disabled by default.** Enable only when "Start on boot" is toggled on. Dynamic component-state so the receiver does not keep the package alive when unused.
- **Direct-boot (`LOCKED_BOOT_COMPLETED`) is supported,** so the tunnel comes up before the user unlocks. Settings and active-profile selection must be accessible from device-protected storage.
- **Persist the chosen service mode and active profile ID,** not the live session state. On boot, reconstruct the session; do not try to restore in-flight connections.
- **Guard on battery-saver and doze exclusion:** do not auto-start if the user denied background permission.
- **Never start on `MY_PACKAGE_REPLACED` alone** without user consent; an update is not a reboot.

## Scope

- **In scope:** `BootReceiver` (BOOT_COMPLETED + LOCKED_BOOT_COMPLETED
+ MY_PACKAGE_REPLACED), start-on-boot user toggle, last-active-profile persistence in direct-boot-aware storage, Settings permission guard.
- **Out of scope:** scheduled on/off timers (separate automation feature), network-change triggered restart (already handled by `NetworkHandoverMonitor`), carrier/roaming conditional autostart.

## Ship definition

- [ ] User toggles "Start on boot" in Settings; `BootReceiver` is enabled only while this toggle is on.
- [ ] After reboot, if toggle is on and a last-active profile exists, the previously selected service (VPN or Proxy mode) resumes.
- [ ] Direct-boot path works: tunnel up before lockscreen unlock.
- [ ] Battery-saver / doze whitelist guard prompt appears once when the toggle is first enabled; rejection disables the toggle.
- [ ] Package replacement (app update) does not auto-restart unless the session was actively running at update time.
- [ ] No sensitive data (UUIDs, keys, server addresses) lands in direct- boot storage that is device-protected only, not user-protected.

## Child tasks

- [[Add boot-completed receiver with dynamic enable]]
- [[Add last-active-profile persistence in direct-boot storage]]
- [[Add start-on-boot user toggle and permission guard]]
- [[Add package-replaced restart gated on prior running state]]

## Dependencies

- Depends on: [[Epic - System HTTP proxy service mode]] — receiver must resume whichever service mode was active, not default.

## Risks / open questions

- Chinese OEM ROM background policies (Xiaomi, Huawei, Oppo, Vivo, Samsung "Sleeping apps") silently kill auto-start. Document the vendor-specific whitelist steps in onboarding rather than fighting each ROM.
- Direct-boot storage split: ensure the secret-bearing profile fields never land in device-protected storage, only a non-sensitive pointer.
- `MY_PACKAGE_REPLACED` gate: distinguish "was running before update" from "was set up on boot"; only the first justifies auto-restart post-update.

## Links

- [[ripdpi-android]]
- Child issues: 4
