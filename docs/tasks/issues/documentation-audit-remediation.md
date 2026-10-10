---
id: TST-1791621908659469
title: Correct documentation audit findings and refresh screenshots
kind: chore
status: done
area: testing
priority: high
owner: Documentation maintainer
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-10
updated: 2026-10-10
spec_reason: docs-only
closed_at: "2026-10-10T09:31:19Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: All 29 correction commits were integrated by fast-forward and pushed to origin/main; remote SHA 51039ca370553a6cbf70350b6df42dfae335f803 matches local main. Nine public README variants corrected. APK build, libXray verification, 21 actual Android captures across seven locales, source freshness, five regression tests, production image capture and strict 56/56 image validation passed. 1260 Markdown files and 1052 local references have no missing targets; README selectors passed 72 link and nine bold assertions. Design, harness, task, architecture and locked Cargo metadata checks passed; baselines unchanged. Exact text projection autoreview returned zero actionable findings; full diff TruffleHog clean; PNGs separately visually inspected. Phase16 command starts and 53 harness tests passed, but runtime gate exit 1 has 23 blocked and 40 bypassed cells out of 63, documented without claiming physical-device or carrier-network acceptance. Remote required CI and CodeQL completion remain unverified.
---

## Goal

Correct the findings from the documentation audit at `e9471d33a`. Keep runtime
behavior and compatibility contracts unchanged. Refresh README images from the
current Android UI and publish one separate commit for each audited problem.

## Acceptance criteria

- Current contract, build, localization, test, and export instructions match
  their source files. All nine public READMEs carry the applicable corrections.
- README image descriptions match their pixels. Real app frames are readable,
  current, and linked to capture provenance. Localized image paths are used.
- Local documentation, task, harness, and image checks pass. Review and inspect
  the combined change before integration. Record device and CI limits exactly.
- Integrate the checked commits into `main` with fast-forward and push to origin,
  as authorized by the user on 2026-10-10.

## Parallel ownership

| Owner | Paths and responsibility |
| --- | --- |
| Documentation worker | Current prose under `docs/`, excluding `docs/tasks/`, `docs/screenshots/`, and `docs/fa/README.md`; contract, soundness, privacy, manual, localization, QA, and source-pointer findings |
| README worker | Root `README*.md` and `docs/fa/README.md`; all semantic, build, translation, image-path, alt, and image-readability fixes |
| Screenshot worker | `play-store-screenshots/`, `docs/screenshots/`; current Android captures, provenance, localized composites, compatibility copy, and image validation |
| Integration owner | This portfolio record, its execution file and generated board; combined review, validation, integration, and push |

Each writer uses a separate worktree. The README set and screenshot set each
have one writer. Source registries, native artifacts, locale resources, golden
fixtures, baselines, dependency manifests, and lockfiles are outside this change.
README image references use `docs/screenshots/ui/<locale>/` only after the
screenshot worker supplies those files; Hindi and pt-BR use a disclosed English
fallback until screenshots for those locales exist.
