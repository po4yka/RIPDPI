## Context

Task ID: `UIX-1793256952721314`. Part of epic `EPC-1793256695505524`; blocked by `UIX-1793256927624290`. Owner decisions of 2026-10-29 apply.

## Goals / Non-Goals

- Goal: Rearrange the main screen families to Material 3 canonical layouts and action placement so each window size class gets a layout made for it.
- Non-goal: network, service, native, JNI, or storage behavior changes.

## Decisions

- Order: Home, Settings, Diagnostics, Logs, History, then the remaining families.
- Each screen family can run in its own worktree after the component facade lands, with one writer for locale files and fixtures.
- Keep the Guided and Advanced persona rules: every Advanced control stays reachable from Guided in place.

## Contracts and ownership

- Automation resource IDs and test tags stay stable unless a step names the change.
- Screenshot fixtures change only after owner approval.

## Risks / Trade-offs

- Detail-pane routes change deep links and automation launch routes; update the automation contract in the same commit.
- Two panes at 200% font can be too narrow; fall back to one pane when the measured content does not fit.
