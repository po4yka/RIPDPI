---
task_id: RLY-1794036580419313
change: use-library-socks-secret-comparison
commit_sha: 2fb2483c25d37b88e0ee40288472fef43ec8e496
local: passed
local_evidence: "The new credential boundary table passed before replacement. Full relay-core and proxy-runtime cargo test --locked --jobs 4 -- --test-threads=1 passed with a command-owned temporary directory. The first parallel run failed in a Tor fixture with SQLite CannotOpen; read-only triage found shared cache paths in two Tor tests and a likely creation race. Serial execution keeps every test; the existing fixture race remains outside this change. cargo fmt --check, relay-core clippy --all-targets -- -D warnings, locked metadata, cargo-deny 0.19.8 all four checks, and just ci-gates passed. Lockfile adds only the relay-core edge to existing constant_time_eq 0.6.1. Independent pr-reviewer found no actionable issues. Logs: /tmp/ripdpi-socks-baseline.log, /tmp/ripdpi-socks-tests.log, /tmp/ripdpi-socks-tests-serial.log, /tmp/ripdpi-socks-clippy.log, /tmp/ripdpi-socks-deny.log, /tmp/ripdpi-socks-ci.log. Existing interoperability and transport opt-in tests remain ignored by their owning suites; no test was newly ignored."
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
| REQ-LIBRARY-SOCKS-SECRET-COMPARISON-BEHAVIOR | RLY-1794036582896469 | Observed local checks and independent review below | Passed |
| REQ-LIBRARY-SOCKS-SECRET-COMPARISON-COMPAT | RLY-1794036583340440 | Observed local checks and independent review below | Passed |

## Observed evidence

The new credential boundary table passed before replacement. Full relay-core and proxy-runtime cargo test --locked --jobs 4 -- --test-threads=1 passed with a command-owned temporary directory. The first parallel run failed in a Tor fixture with SQLite CannotOpen; read-only triage found shared cache paths in two Tor tests and a likely creation race. Serial execution keeps every test; the existing fixture race remains outside this change. cargo fmt --check, relay-core clippy --all-targets -- -D warnings, locked metadata, cargo-deny 0.19.8 all four checks, and just ci-gates passed. Lockfile adds only the relay-core edge to existing constant_time_eq 0.6.1. Independent pr-reviewer found no actionable issues. Logs: /tmp/ripdpi-socks-baseline.log, /tmp/ripdpi-socks-tests.log, /tmp/ripdpi-socks-tests-serial.log, /tmp/ripdpi-socks-clippy.log, /tmp/ripdpi-socks-deny.log, /tmp/ripdpi-socks-ci.log. Existing interoperability and transport opt-in tests remain ignored by their owning suites; no test was newly ignored.

The exact implementation SHA is recorded above. Local checks do not establish remote CI, device, artifact, or deployment acceptance.
