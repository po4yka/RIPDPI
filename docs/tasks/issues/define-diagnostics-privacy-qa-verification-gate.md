---
title: Define diagnostics privacy QA verification gate
type: task
status: review
area: testing
priority: medium
owner: QA Lead
parent: null
blocks: []
blocked_by: [UNRESOLVED-POY-13, UNRESOLVED-POY-14]
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task Define diagnostics privacy QA verification gate #repo/RIPDPI #area/testing #status/review 🔼

## Objective
Define the QA verification gate for diagnostics/privacy/export acceptance criteria after the PCAP and AppSec decisions are available.

## Context
POY-6 defines the product acceptance checklist for diagnostics and runtime telemetry wording. Follow-up decisions POY-13 and POY-14 will determine the approved packet-capture/export boundary. QA needs a machine-verifiable gate before user-visible diagnostics, telemetry, export, settings, or privacy-copy changes proceed.

User story:
As a QA reviewer, I want diagnostics privacy requirements translated into observable tests and artifacts, so that implementation cannot ship with misleading export behavior or incomplete privacy disclosure.

Affected surface:
Diagnostics screen, History screen, Home analysis share controls, Advanced Settings diagnostics history controls, Data Transparency screen, settings support bundle flow, diagnostics archive contents.

## Acceptance criteria
1. User story: As a QA reviewer, I want diagnostics privacy requirements translated into observable tests and artifacts, so that implementation cannot ship with misleading export behavior or incomplete privacy disclosure.
2. Observable behavior: QA posts a testability confirmation naming the required UI screenshots/tests, archive fixture checks, and manual review artifacts for each privacy-sensitive diagnostics/export surface.
3. Success metric or test name: QA names concrete tests from `DiagnosticsArchiveExporterTest`, `DiagnosticsArchiveRendererTest`, `DiagnosticsScreenTest`, `RipDpiScreenCatalogScreenshotTest`, `AdvancedSettingsScreenCharacterizationTest`, `HomeScreenTest`, or creates follow-up automation tasks for missing coverage.
4. Privacy implication: Yes. This is the verification gate for data collected, retained, exported, displayed, and shared by the user.
5. Rollback note: QA must state what fallback/disabled states need verification, including diagnostics monitor off, export history off, non-root PCAP unavailable, and failed archive/log export states.
6. Explicit non-goals: This issue does not implement tests directly unless QA chooses to create child automation work. This issue does not approve privacy copy. This issue does not run release certification.

Privacy implication:
High. QA verification must prove disclosure and export behavior match the AppSec-approved boundary.

## Required verification
QA comment with test names/artifacts and any child automation issues required.

Rollback note:
If coverage is insufficient, create child test automation tasks and keep affected implementation/copy tasks blocked until coverage is present or explicitly waived by QA and PM.

Non-goals:
- No app or native implementation changes.
- No release signoff.
- No legal policy decision.

## Definition of done
QA posts a concrete verification matrix and creates any missing automation follow-ups needed before implementation/copy tasks can close.
