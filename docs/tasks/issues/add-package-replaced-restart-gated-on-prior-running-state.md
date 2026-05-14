---
title: Add package-replaced restart gated on prior running state
type: task
status: backlog
area: service
priority: low
owner: unassigned
parent: epic-boot-autostart-and-session-persistence
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add package-replaced restart gated on prior running state #repo/RIPDPI #area/service #status/backlog 🔽

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-package-replaced-restart-gated-on-prior-running-state`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

When the app is updated (MY_PACKAGE_REPLACED), auto-restart the tunnel
only if the session was running at the moment the update installed.

## Context

Resuming the session after an update is expected behavior for always-on
VPN use, but blanket resume on every update is wrong (a user may have
stopped the tunnel deliberately before the update). Persist a "was
running" flag on service stop-or-update; read and clear it on the
receive path.

## Acceptance criteria

- [ ] A persistent `wasRunningAtUpdate` flag is set when the session is
    active AND the user is not in the Settings → Stop flow; cleared
    on explicit user-initiated stop.
- [ ] On `MY_PACKAGE_REPLACED`, the receiver reads and clears the flag;
    auto-start only when it was set.
- [ ] Unit tests cover: updated-while-running, updated-while-stopped,
    stopped-then-updated.
- [ ] Flag location is direct-boot aware so the check works even before
    user unlock.
- [ ] No secret material or profile identity surfaces in the flag
    itself.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/BootReceiver.kt` — the `MY_PACKAGE_REPLACED` branch is combined with `BOOT_COMPLETED` and re-reads `DataStore.persistAcrossReboot` without distinguishing "was running at update". **Do not copy this behavior.** NekoBox's approach auto-restarts on every update even if the user had deliberately stopped the tunnel — a correctness bug.
- `app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt` — `DataStore.currentProfile` is cleared on explicit stop, so NekoBox does have the signal, but BootReceiver ignores it.

**Adapt:** The receiver-branch structure. **Improve over NekoBox:** add a `wasRunningAtUpdate: Boolean` flag set when the service is torn down for an update (vs user-initiated stop), and gate the restart on it. This is an explicit correctness improvement documented in the acceptance criteria.

## Links

- [[Epic - Boot autostart and session persistence]]
