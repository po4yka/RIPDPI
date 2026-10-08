---
id: TST-1791483113190963
title: Refresh fleet fixtures against the current deployer contract
kind: chore
status: review
area: testing
priority: medium
owner: Fleet contract worker
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-08
updated: 2026-10-08
spec_reason: test-only
status_detail: Approved fixture regeneration passed deployer provenance, 26 Python tests, 65 Kotlin parser tests, and independent diff review.
---

## Goal

Pin fleet compatibility checks to the current reviewed deployer commit and retain deterministic emitted bundles.

## Acceptance criteria

- Inspect structural drift against deployer ff0ae2198aa82648a03203a0ab52d5cdac485df3 before changing any fixture.
- Obtain explicit approval for the affected fleet-fixtures family before regeneration.
- After approval, pass emitter provenance checks and the fleet parser tests.

## Ownership

Fleet contract worker owns scripts/fleet-fixtures/deployer-git-sha.txt, core/data/src/test/resources/fleet-fixtures, and this task/work record in the isolated test/current-fleet-contract branch. The golden specialist owns the authorized regeneration command. Other workers own snapshot fixtures and production fixes. The integration owner serializes generated board integration. The deployer repository remains read-only.

The golden specialist now owns regeneration, validation, and the scoped implementation commit for this task. The user explicitly approved this fleet-fixtures family and deployer pin `ff0ae2198aa82648a03203a0ab52d5cdac485df3` after the structural diff was reviewed. Expected parser outputs and the port-hop reference bundle remain outside the write scope.
