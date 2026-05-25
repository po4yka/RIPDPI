# Design spike: generator-driven packet-smoke

Status: design proposal (2026-05-16) Tracks: [`spike-adversarial-network-harness-and-realprovider-matrix.md`](../tasks/issues/spike-adversarial-network-harness-and-realprovider-matrix.md)

## Problem

`scripts/ci/packet-smoke-scenarios.json` hand-lists ~10-15 named scenarios. The desync parameter space is at least 7-dimensional (split offset, TLS record split, TLS-randrec profile, UDP burst, QUIC fake profile, fake-TTL ladder, OOB-byte placement). A hand-curated list can cover the well-known L7 path-variation scenarios but cannot defend against regressions in less-visited corners of the space.

## Goal

Augment — not replace — the named scenarios with a generator that samples the 7-dim space and replays each sample against the existing packet-smoke oracle. PR CI runs all named scenarios + N random samples per dimension; nightly increases N and adds combinatorial sweeps.

## Input space

Each axis is enumerable or has a small canonical set of values:

| Dim | Axis | Sample set |
|---|---|---|
| 1 | `split_offset` | 0, 1, 2, 3, mid, mid-1, end-1, end |
| 2 | `tls_record_split` | none, between handshake and app data, mid-handshake |
| 3 | `tlsrandrec_profile` | off, profile A, profile B, profile C |
| 4 | `udp_burst` | off, low, medium, high |
| 5 | `quic_fake_profile` | off, fake-A, fake-B, fake-C |
| 6 | `fake_ttl_ladder` | off, 2, 4, 8, 16, 32 |
| 7 | `oob_byte_placement` | off, pre-handshake, post-sni, mid-app |

The sample sets above are illustrative; the spike landing PR pins them against the actual enums in `ripdpi-desync`.

## Sampling strategy

- **Named scenarios** run on every PR. These are the L7 path-variation scenarios encoded today in `packet-smoke-scenarios.json`.
- **Per-PR random samples** run N = 8 cells. The seed is per-PR and recorded in the artifact so failures reproduce. Sample without replacement across PRs in a rolling window so coverage spreads.
- **Nightly** runs N = 64 random samples plus all 2-axis combinatorial sweeps (every pair of dimensions with every value-pair, holding the other 5 axes at their default). This is large but still finite.

## Oracle

Same as today: the recorded pcap and `phase16_pcap_summary.py` output are diffed against the expected byte-shape generated from the desync plan. Generator-driven cells reuse this oracle; the only new piece is the manifest writer that emits the plan and the expected shape alongside the input.

## Contract changes

- Add `generator_seed`, `generator_axis_values`, `generator_origin` (`named` | `random` | `sweep`) fields to the per-scenario fixture output. Existing named-scenario tooling ignores them.
- The pcap summary contract stays unchanged so existing tooling continues to work.

## Why not just expand the named-scenario list?

Hand-curated lists encode our current threat model and grow slowly. Generator-driven sampling is the only way to detect regressions in corners we did not think to enumerate. Both surfaces are needed: named scenarios are an anchor; generated samples are coverage breadth.

## Phasing

1. v1 land the generator scaffolding + per-PR N=8 random sampling with recorded seed.
2. v1.1 land nightly N=64 and 2-axis sweeps.
3. v2 wire the generator-driven cells into the L7 adversarial emulator's pattern matrix so cells run (desync × adversary pattern).

## Open design questions

- Generator implementation: bolt onto the existing Rust packet-smoke runner, or a thin Python wrapper that emits the manifest and lets the existing runner consume it? Python wrapper is lower-risk for v1.
- How to deduplicate cells across the rolling-sample window without pinning the seed? Probably a hash of axis values stored alongside the PR artifact, then a budget check at sample time.
- Where do failure artifacts land for random samples? Same artifact bucket as named scenarios, but the manifest carries `generator_origin` so triage knows whether the cell is a regression or a newly-discovered blind spot.
