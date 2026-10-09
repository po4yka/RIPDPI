---
id: TST-1791554642630646
title: Update Simple UI acceptance contracts
kind: bug
status: done
area: testing
priority: high
owner: Acceptance coordinator
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-09
updated: 2026-10-10
spec_reason: test-only
status_detail: Simple JVM suites and approved13 goldens pass; actual emulator cancel/share1of1passes after scroll/resource expectations fix; exact published CI remains coordinator gate
closed_at: "2026-10-09T21:01:40Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Clean1e51343f1: full42 plus Xray repeat1/repeat2/VM-routed PASS; combined7631 executed and staticAnalysis PASS; all5 exact published code workflows PASS; independent source/JNI/runtime review CLEAR; own AVD/Lima stopped, artifacts/evidence preserved; committed review precedes terminal record."
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

## Android interaction evidence

The actual Simple method first failed on clean 2ae5493aa because its shared helper required a LazyColumn container. After target-node scroll correction, it failed on an expected numeric resource ID from FakeInstrumentedStringResolver. The production button uses stringResource. Expectations now use Activity resources, and Simple uses performScrollTo on its existing scroll container.

The complete method passed on emulator-5584, API35 arm64: one testcase, zero failures, errors, or skips. It checked report start, exact cancellation ID, a second completed report, and archive export run/session IDs, reason, and purpose. The repository JUnit validator passed with expected total 1 and forbidden skips. Existing test doubles validate UI wiring; this is separate from real Android traffic acceptance.

Private source-paired receipts and complete XML/logcat are in build/acceptance/session-20261009/simple-ui-scroll-label-fix. Base SHA is 2ae5493aaf886ef22cd6a1addf00f420df8fd8c5; before/after diff SHA256 is d9e2ebdbad05ca27fc36496757c0e5a9730736b189086e67fcc0e70c9eb6b67d. Both initial failed directories remain intact. Independent review found no weakened assertions. Exact published main CI remains an overall acceptance gate.
