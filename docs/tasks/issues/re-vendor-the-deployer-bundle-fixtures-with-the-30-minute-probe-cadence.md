---
id: DAT-1793428393229398
title: Re-vendor the deployer bundle fixtures with the 30-minute probe cadence
kind: chore
status: review
area: data
priority: medium
owner: Client contract maintainer
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-31
updated: 2026-10-31
spec_reason: test-only
status_detail: Both fixtures re-vendored byte-identically; CI kotlin-coverage (every core module unit test, including RipdpiBundleContractTest, PayloadFailoverImportTest and PayloadSelectorTopologyTest) passed on 8d68ceb; the deployer contract-sync dispatch after both merges remains.
---

## Goal

The vendored `ripdpi-bundle.payload-failover.json` and `ripdpi-bundle.hysteria-port-hopping.json` under `core/data/src/test/resources/contract/` are byte-identical to the deployer's `contract/` copies after the deployer moves the bundle's `urltest` interval from `5m` to `30m` (`po4yka/ripdpi-vpn-deploy#SEC-1793428392418058`), so the deployer's `contract-sync` workflow stays green and devices stop probing every transport in a five-minute burst.

## Acceptance criteria

- Both vendored files differ from their previous version only in the `auto` outbound's `interval` scalar and match the deployer's files byte-for-byte.
- No client test asserts the five-minute `urltest` interval; `RipdpiBundleContractTest`, `PayloadFailoverImportTest` and `PayloadSelectorTopologyTest` pass on the re-vendored fixtures.
- The deployer's `contract-sync` dispatch passes once both repositories have merged.
