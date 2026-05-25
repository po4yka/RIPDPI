# Design spike: Phase-16 lab matrix on real-provider SIM

Status: repository contract wired (2026-05-25); private runner operation is now a self-hosted operator responsibility guarded by fail-closed repo contracts. Tracks: [`spike-adversarial-network-harness-and-realprovider-matrix.md`](../tasks/issues/spike-adversarial-network-harness-and-realprovider-matrix.md)

## Problem

`contract-fixtures/phase16_lab_matrix.json` defines a Wi-Fi/cellular × IPv4/IPv6 × rooted/non-rooted × proxy/VPN matrix that fans out onto self-hosted `ripdpi-lab` runners. The runners today expose synthetic network environments; they do not exercise actual carrier SIM hardware on selected mobile providers. That gap is the difference between "the harness accepts a synthetic profile" and "the selected transport profile is validated on a real provider path".

## Goal

Add a real-provider lane to the existing `ripdpi-lab` runner pool so release-time confidence has a credible signal from carrier networks with materially different L7 path behavior.

## Hardware shape (proposed)

- 1 dedicated `ripdpi-lab` runner host with 3 USB-attached LTE modems, each holding a SIM from a distinct target carrier.
- The modems present as separate network namespaces on the host (`ns-mts`, `ns-megafon`, `ns-beeline` or equivalent).
- The existing matrix fanout reads a new `carrierNamespace` axis from `phase16_lab_matrix.json` and dispatches the matrix entry into the selected namespace.
- Synthetic environments stay; they continue to run on every PR. Real-provider cells gate releases only.

## Secret handling

- SIM identifiers (IMSI, carrier subscriber IDs) live in the runner's GitHub Actions environment and are not committed.
- `phase16_lab_matrix.json` references namespaces by symbolic name; the runner translates names to physical modems via a local mapping file outside the repo.
- Captured pcap from real-provider lanes is scrubbed of IMSI / TMSI before upload, using the same scrubber the existing diagnostics archive uses for session IDs.
- The repo-side runner only validates that the private `RIPDPI_PHASE16_REAL_PROVIDER_CONFIG` has version `phase16_real_provider_runner_v1`, declares the requested symbolic `carrierNamespace`, and sets `pcapScrubPolicy=required`; the prepare hook receives the namespace through `RIPDPI_PHASE16_REQUESTED_NAMESPACE` and must keep all physical SIM/modem identifiers out of logs and artifacts.

## Fallback when the runner is offline

Real-provider cells are marked `runnerRequired: real-provider` and `evidenceTier: real-provider`. They are excluded from normal matrix emission and require `--include-real-provider` even when the entry id is explicitly filtered. When the runner is unavailable:

- PR CI ignores those cells (they are not on the PR critical path).
- Release-gate CI fails closed with a `runner_unavailable` summary so releases cannot ship without the signal.
- Missing config, an undeclared namespace, a missing hook, a non-executable hook, or a failed real-provider hook writes `phase16-run.json`, `phase16-pcap-summary.json`, and non-secret hook/config metadata before exiting non-zero.

This keeps the day-to-day PR loop independent of carrier hardware availability while making the release gate honest. Release notes or sign-off may claim real-provider confidence only after manually dispatching `.github/workflows/phase16-matrix.yml` with `include_real_provider=true` and retaining the `phase16-<entry-id>` artifact that contains both `phase16-run.json` and `phase16-pcap-summary.json`.

## Matrix delta

Adds three rows to the existing matrix per axis combination that today runs on synthetic only:

| ... existing axes ... | carrierNamespace | runnerRequired |
|---|---|---|
| ... | `ns-mts` | `real-provider` |
| ... | `ns-megafon` | `real-provider` |
| ... | `ns-beeline` | `real-provider` |

`phase16_pcap_summary.py` now carries `status`, `failureMessage`, `runnerRequired`, `evidenceTier`, and `carrierNamespace` from `phase16-run.json` into `runMetadata` so archive/export work can distinguish synthetic lab evidence from real-provider evidence without inspecting workflow labels.

## What this does *not* do

- Not a substitute for the L7 adversarial emulator. The emulator produces deterministic, reproducible adversary patterns; real carriers are non-deterministic. Both signals are needed.
- Not a substitute for global geographic coverage. The initial carrier set reflects the current lab coverage target; additional regions can be added later through the same mechanism.

## Open design questions

- Do we ship modem firmware as part of the runner image, or expect the runner operator to manage modem firmware out-of-band? Out-of-band is simpler but couples release readiness to operator availability.
- Do we report real-provider failures as `blocked` (gate the release) or `flaky` (require N consecutive failures before gating)? Flaky-with- threshold is probably right given carrier-side non-determinism.
- Privacy review: even with IMSI/TMSI scrubbed, pcap from a real SIM may include uplink metadata that identifies the SIM. Pcap upload policy needs sign-off before this lane goes live.
