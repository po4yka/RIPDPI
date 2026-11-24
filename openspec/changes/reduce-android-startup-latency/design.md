## Context

The Pixel 8 Pro debug trace showed about half a second between the strategy setup marker and the diagnostics bootstrap marker. This interval includes process exit reconciliation and diagnostics bootstrap.

## Goals / Non-Goals

- Reduce repeated I/O and layout work before the first screen.
- Measure startup in a release-derived build.
- Preserve stored data formats and required recovery order.

## Decisions

- Keep exit reconciliation before runtime history. Batch exit event persistence and load diagnostic catalog versions together. Trace reconciliation, recovery, catalog import, and maintenance as separate intervals.
- Run archive cache maintenance in the application I/O scope. Keep the existing archive mutex.
- Wait behind the existing splash screen before composing the recovery shell. Retain the retry UI after a recovery failure. Report full display after navigation has content.
- Use the managed benchmark lane to record and check the reference. Use a deliberate main-branch calibration when the lane has no reference.

## Contracts and ownership

The Android performance worktree owns app startup, diagnostics bootstrap and data access, baselineprofile, and the macrobenchmark CI job. The database schema stays at its current version.

## Risks / Trade-offs

The first-run, retry, and application lock paths can present different content. Keep the first-destination signal on the navigation host and record the launch scenario for each benchmark.

## Migration Plan

Ship the runtime changes with the current data format. Calibrate CI with a release-derived run on the managed benchmark device.
