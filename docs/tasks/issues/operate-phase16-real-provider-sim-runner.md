---
title: Operate Phase-16 real-provider SIM runner
type: task
status: backlog
area: testing
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-25
updated: 2026-05-25
---

- [ ] #task Operate Phase-16 real-provider SIM runner #repo/RIPDPI #area/testing #status/backlog 🔼

## Summary

Provision the private self-hosted carrier hardware and hook scripts required by the `runnerRequired=real-provider` Phase-16 rows.

## Motivation

The repository now has a contract that separates synthetic lab evidence from real-provider evidence, but the actual SIM/modem runner remains an operator-owned environment. Release confidence should only include real-provider cells after the runner has a local namespace map, pcap scrubber policy, and successful artifacts.

## Scope

- In scope: private runner labels, local namespace mapping, `RIPDPI_PHASE16_REAL_PROVIDER_CONFIG`, `RIPDPI_PHASE16_PREPARE_HOOK`, pcap scrub policy, and release-gate evidence recording.
- Out of scope: committing IMSI, subscriber IDs, APN secrets, carrier IP addresses, or modem firmware blobs.

## Acceptance criteria

- [ ] A self-hosted runner with `real-provider` and provider namespace labels can execute filtered real-provider Phase-16 rows.
- [ ] The prepare hook selects the requested namespace without exposing SIM identifiers in logs or artifacts.
- [ ] Missing runner config fails closed with a `phase16-run.json` failure and pcap summary metadata.
- [ ] Release documentation names the exact workflow dispatch input and evidence artifact required before claiming real-provider confidence.

## Links

- [Design spike: Phase-16 lab matrix on real-provider SIM](../../architecture/spike-phase16-real-provider.md)
- [Parent spike](spike-adversarial-network-harness-and-realprovider-matrix.md)
