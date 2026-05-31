---
title: Add Xray profile UX and import flow
type: task
status: doing
area: outbound
priority: medium
owner: unassigned
parent: epic-xray-provider-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-31
---

## Summary

Add the user-facing flow for selecting Xray VPN mode and importing or editing initial Xray profiles.

## Motivation

tunneled outbound profile support needs to fit the existing Mode Editor, Settings, and onboarding model without exposing low-level config trivia or secrets.

## Scope

- In scope: provider selection, profile import, validation errors, selected route summary, onboarding validation, and localized copy.
- Out of scope: subscription management, server purchase/provisioning, and multi-provider catalogs.

## Acceptance criteria

- [x] Mode Editor can select Xray-backed VPN mode separately from native RIPDPI direct/proxy modes. — `XrayServiceModeOption` (`:core:data:runtime-state`) flattens provider×mode into the mutually-exclusive picker set; `XrayProviderSelection` (`:app`) records the choice and persists the mode. (`XrayServiceModeOptionTest` green offline; the `:app` wiring is part of the UNVERIFIED `:app` lane below.)
- [x] Import supports at least the first approved share/config shapes and fails closed on unsupported or unsafe fields. — `XrayImportParser` (`:core:data:catalog`) parses `vless://` REALITY/XHTTP links and raw config JSON, rejecting unsupported transports, missing fields, allowInsecure, and broken REALITY+XHTTP tags; `XrayImportParserTest` green offline.
- [x] Validation errors are actionable but redact credentials and endpoints. — import errors return REDACTED, jargon-free messages; verified by `XrayImportParserTest` (offline) and the redaction regression suite.
- [ ] Onboarding can validate an Xray profile as the chosen mode before finish. — the reusable validation surface (`XrayProfileImportViewModel`, `XrayCapability`) exists and is wired for onboarding reuse, but the onboarding-to-finish flow is exercised only by `:app` tests that did not run green here. PARTIAL: blocked on the `:app` test lane below.
- [ ] Compose/UI tests cover selection, validation failure, and successful imported-profile state. — `XrayProfileImportScreenTest` / `XrayProfileImportViewModelTest` are authored and were exercised to green during development, but the final `:app:testGithubDebugUnitTest` run is UNVERIFIED IN CI here: the offline Gradle build cannot reconfigure build-logic (`gradle-kotlin-dsl-plugins:6.5.7` missing from the offline plugin cache — a pre-existing environment limitation that reproduces in untouched sibling worktrees). OPEN: blocked on offline plugin-cache, not on missing code.

## Progress

**2026-05-30** — Profile UX + import flow landed (commit `feat(xray): add Xray provider selection, import, and onboarding validation UX`): the fail-closed `XrayImportParser`, `XrayCapability` labels, `XrayServiceModeOption`, and the `XrayProfileImportScreen`/`ViewModel` + provider picker built entirely from existing RDS tokens, wired into navigation and Hilt, with new strings across all 7 locales. The pure-data lanes (`:core:data:catalog`, `:core:data:runtime-state`) are verified offline; the `:app` Compose/ViewModel tests were exercised green during development but the final `:app:testGithubDebugUnitTest` capture is blocked by a pre-existing offline plugin-cache miss (`gradle-kotlin-dsl-plugins:6.5.7`), not by this change. Remaining: re-capture the `:app` UI/ViewModel test lane on a network-enabled or fully-cached build.

## Design notes

Use provider capability labels rather than protocol jargon wherever possible: VPN privacy, relay, split/full tunnel, anti-DPI, and DNS protection.

## Risks / open questions

- Imported raw JSON can become an expert-only escape hatch; the first UX should prefer typed forms and known share links.

## Links

- [[Epic - Xray provider mode]]
- [[Render validated Xray client configs]]
- ripdpi-android-xray-provider-plan-2026-04-24
