# Design spike: Phase-16 lab matrix on real-provider SIM

Status: design proposal (2026-05-16)
Tracks: [`spike-adversarial-network-harness-and-realprovider-matrix.md`](../tasks/issues/spike-adversarial-network-harness-and-realprovider-matrix.md)

## Problem

`contract-fixtures/phase16_lab_matrix.json` defines a Wi-Fi/cellular ×
IPv4/IPv6 × rooted/non-rooted × proxy/VPN matrix that fans out onto
self-hosted `ripdpi-lab` runners. The runners today expose synthetic
network environments; they do not exercise actual carrier SIM hardware
on RU mobile providers. That gap is the difference between "the harness
believes the desync passes" and "the desync actually works on field".

## Goal

Add a real-provider lane to the existing `ripdpi-lab` runner pool so
release-time confidence has a credible signal from carrier networks
with known DPI policy differences.

## Hardware shape (proposed)

- 1 dedicated `ripdpi-lab` runner host with 3 USB-attached LTE modems,
  each holding a SIM from a distinct major RU carrier.
- The modems present as separate network namespaces on the host
  (`ns-mts`, `ns-megafon`, `ns-beeline` or equivalent).
- The existing matrix fanout reads a new `carrier_namespace` axis from
  `phase16_lab_matrix.json` and dispatches the matrix entry into the
  selected namespace.
- Synthetic environments stay; they continue to run on every PR.
  Real-provider cells gate releases only.

## Secret handling

- SIM identifiers (IMSI, carrier subscriber IDs) live in the runner's
  GitHub Actions environment and are not committed.
- `phase16_lab_matrix.json` references namespaces by symbolic name; the
  runner translates names to physical modems via a local mapping file
  outside the repo.
- Captured pcap from real-provider lanes is scrubbed of IMSI / TMSI
  before upload, using the same scrubber the existing diagnostics
  archive uses for session IDs.

## Fallback when the runner is offline

Real-provider cells are marked `runner_required: real-provider`. When
the runner is offline:

- PR CI ignores those cells (they are not on the PR critical path).
- Release-gate CI fails closed with a `runner_unavailable` summary so
  releases cannot ship without the signal.

This keeps the day-to-day PR loop independent of carrier hardware
availability while making the release gate honest.

## Matrix delta

Adds three rows to the existing matrix per axis combination that today
runs on synthetic only:

| ... existing axes ... | carrier_namespace | runner_required |
|---|---|---|
| ... | `ns-mts` | `real-provider` |
| ... | `ns-megafon` | `real-provider` |
| ... | `ns-beeline` | `real-provider` |

`phase16_pcap_summary.py` already understands per-row evidence; no
schema change required on the summarizer side.

## What this does *not* do

- Not a substitute for the TSPU adversarial emulator. The emulator
  produces deterministic, reproducible adversary patterns; real
  carriers are non-deterministic. Both signals are needed.
- Not a substitute for global geographic coverage. RU carriers are the
  initial target because that is the project's primary threat model.
  CIS, MENA, and PRC lanes can be added later via the same mechanism.

## Open design questions

- Do we ship modem firmware as part of the runner image, or expect the
  runner operator to manage modem firmware out-of-band? Out-of-band is
  simpler but couples release readiness to operator availability.
- Do we report real-provider failures as `blocked` (gate the release)
  or `flaky` (require N consecutive failures before gating)? Flaky-with-
  threshold is probably right given carrier-side non-determinism.
- Privacy review: even with IMSI/TMSI scrubbed, pcap from a real SIM
  may include uplink metadata that identifies the SIM. Pcap upload
  policy needs sign-off before this lane goes live.
