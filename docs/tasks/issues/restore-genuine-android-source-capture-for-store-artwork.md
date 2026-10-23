---
id: CIC-1792748686642962
title: Restore genuine Android source capture for store artwork
kind: bug
status: doing
area: ci
priority: high
owner: Screenshot CI repair
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-23
updated: 2026-10-23
spec_reason: tooling-only
---

## Goal

Restore verified, current-source Android frames and generated Play Store artwork without weakening source freshness or actual-state checks.

## Acceptance criteria

- Reproduce stale-source failure and retain rejection regression coverage.
- Provide opt-in disposable CI capture using actual app/native builds and normal Android VPN consent.
- Review all 42 source frames and 49 unique generated assets; preserve real measured outcomes.
- Pass exact-head PR checks, then merge normally and verify merge-triggered checks.

## Ownership

Screenshot CI repair owns the screenshot workflow, capture tools, generated artwork and this task. Existing owner PR #644 and all native dependency files remain untouched. The repair also owns the relay switch automation tag and its focused Compose regression. Independent review covers capture and provenance changes; separate file ownership is maintained for the build-bundle module and workflow.

## Initial evidence

At main `5e778024b07be987f2bad73d7700a9a4ffdf5266`, `validate-source-captures.py` fails on stale Android inputs. Scheduled run 39932738003 confirms the same failure. The renderer never recaptures Android frames, and its old always-run summary falsely described unchanged retained assets as no drift after failed regeneration.

## Repeatability diagnosis and bounded repair

Real run 39956225603 recorded 35 non-relay frames but no complete capture.
English and Persian relay switches remained off; other locales reached natural
scroll boundaries after nonlinear text wrapping. Smaller densities were not a
reliable substitute for actual viewport evidence.

The relay controller now uses a unique tag, stable bounds and checked-state
readback. Diagnostic-only finite calibration requires two independent whole-
section/alignment passes and retains raw evidence for every trial. It cannot
write a source manifest or render marketing outputs. Normal capture continues
to require all 42 fixed-profile frames. A content-checked build bundle preserves
the original producer revision and exact build inputs, recipe and toolchains
so layout-only retries need not rebuild unchanged native code.

Local verification: 65 capture/provenance/calibration tests, 16 workflow-path,
KVM and emulator contract tests, actionlint and task contracts passed. The new
Compose test requires hosted execution; no local Android result is claimed.
Actual calibration, final visual review and exact-head CI remain pending.
