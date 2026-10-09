---
id: SVC-1791570478587374
title: Preserve protect socket ownership across VPN session overlap
kind: bug
status: doing
area: service
priority: high
owner: Android
parent: null
blocked_by: []
spec_mode: required
openspec_change: svc-1791570478587374-acceptance-protect-socket-ownership
created: 2026-10-09
updated: 2026-10-09
---

## Goal

Keep the active VPN protect socket reachable when an old service session finishes cleanup after a new session starts. Coordinated by TST-1791553917096956.

## Acceptance criteria

- Preserve the original c2a failed Android report and its TCP empty EOF, UDP timeout, and server receipts.
- Reproduce overlapping socket ownership with a failing deterministic test before the production fix.
- Old cleanup cannot remove or close a newer endpoint. Failed and repeated operations preserve ownership.
- Keep descriptor protection and fail-closed behavior on non-root devices.
- Pass full service tests, static analysis, genuine Android profile and repeats, independent review, and exact published CI.

## Ownership

Android owns VpnProtectSocketServer, VpnServiceSessionLifecycle, ActiveProtectSocketPathProvider, VpnNativeProtectRegistration, runtime context assembler/resolver wiring and their tests, the named core/service test fixture and app Android endpoint probe, plus the single app/build.gradle.kts test fixture dependency. The coordinator owns this task and OpenSpec artifacts. Linux owns read-only failure analysis. No other writer changes these files.

## Observed failure

The clean c2a Android Network run starts a new protect listener at 57.267 and logs old server stopped at 57.268. The old stop deletes the shared pathname after joining its threads. This is a concrete possible overlap defect; the exact historical unlink order and causal connection to this traffic failure require a deterministic reproduction and fresh runtime evidence. The TCP reader completed connect but returned empty EOF, UDP timed out, and restart TCP had no server receipt. DoH success uses a distinct protection path and does not prove this endpoint remains reachable.

## Reproduction evidence

Existing-API JVM tests observed 22 tests and exactly 3 assertion failures, with no errors: unbound old server deletes a foreign endpoint, old provider withdrawal clears replacement, and old native cleanup releases generation2 in the replacement slots.

The explicit-serial Android wire probe v2 used real LocalSocket, SCM_RIGHTS, and the active VpnService.protect controller in an isolated directory. The replacement returned ACK0 before old cleanup, with one successful protect call and zero failed calls. After old.stop, replacementPathPresent was false and connect failed with IOException, so its ACK was null. The final preserved assertion expected0 and failed at line88. One test, one assertion failure, zero errors/skips. Source before/after diagnostic source-tree SHA256 475bfde89d53aae6ef122fa18f9b6a44438a4bdb779cca5605e0b51be4788949 was unchanged on base2ae5493aa. Private XML, logcat, probe-fields.log and APK hashes remain in the Android worktree build/acceptance/protect-wire-red-v2-2ae. This actual boundary proves the UDS source defect; it does not measure the exact past c2a syscall or replace final service-generation/traffic acceptance.

Provenance: source_tree_sha256 before/after is 475bfde89d53aae6ef122fa18f9b6a44438a4bdb779cca5605e0b51be4788949; tracked_diff_sha256 is 65bb6689d2ecd951b539e742344da24a7f9ba814d233db83e7ff702d5cb7a054. Paired JSON snapshots and diffs remain in Android build/acceptance/environment-android-initial/protect-wire-red-v2-source-before.json and protect-wire-red-v2-source-after.json. These are diagnostic dirty-source artifacts, separate from the final clean catalog.
