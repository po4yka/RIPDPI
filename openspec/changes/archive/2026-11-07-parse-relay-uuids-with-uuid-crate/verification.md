---
task_id: RLY-1794036606000501
change: parse-relay-uuids-with-uuid-crate
commit_sha: 61f6a62b637357a6513291741fdca58346bba3ff
local: passed
local_evidence: "All three handwritten parsers failed the misplaced-dash rejection regression before replacement. Full VLESS, xHTTP, Cloudflare origin, and TUIC test suites passed: 295 tests, zero failures, 8 existing opt-in tests ignored. The migrated parsers compare all 16 bytes to independent RFC vectors and preserve canonical/compact mixed-case inputs without trimming or adding URN/braced forms. TUIC production authentication remains unchanged; its real QUIC fixture proves six legacy/common forms produce the same independent nonzero AUTH bytes and exporter token. The plan was corrected to preserve this existing per-protocol compatibility. Formatting, four-package Clippy with await-holding lints, locked metadata, all cargo-deny 0.19.8 checks, ci-gates, and independent correctness and test cancellation reviews passed. The existing uuid 1.29.0 version/features are retained; lockfile changes only direct edges and removes the unused Cloudflare hex edge. Logs: /tmp/ripdpi-uuid-red.log, /tmp/ripdpi-uuid-tests.log, /tmp/ripdpi-uuid-clippy.log, /tmp/ripdpi-uuid-deny.log, /tmp/ripdpi-uuid-ci.log."
remote_ci: not_applicable
remote_ci_evidence: This scope authorizes local work and main commits; remote publication is not owned by this task.
device: not_applicable
device_evidence: No Android lifecycle, JNI, or device socket ownership change is planned. Use the child host or Robolectric gates; revisit this category if implementation expands those contracts.
artifact: not_applicable
artifact_evidence: No release APK, native binary publication, or golden blessing is requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RELAY-UUIDS-WITH-UUID-CRATE-BEHAVIOR | RLY-1794036608444534 | Observed local checks and independent review below | Passed |
| REQ-RELAY-UUIDS-WITH-UUID-CRATE-COMPAT | RLY-1794036608929239 | Observed local checks and independent review below | Passed |

## Observed evidence

All three handwritten parsers failed the misplaced-dash rejection regression before replacement. Full VLESS, xHTTP, Cloudflare origin, and TUIC test suites passed: 295 tests, zero failures, 8 existing opt-in tests ignored. The migrated parsers compare all 16 bytes to independent RFC vectors and preserve canonical/compact mixed-case inputs without trimming or adding URN/braced forms. TUIC production authentication remains unchanged; its real QUIC fixture proves six legacy/common forms produce the same independent nonzero AUTH bytes and exporter token. The plan was corrected to preserve this existing per-protocol compatibility. Formatting, four-package Clippy with await-holding lints, locked metadata, all cargo-deny 0.19.8 checks, ci-gates, and independent correctness and test cancellation reviews passed. The existing uuid 1.29.0 version/features are retained; lockfile changes only direct edges and removes the unused Cloudflare hex edge. Logs: /tmp/ripdpi-uuid-red.log, /tmp/ripdpi-uuid-tests.log, /tmp/ripdpi-uuid-clippy.log, /tmp/ripdpi-uuid-deny.log, /tmp/ripdpi-uuid-ci.log.

The exact implementation SHA is recorded above. Local checks do not establish remote CI, device, artifact, or deployment acceptance.
