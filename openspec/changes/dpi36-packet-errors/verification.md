---
task_id: RST-1794684264866031
change: dpi36-packet-errors
commit_sha: 139aeee3e2c2c2fcfa2efeef1d80180007420dfc
local: passed
local_evidence: 442 host tests, doctests, Clippy, Android test-target compilation, ci-gates and independent reviews passed.
remote_ci: required
remote_ci_evidence: Hosted CI must be checked after the authorized main push; no remote pass is claimed.
device: not_applicable
device_evidence: No device or root activation is owned by this error-handling repair; actual privileged syscall acceptance remains unverified.
artifact: not_applicable
artifact_evidence: No APK or release artifact is produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RST-CLEANUP | RST-1794684426554718 | Both executor paths reject typed and opaque unsafe RST errors; real loopback peers receive no original bytes; temporary TTL is restored | passed |
| REQ-UDP-ERROR-PARITY | RST-1794684430825895 | Existing main fix 774661065a97ad0f75bdf54116d5e639d7732cb1 retained; two targeted UDP tests and the final owning suite passed | passed |
| REQ-FRAGMENT-MD5-SCOPE | RST-1794684434053320 | MD5 documentation excludes ipfrag2; no new packet option was added | passed |
| REQ-RST-CLEANUP | RST-1794684437293221 | 442 host tests, strict Clippy, Android cfg test compilation, source gates, PR review and raw-boundary review passed | passed |

## Change boundaries

- F1: both TCP repair restoration calls run. An unsafe cleanup result remains terminal through the helper, platform adapter and both runtime executors. A proven safe refusal retains the plain-write fallback.
- F2: while this repair was in progress, main received the same UDP fix in commit `774661065a97ad0f75bdf54116d5e639d7732cb1`. The job branch was rebased onto it. The implementation and its stricter fallback guard were retained without duplication. TCP, UDP and RST now share the upstream error converter.
- F3: the MD5 section now states the actual fragment-builder scope.
- Helper response metadata is additive in protocol v4. Legacy and malformed failures remain terminal. No request fields, public signatures, versions, dependencies, lockfile, baselines or packet constructors changed in this repair. Packet serialization remains the same.

## Observed checks

The source tree is the tree at `commit_sha`. This later verification commit changes task evidence only. A test runner recorded 260 source, build and documentation input hashes before and after the final commands. All hashes matched; the final tested tree was clean. The preceding rebase retained the independent DPI-26 changes.

| Check | Observed result | Log |
|---|---|---|
| Owning nextest suite, six packages | Exit 0; 442/442 passed, 0 skipped, no retries | `/tmp/dpi36-final-tests.log` |
| Owning doctests | Exit 0; selected libraries contain 0 doctests | `/tmp/dpi36-final-doc.log` |
| Clippy, six packages, all targets, warnings denied | Exit 0; only pre-existing Cargo manifest warnings remain | `/tmp/dpi36-final-clippy.log` |
| Android aarch64 test targets for privileged-ops, runtime-platform and root-helper | Exit 0 with pinned NDK 30.0.16248370 and API 27; compile-only | `/tmp/dpi36-final-android.log` |
| `just ci-gates` | Exit 0; architecture, LoC/hotspot limits, contracts, fixture versions, diagnostics boundaries, route coverage, locales and applicable API snapshots passed | `/tmp/dpi36-final-ci-gates.log` |
| Targeted integrated UDP tests | Exit 0; 2 passed | `/tmp/dpi36-f2-integrated.log` |
| Independent PR and raw-boundary reviews | No remaining findings, including the rebased shared converter and TCP/UDP/RST decoder union | Reviewer results in the implementation session |

Exit codes, final commit and input stability are recorded in `/tmp/dpi36-final-results.json` and `/tmp/dpi36-final-inputs-{before,after}.json`.

### Regression evidence

Before the F1 production fix, the new test `fakerst_uncertain_failure_stops_both_executors_before_original_bytes` failed as expected: the runtime returned success and reported 38 committed bytes after an uncertain reset failure. The log is `/tmp/dpi36-f1-red.log`. After the fix, all five targeted fakerst tests passed. The final 442-test run includes the new regression. This is controlled error-path evidence with real host TCP peers, not privileged raw-send evidence.

An earlier UDP boundary regression also failed because the unsupported sender returned no phase marker. Main then received the independently implemented F2 fix. The local duplicate test patch was retained outside the checkout and not merged; existing upstream tests cover the behavior.

### Exact host commands

These commands ran from `/private/tmp/ripdpi-dpi36-repairs-20261114/native/rust`:

```sh
build-gate --lane ripdpi -- env CARGO_TARGET_DIR=/private/tmp/ripdpi-dpi36-repairs-20261114/target bash ../../scripts/ci/cargo-guarded.sh cargo nextest run --locked --jobs 4 --no-fail-fast -p ripdpi-privileged-ops -p ripdpi-desync-runtime -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-runtime-platform -p ripdpi-root-helper -p ripdpi-root-helper-protocol
build-gate --lane ripdpi -- env CARGO_TARGET_DIR=/private/tmp/ripdpi-dpi36-repairs-20261114/target cargo test --locked --jobs 4 -p ripdpi-privileged-ops -p ripdpi-desync-runtime -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-runtime-platform -p ripdpi-root-helper -p ripdpi-root-helper-protocol --doc
build-gate --lane ripdpi -- env CARGO_TARGET_DIR=/private/tmp/ripdpi-dpi36-repairs-20261114/target bash ../../scripts/ci/cargo-guarded.sh cargo clippy --locked --jobs 4 -p ripdpi-privileged-ops -p ripdpi-desync-runtime -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-runtime-platform -p ripdpi-root-helper -p ripdpi-root-helper-protocol --all-targets -- -D warnings
```

The Android command ran through `build-gate --lane ripdpi -- bash /tmp/dpi36-android-check.sh`. That script uses the repository compiler-filter wrappers and pinned NDK; it clears Apple host compiler flags, selects API 27 and runs:

```sh
cargo check --locked --jobs 4 --tests --target aarch64-linux-android -p ripdpi-privileged-ops -p ripdpi-runtime-platform -p ripdpi-root-helper
```

The source-gate command ran from the worktree root:

```sh
build-gate --lane ripdpi -- env CARGO_TARGET_DIR=/private/tmp/ripdpi-dpi36-repairs-20261114/target CARGO_BUILD_JOBS=4 just ci-gates
```

The latest pre-integration `git fetch origin` and `git rebase origin/main` found the job branch up to date at `139aeee3e2c2c2fcfa2efeef1d80180007420dfc` over `origin/main@0c2a87666df9d82b02b5be6391efc5ddfb0d183d`.

## Evidence limits

- C: the three confirmed audit findings are addressed. Hypotheses about cross-process fragment IDs and broader MTU policy remain outside these repairs.
- V: host error handling, real loopback original-byte delivery and refusal behavior were observed. Android/Linux-only test targets compiled successfully; those tests were not executed on Android.
- S: no new wire conformance claim is made. Packet constructors and serialization did not change.
- E: no external server, physical device, VPN protection acceptance or actual Linux TCP_REPAIR syscall-failure run was performed. Root was not activated.
- The Linux-owned runtime-platform API snapshot is skipped by the existing Darwin gate. No Linux API snapshot pass is claimed.
- Miri, a full workspace Rust test run, Gradle builds and APK packaging were not run. The changes add no unsafe block, JNI entry point or Kotlin field.
- Hosted CI remains a separate required category. The portfolio task stays in review; it is not archived or closed from local evidence alone.
