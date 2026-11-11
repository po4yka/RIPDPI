---
task_id: TRN-1794383445146007
change: repair-websocket-upgrade-contracts
commit_sha: a52e3a08121fd0ac501cff3d8384df0cf97bced6
local: passed
local_evidence: 346 tests passed; owning raw-wire regressions, affected all-target clippy, workspace check, fmt, locked metadata, ci-gates, architecture check and independent reviews passed.
remote_ci: not_applicable
remote_ci_evidence: User authorized direct main push. Source a52e3a08121fd0ac501cff3d8384df0cf97bced6 is published and confirmed by ls-remote. GitHub reported PR and ci-required rule bypass and pending CodeQL; no hosted CI success is claimed.
device: not_applicable
device_evidence: Source repair of host-testable transport contracts; no physical-device or public-peer acceptance is claimed.
artifact: not_applicable
artifact_evidence: This change does not produce a release artifact.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-WS-EXTENSIONS | TRN-1794383519591425 | Independent raw-wire regression and owning suite; passed on source a52e3a0812 | passed |
| REQ-WS-CONNECTION | TRN-1794383519591425 | Independent raw-wire regression and owning suite; passed on source a52e3a0812 | passed |
| REQ-WS-ERRORS | TRN-1794383522543227 | Independent raw-wire regression and owning suite; passed on source a52e3a0812 | passed |
| REQ-WS-SINGLETONS | TRN-1794392603512440 | Independent repeated-field raw response regression; passed on source a52e3a0812 | passed |
| REQ-WS-UPGRADE | TRN-1794395621815086 | Independent repeated/combined Upgrade field raw responses; passed on source a52e3a0812 | passed |
| REQ-UPGRADE-RESPONSE | TRN-1794383523565872 | Independent raw-wire regression and owning suite; passed on source a52e3a0812 | passed |
| REQ-WS-CLOSE | TRN-1794383526870583 | Independent raw-wire regression and owning suite; passed on source a52e3a0812 | passed |
| REQ-UPGRADE-REQUEST | TRN-1794383523565872 | Independent raw-wire regression and owning suite; passed on source a52e3a0812 | passed |
| REQ-WS-PRIVACY-LIMITS | TRN-1794383530212567 | Independent raw-wire regression and owning suite; passed on source a52e3a0812 | passed |

## Observed validation

Source commit: `a52e3a08121fd0ac501cff3d8384df0cf97bced6`, rebased onto current main `032bec1fbfa0c379e75b5d4db5b18a19c1edad1e` before all final checks.

From `native/rust`, all passed:

- `cargo test -p ripdpi-ws-tunnel -p ripdpi-wireguard-ws -p ripdpi-warp-core --locked`: 335 passed; one existing manual benchmark ignored.
- `cargo test -p ripdpi-diagnostics-transport --locked ws_tls::tests`: 11 passed; 86 unrelated tests filtered.
- `bash ../../scripts/ci/cargo-guarded.sh cargo clippy --locked -p ripdpi-ws-tunnel -p ripdpi-wireguard-ws -p ripdpi-warp-core -p ripdpi-diagnostics-transport --no-deps --all-targets -- -D warnings`.
- `cargo check --workspace --all-targets --locked`.
- `cargo fmt --check` and `cargo metadata --locked --format-version 1`.

From the worktree root, all passed:

- `SCCACHE_RECACHE=1 just ci-gates`: API snapshots match; canonical Linux-owned runtime-platform snapshot skipped on Darwin. No baselines changed.
- `python3 scripts/ci/check_architecture_health.py`: 18 baseline indicators, zero new/worse/stale.
- `git diff --check` and clean source status.

Independent transport and async/cancel reviewers approved the final source. Each confirmed defect has its own functional commit. The separate packet receive limit is resource hardening, without a measured pre-fix OOM claim. Two separate fixture commits repair Darwin socket blocking and polling expectations.

Earlier attempts included a transient RustSec fetch failure, a timing fixture failure, and a missing sccache rmeta artifact. Corrected fixtures and all normal hooks passed. Only the owned temporary unicode-ident build artifacts were cleaned before the final successful CI gates; the cache failure root cause is unknown. No gate was weakened.

The source was fast-forward merged and pushed to main. `git ls-remote origin refs/heads/main` returned the source SHA above. GitHub reported existing rule bypass for the direct push; protection was not changed. Hosted CI/CodeQL remains unverified.

C: implemented and caller paths reviewed. V: host raw-wire/loopback tests only. S: selected RFC6455 v13 and RFC9110/9112 requirements checked. E: public peer compatibility not established. No physical device, root activation, real secret, Android cross-build, Autobahn, service-wide stop/resource SLA, or external transport probe was run. No new backend or driver is claimed for the internal generic/raw API.
