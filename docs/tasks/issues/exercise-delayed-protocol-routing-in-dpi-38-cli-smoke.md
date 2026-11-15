---
id: CIC-1794766240119737
title: Verify protocol selection in DPI-38 CLI smoke
kind: bug
status: review
area: ci
priority: high
owner: DPI-38 CLI smoke maintainer
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-15
updated: 2026-11-15
spec_reason: test-only
---

## Goal

Make the positive adaptive CLI fixture reach the applied split route. Keep the independent packet oracle, exact feedback count, negative fallback control, and host-autolearn persistence.

## Acceptance criteria

- Use an unfiltered positive route for the known HTTP driver. Direct CLI connections select the route before payload; L7 filters require a delayed connection that this direct mode cannot enable through a standalone CLI option.
- Keep the TLS-filtered negative control and prove that warmup sends without an attempt owner cannot earn evolution feedback.
- Run the affected local tests, strict CLI Clippy, architecture health, locked metadata, `just ci-gates`, and independent review.
- Observe the existing Linux CI capture case before final closure. Do not launch local external warmup probes or root activation.

## Ownership

- The smoke writer owns only `native/rust/crates/ripdpi-cli/tests/packet_smoke/adaptive.rs` in this dedicated task worktree after planning is committed.
- The integrator owns task metadata, board generation, report, and integration. These writes are serialized with code work.
- Production code, dependencies, baselines, goldens, schemas, and registry remain out of scope.

## Evidence

At published main `7c8439eb841ef67a835f752e18866a01d6dfcdf4`, CI job 132904484322 failed the positive byte oracle. Original pcap and an independent tshark decode show two complete 67-byte GET requests in one segment each. The CLI used plain group 1 and recorded zero evolution successes. Immediate routing has no payload and rejects the HTTP-filtered group 0. The packet oracle is correct; this is a test configuration defect. Warmup passes `evolution_attempt: None`; the adapter discards such a fresh attempt even when emission is applied.

Fix `57d2a69185eea313671c240955659e1f8d153806` removes only the positive L7 filter and adds one comment about immediate route selection. Two local adaptive tests and seven existing byte-oracle tests passed; strict CLI Clippy, format, architecture health, locked metadata, `just ci-gates`, and task validation passed. Independent review found no actionable defect and checked all warmup feedback paths: an unheld attempt cannot provide the two checked success credits.

The natural Linux CI run 43746016759, job 132910064186, passed all 16 CLI selectors, including the adaptive case. Artifact 14174547522 retains the original pcap. An independent offline tshark decode reconstructed both exact 67-byte HTTP requests from two segments of 32 and 35 bytes per stream. The boundary is the first Host byte; raw sequences are contiguous. Positive stderr has two split executions and exactly two evolution successes. Negative capture has zero success and failure credits. Host-autolearn controls passed. This is Linux fixture V, not external compatibility E. Other CI jobs are outside this CLI acceptance and are reported separately.
