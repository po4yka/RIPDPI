---
task_id: PRX-1794036614727167
change: parse-naive-connect-with-httparse
commit_sha: f5c537e1900223994cc338a10008d07c57146a8a
local: passed
local_evidence: "The malformed HTTP/1.xyz regression failed before replacement because it opened one upstream TLS connection. Final full NaiveProxy package tests passed: 94 tests including CLI integration, zero failures, 4 existing opt-in tests ignored. Tests cover invalid request lines/headers/authorities, duplicate authentication, no upstream on rejection, real h2 padding round trips with partial and coalesced input, 700 short LF headers, the exact 16 KiB boundary and following payload, HTTP/1.0 and mixed-case CONNECT compatibility, and early close on oversized/incomplete heads. Authority errors are fixed and redacted. cargo fmt --check, all-targets Clippy with await-holding lints, locked metadata, all cargo-deny 0.19.8 checks, ci-gates, and independent correctness/cancellation reviews passed. Existing httparse 1.10.1 is reused; the lockfile adds one direct edge without new versions. Logs: /tmp/ripdpi-connect-red.log, /tmp/ripdpi-connect-tests.log, /tmp/ripdpi-connect-clippy.log, /tmp/ripdpi-connect-deny.log, /tmp/ripdpi-connect-ci.log."
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
| REQ-NAIVE-CONNECT-WITH-HTTPARSE-BEHAVIOR | PRX-1794036617291523 | Observed local checks and independent review below | Passed |
| REQ-NAIVE-CONNECT-WITH-HTTPARSE-COMPAT | PRX-1794036617805336 | Observed local checks and independent review below | Passed |

## Observed evidence

The malformed HTTP/1.xyz regression failed before replacement because it opened one upstream TLS connection. Final full NaiveProxy package tests passed: 94 tests including CLI integration, zero failures, 4 existing opt-in tests ignored. Tests cover invalid request lines/headers/authorities, duplicate authentication, no upstream on rejection, real h2 padding round trips with partial and coalesced input, 700 short LF headers, the exact 16 KiB boundary and following payload, HTTP/1.0 and mixed-case CONNECT compatibility, and early close on oversized/incomplete heads. Authority errors are fixed and redacted. cargo fmt --check, all-targets Clippy with await-holding lints, locked metadata, all cargo-deny 0.19.8 checks, ci-gates, and independent correctness/cancellation reviews passed. Existing httparse 1.10.1 is reused; the lockfile adds one direct edge without new versions. Logs: /tmp/ripdpi-connect-red.log, /tmp/ripdpi-connect-tests.log, /tmp/ripdpi-connect-clippy.log, /tmp/ripdpi-connect-deny.log, /tmp/ripdpi-connect-ci.log.

The exact implementation SHA is recorded above. Local checks do not establish remote CI, device, artifact, or deployment acceptance.
