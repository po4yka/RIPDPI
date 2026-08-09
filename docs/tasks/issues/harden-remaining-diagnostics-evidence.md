---
id: DGN-1786264762917145
title: Harden remaining diagnostics evidence
kind: bug
status: review
area: diagnostics
priority: high
owner: Codex diagnostics completion coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: dgn-1786264762917145-harden-remaining-diagnostics-evidence
created: 2026-07-29
updated: 2026-08-09
---

## Goal

Close the confirmed source-side diagnostics gaps left after the P0, P1, and P2
waves without claiming physical-device or dual-vantage evidence that was not
observed.

## Scope and ownership

1. **Archive lane** owns failure-time context, privacy projection, authoritative
   archive inventory, file/record reconciliation, composite reconstruction,
   bounded logs, truncation accounting, and archive tests. It is the only lane
   allowed to touch archive-v4 fixtures, and only after explicit fixture-family
   approval.
2. **Runtime correctness lane** owns zero-counter generation resets,
   process-exit/bootstrap ordering, acceptance-generation compare-and-set, and
   terminal-outbox paging.
3. **Device evidence lane** owns public-API-only installed-artifact provenance,
   foreground-service visibility, boot/unlock recovery receipts, and categorical
   UID-policy capability evidence.
4. **Network evidence lane** owns generation-bound passive TUN health,
   policy-consistency assessment, transport-capability acceptance, and removal
   of unsafe public probe defaults. It must not describe local SOCKS success as
   authoritative TUN or leak proof.
5. **Presentation lane** owns standalone completion/termination projection and
   all locale strings. It must not change the diagnostics wire schema.
6. **Verifier lane** is read-only and reviews the rebased combined tree for
   correctness, privacy, cancellation safety, scope, and cross-lane collisions.

## Boundaries

- No serial, Android ID, IMEI, stable device/network identifier, IP or DNS
  address, interface name, SSID/BSSID, operator code, endpoint, profile secret,
  raw certificate, filesystem path, or unrestricted exception text.
- No hidden vendor-specific Android API and no claim that OEM sleeping-app membership
  was observed.
- No archive golden, quality baseline, dependency, release, signing, version, or
  application identity changes without the exact required authorization.
- External physical-device, provider, controlled-endpoint, and dual-vantage
  evidence is outside the release gate and not source-code completion.

## Acceptance

- Each behavior slice is an atomic Conventional Commit with focused regression
  tests and independent review.
- Archive inventory, completeness, integrity, and actual ZIP entries agree for
  every declared optional entry.
- Runtime evidence is generation-safe, bounded, replay-safe, and fail-closed.
- Device and network outputs use only categorical, count, band, or SHA-256
  evidence with explicit unavailable/inconclusive states.
- Targeted tests, static analysis, architecture health, task-board check,
  translation parity, locked Cargo metadata, and combined-tree review pass.

## Work log

- 2026-07-29: Reconstructed the remaining scope from the original diagnostics
  review, the unfinished P0 worktree, current main, task board, and seven
  independent read-only audits. Shared archive and locale files are assigned to
  serialized lanes before writers start.
- 2026-07-29: Integrated the reviewed runtime lane as twelve atomic commits.
  Generation-bound data-plane evidence, startup exit reconciliation, durable
  acceptance CAS, bounded fail-closed terminal recovery, and cancellation-safe
  runtime leases passed focused JVM, Room, correctness, and cancel-safety review.
- 2026-07-29: Integrated the reviewed presentation lane as seven atomic commits.
  Standalone partial and terminal outcomes now remain consistent across rows,
  History, share, archive summaries, and both manual-conflict cancellation
  orderings; locale parity and translation export passed in the source lane.
- 2026-07-29: Archive hardening remains in review after the first independent
  pass found raw structured-target and PCAP privacy paths plus seven failure and
  truncation edge cases. Archive-v4 fixtures remain unchanged pending explicit
  approval for the exact generated fixture set.
