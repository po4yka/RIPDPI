---
task_id: DNS-1794036588840800
change: parse-native-urls-with-url-crate
commit_sha: null
local: passed
local_evidence: "Both query-only URL regressions failed before replacement: redirect lost the query and ECH included it in the host. Full apps-script-core and ech-dns tests passed: 64 and 34, zero failures or ignored tests. Tests cover relative references after host changes on one duplex connection, IPv6 and ports, fragments, invalid schemes and redaction, and DoH validation with explicit hosts and port precedence. cargo fmt --check, both-package clippy --all-targets -- -D warnings, locked metadata, cargo-deny 0.19.8 all four checks, and just ci-gates passed. Lockfile adds only two direct url edges. Independent correctness and per-await cancellation reviews found no actionable issues; additional await-holding Clippy passed. Logs: /tmp/ripdpi-url-red.log, /tmp/ripdpi-url-ech-red.log, /tmp/ripdpi-url-tests.log, /tmp/ripdpi-url-clippy.log, /tmp/ripdpi-url-deny.log, /tmp/ripdpi-url-ci.log, /tmp/ripdpi-task6-async-clippy.log."
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
| REQ-NATIVE-URLS-WITH-URL-CRATE-BEHAVIOR | DNS-1794036591381058 | Observed local checks and independent review below | Passed |
| REQ-NATIVE-URLS-WITH-URL-CRATE-COMPAT | DNS-1794036591886250 | Observed local checks and independent review below | Passed |

## Observed evidence

Both query-only URL regressions failed before replacement: redirect lost the query and ECH included it in the host. Full apps-script-core and ech-dns tests passed: 64 and 34, zero failures or ignored tests. Tests cover relative references after host changes on one duplex connection, IPv6 and ports, fragments, invalid schemes and redaction, and DoH validation with explicit hosts and port precedence. cargo fmt --check, both-package clippy --all-targets -- -D warnings, locked metadata, cargo-deny 0.19.8 all four checks, and just ci-gates passed. Lockfile adds only two direct url edges. Independent correctness and per-await cancellation reviews found no actionable issues; additional await-holding Clippy passed. Logs: /tmp/ripdpi-url-red.log, /tmp/ripdpi-url-ech-red.log, /tmp/ripdpi-url-tests.log, /tmp/ripdpi-url-clippy.log, /tmp/ripdpi-url-deny.log, /tmp/ripdpi-url-ci.log, /tmp/ripdpi-task6-async-clippy.log.

The closure commit records the implementation SHA. Local checks do not establish remote CI, device, artifact, or deployment acceptance.
