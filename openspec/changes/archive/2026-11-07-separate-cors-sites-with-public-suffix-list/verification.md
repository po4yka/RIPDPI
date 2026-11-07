---
task_id: RLY-1794036597359685
change: separate-cors-sites-with-public-suffix-list
commit_sha: 873d4a3457a164fef502096e62a2f7ab07b5328a
local: passed
local_evidence: "The old private-tenant wire regression returned allow-credentials true for alice.github.io to bob.github.io. A review found embedded HTAB in the scheme was normalized by url; its wire regression also failed before the whole-Origin whitespace/control guard. Final full apps-script-core tests passed: 68 tests, no failures or ignores. Existing same-site and framing tests remain; new tests cover private tenants, scheme/effective ports, IP/localhost, absolute targets, and malformed/duplicate/non-ASCII Origins. Final fmt, Clippy with all-targets and three await-holding lints, locked metadata, all cargo-deny 0.19.8 checks, ci-gates, and independent correctness/cancellation reviews passed. User approval covers psl 2.1.248 and policy; exact reviewed psl/types checksums match Cargo.lock. Both arm64-v8a android-jni-dev owner builds passed. Normalized stripped relay ELF: 37004000 to 38222616 bytes, +1218616 (+3.29%), whole-task delta. Release/APK size and the full multi-ABI size gate were not checked; no baseline was changed. Logs: /tmp/ripdpi-psl-red.log, /tmp/ripdpi-psl-origin-red.log, /tmp/ripdpi-psl-tests-final.log, /tmp/ripdpi-psl-clippy-final.log, /tmp/ripdpi-psl-deny.log, /tmp/ripdpi-psl-ci-final.log, /tmp/ripdpi-psl-native-before.log, /tmp/ripdpi-psl-native-final.log. Measurement hashes and source digest are recorded in design.md."
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
| REQ-SEPARATE-CORS-SITES-WITH-PUBLIC-SUFFIX-LIST-BEHAVIOR | RLY-1794036599956082 | Observed local checks and independent review below | Passed |
| REQ-SEPARATE-CORS-SITES-WITH-PUBLIC-SUFFIX-LIST-COMPAT | RLY-1794036600424278 | Observed local checks and independent review below | Passed |

## Observed evidence

The old private-tenant wire regression returned allow-credentials true for alice.github.io to bob.github.io. A review found embedded HTAB in the scheme was normalized by url; its wire regression also failed before the whole-Origin whitespace/control guard. Final full apps-script-core tests passed: 68 tests, no failures or ignores. Existing same-site and framing tests remain; new tests cover private tenants, scheme/effective ports, IP/localhost, absolute targets, and malformed/duplicate/non-ASCII Origins. Final fmt, Clippy with all-targets and three await-holding lints, locked metadata, all cargo-deny 0.19.8 checks, ci-gates, and independent correctness/cancellation reviews passed. User approval covers psl 2.1.248 and policy; exact reviewed psl/types checksums match Cargo.lock. Both arm64-v8a android-jni-dev owner builds passed. Normalized stripped relay ELF: 37004000 to 38222616 bytes, +1218616 (+3.29%), whole-task delta. Release/APK size and the full multi-ABI size gate were not checked; no baseline was changed. Logs: /tmp/ripdpi-psl-red.log, /tmp/ripdpi-psl-origin-red.log, /tmp/ripdpi-psl-tests-final.log, /tmp/ripdpi-psl-clippy-final.log, /tmp/ripdpi-psl-deny.log, /tmp/ripdpi-psl-ci-final.log, /tmp/ripdpi-psl-native-before.log, /tmp/ripdpi-psl-native-final.log. Measurement hashes and source digest are recorded in design.md.

The exact implementation SHA is recorded above. Local checks do not establish remote CI, device, artifact, or deployment acceptance.
