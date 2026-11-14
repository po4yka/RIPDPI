---
task_id: ENG-1794677657331523
change: dpi31-http-integrity
commit_sha: null
local: required
local_evidence: Regression tests, affected crate and module checks, independent review, and source gates are pending.
remote_ci: not_applicable
remote_ci_evidence: Local repair acceptance is owned here. No hosted CI success is claimed.
device: not_applicable
device_evidence: No physical-device or emulator acceptance is owned by this data-integrity and evidence repair.
artifact: required
artifact_evidence: Local receipt compatibility and data-preservation evidence are pending.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI31-UA | ENG-1794677777812641 | Meaningful UA and body regression tests; exact commands pending | required |
| REQ-DPI31-PREFIX | ENG-1794677778638690 | Prefixed request no-op and metadata regression tests; exact commands pending | required |
| REQ-DPI31-RECEIPT | ENG-1794677779483214 | Native loopback outcome and absent/false/true receipt compatibility tests; exact commands pending | required |
| REQ-DPI31-BOUNDARY | ENG-1794677781056020 | Independent review, affected Rust and Kotlin checks, ci-gates, architecture health, locked Cargo metadata; exact commands pending | required |

## Planned checks and limits

Use Cargo with `--locked`. Verify packet data without comparing output to a copy of the mutation implementation. Verify receipt changes independently of chain disposition, including unchanged input and failure accounting. Run the corresponding Kotlin decode test if the additive diagnostics field reaches Kotlin. Regenerate API snapshots through their owning script, then run `just ci-gates`.

The final record must state exact observed commands and commit SHA. No external-network compatibility, bypass effectiveness, root activation, physical device, secret, or deployment is part of these local checks. This planning record contains no claim that implementation or acceptance has passed.
