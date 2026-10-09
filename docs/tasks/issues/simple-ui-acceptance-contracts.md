---
id: TST-1791554642630646
title: Update Simple UI acceptance contracts
kind: bug
status: doing
area: testing
priority: high
owner: Acceptance coordinator
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-09
updated: 2026-10-09
spec_reason: test-only
---

## Goal

Match Simple UI acceptance tests to the approved action button and report workflow. Current main CI fails three Simple unit tests, the Simple navigation test on five API levels, and thirteen screenshot checks after the shared action control changed in 931985afa.

## Acceptance criteria

- Check the button text, status, disabled lockdown behavior, and one-click cancellation while connecting.
- Exercise Report, Cancel, completion, and share archive through MainViewModel with the current Simple controls.
- Run Simple unit tests and the affected device integration scenario. Inspect screenshot differences before any fixture update.
- Preserve the connected-state confirmation and archive payload assertions.

## Ownership

The coordinator owns SimpleHomeScreenTest and MainActivityNavigationInstrumentedTest in its isolated worktree. The Android writer owns production DNS code and Xray TUN tests in a separate worktree. Screenshot fixtures remain unchanged until explicit family-level authorization.

## Initial evidence

CI run 37935444773 on f52964e42 reports failures at SimpleHomeScreenTest lines 281, 320, and 388. The API 35 log records a missing home-modes-diagnostics-header selector. These tests still use the prior control and Full diagnostics layout. The production Simple screen uses HomeDiagnosticsRunAnalysis for both report and cancellation.

## Local validation

The full Simple JVM suite ran 2808 tests. All 23 SimpleHomeScreenTest cases pass. The remaining 13 failures are screenshot baselines for the accepted connection action/status contract. The actual/expected comparisons were reviewed in light, dark, RTL, and large-font states. Golden blessing awaits explicit approval for the two affected families. Android report cancellation and archive sharing remain pending device validation. Retained logs: build/acceptance/session-20261009/simple-unit-golden-retry.log.
