---
task_id: RST-1793945484896628
change: replace-rust-protobuf-codecs
commit_sha: null
local: passed
local_evidence: 17 GeoSite and 78 xhttp tests; affected-crate clippy; cargo deny; locked metadata; fmt; convention tests; ci-gates; independent code and async reviews.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: Pure message codecs and host-tested stream behavior; no Android API or socket ownership changes.
artifact: not_applicable
artifact_evidence: No release artifact is requested; generated Rust types are built and tested locally.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PB-GEOSITE | RST-1793945621523036 | cargo test -p ripdpi-geo -p ripdpi-xhttp --locked: 17 GeoSite tests passed | Passed |
| REQ-PB-HUNK | RST-1793945629561620 | Same command: 78 xhttp tests passed | Passed |
| REQ-PB-FRAMING | RST-1793945629561620 | Hunk flags, size, malformed input, empty messages, and payload-before-error tests passed | Passed |

## Integration gates

- Before the fix: four GeoSite regression tests and four xhttp regression tests failed.
- After the fix: 17 GeoSite and 78 xhttp tests passed; both doc-test targets passed.
- cargo deny --locked check passed advisories, bans, licenses, and sources.
- cargo metadata --locked --no-deps and cargo fmt --all --check passed.
- build-gate -- ./gradlew :convention:test -p build-logic --max-workers=4 passed.
- Independent code review and async cancel-safety review found no remaining correctness issue. The native task input finding was fixed.
- Affected-crate clippy passed all targets with warnings denied and await-holding lints enabled. The private generated schema module has a narrow allowance for prost-build's borrowed enum name helper.
- build-gate -- just ci-gates passed all source gates, including architecture health and the Rust API snapshot gate (the Linux-owned runtime-platform snapshot is checked only in CI).
- Hosted main CI remains pending.

The lockfile was generated with cargo update -p ripdpi-geo -p ripdpi-xhttp. Cargo added 17 packages and normalized 12 existing transitive dependency selections without changing existing package versions.

The separate fuzz lockfile includes prost and prost-derive for its ripdpi-xhttp dependency.
