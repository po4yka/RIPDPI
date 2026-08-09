---
id: TST-1786264762917827
title: Operate Phase-16 real-provider SIM runner
kind: chore
status: blocked
area: testing
priority: medium
owner: Real-provider test lab maintainer
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-05-25
updated: 2026-08-09
spec_reason: test-only
status_detail: Repository workflow and validators are ready; execution requires an operator-owned physical carrier runner and provider credentials.
---

## Summary

Provision the private self-hosted carrier hardware and hook scripts required by the `runnerRequired=real-provider` Phase-16 rows.

## Motivation

The repository now has a contract that separates synthetic lab evidence from real-provider evidence, but the actual SIM/modem runner remains an operator-owned environment. Release confidence should only include real-provider cells after the runner has a local namespace map, pcap scrubber policy, and successful artifacts.

## Scope

- In scope: private runner labels, local namespace mapping, `RIPDPI_PHASE16_REAL_PROVIDER_CONFIG`, `RIPDPI_PHASE16_PREPARE_HOOK`, pcap scrub policy, and release-gate evidence recording.
- Out of scope: committing IMSI, subscriber IDs, APN secrets, carrier IP addresses, or modem firmware blobs.

## Acceptance criteria

- [ ] A self-hosted runner with `real-provider` and provider namespace labels can execute filtered real-provider Phase-16 rows.
- [x] The prepare hook selects the requested namespace without exposing SIM identifiers in logs or artifacts.
- [x] Missing runner config fails closed with a `phase16-run.json` failure and pcap summary metadata.
- [x] Release documentation names the exact workflow dispatch input and evidence artifact required before claiming real-provider confidence.

## Links

- [Phase-16 real-world confidence status](../../testing.md#phase-16-real-world-confidence-status)

## Work log

- 2026-05-25: Added fail-closed real-provider runner config validation for `phase16_real_provider_runner_v1`, symbolic namespace lookup, and required pcap scrub policy; real-provider prepare hooks now receive `RIPDPI_PHASE16_REQUESTED_NAMESPACE`, suppress hook stdout/stderr, and emit only non-secret hook metadata.
- 2026-05-25: Extended `phase16-run.json` and `phase16-pcap-summary.json` metadata with real-provider config/hook status, and documented the exact `include_real_provider=true` workflow dispatch input plus required `phase16-<entry-id>` artifact evidence before release confidence can claim real-provider coverage.
- 2026-05-25: Verification run: `python3 scripts/ci/phase16_matrix.py validate`, `bash -n scripts/ci/run-phase16-matrix-entry.sh`, targeted real-provider unittest cases, and full `python3 -m unittest scripts.tests.test_phase16_matrix` passed.
- 2026-06-05: Repo-side contract fully implemented (criteria 2-4 verified: suppress hook stdout/stderr + RIPDPI_PHASE16_REQUESTED_NAMESPACE export in run-phase16-matrix-entry.sh:140/156, fail-closed runner_unavailable paths in run-phase16-matrix-entry.sh:37-40/174-187, release dispatch docs in docs/testing.md:220). Criterion 1 remains open: physical carrier SIM hardware and self-hosted runner registration with real-provider + namespace labels is operator infrastructure not present in the repo.
- 2026-06-05: Re-verified all criteria against source. Criteria 2-4 confirmed [x]: hook stdout/stderr suppressed via `>/dev/null 2>&1` at run-phase16-matrix-entry.sh:156, RIPDPI_PHASE16_REQUESTED_NAMESPACE exported at :140; fail-closed runner_unavailable function at :37-41 with pcap summary written in on_exit trap at :107-108 and realProvider fields in write_manifest at :67-72; release gate documented at docs/testing.md:220 naming `include_real_provider=true` input and `phase16-<entry-id>` artifact. Criterion 1 remains [ ]: fixture has valid runsOn labels (real-provider + ns-mts/ns-megafon/ns-beeline per contract-fixtures/phase16_lab_matrix.json) but physical self-hosted runner registration is operator infrastructure with no repo-side evidence of deployment. Status remains doing.
