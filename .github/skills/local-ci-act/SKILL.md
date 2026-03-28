---
name: local-ci-act
description: Use when running GitHub Actions workflows locally with act, checking which RIPDPI CI jobs are practical on macOS, or debugging workflow wiring before pushing
---

# Local CI with act

## Overview

Use `act` for local workflow parsing and a narrow subset of practical CI-parity jobs. Do not assume every GitHub-hosted Ubuntu lane is worth reproducing inside Docker on macOS.

### Prerequisites

- **act**: `brew install act`
- **Docker Desktop**: running with at least 8 GB memory
- **Apple Silicon**: `.actrc` already forces `linux/amd64`

## Wrapper Coverage

`scripts/ci/act-local.sh` currently wraps this subset of `ci.yml`:

| Job | Wrapped | Notes |
|-----|:-:|---|
| `build` | Yes | Best first local parity check |
| `static-analysis` | Yes | Covers Gradle plus Rust static checks |
| `rust-network-e2e` | Yes | Lightest repo-owned network lane |
| `rust-native-soak` | Yes* | Uses the dispatch payload helper |

*`rust-native-soak` requires a `workflow_dispatch` event payload, which the wrapper selects automatically.

## Workflow Surface Outside The Wrapper

These jobs exist in CI but are not curated by the wrapper yet:

| Job | Practical on macOS with act | Preferred local fallback |
|-----|:-:|---|
| `release-verification` | Likely | `./gradlew :app:assembleRelease` |
| `cli-packet-smoke` | Maybe | `bash scripts/ci/run-cli-packet-smoke.sh` |
| `rust-turmoil` | Likely | `bash scripts/ci/run-rust-turmoil-tests.sh` |
| `coverage` | Heavy | `./gradlew coverageReport` plus `bash scripts/ci/run-rust-coverage.sh` |
| `rust-criterion-bench` | Likely | `cd native/rust && cargo bench --package ripdpi-bench` |
| `android-macrobenchmark` | No | Needs emulator/KVM; use GitHub CI or a native Linux host |
| `rust-loom` | Likely | `cd native/rust && cargo test --features loom -- loom` |
| `rust-native-load` | Maybe | `bash scripts/ci/run-rust-native-load.sh` |
| `nightly-rust-coverage` | Heavy | Use the repo coverage scripts directly |
| `android-network-e2e` | No | Run on a local emulator outside Docker |
| `linux-tun-e2e` | No | Use CI or a Linux VM |
| `linux-tun-soak` | No | Use CI or a Linux VM |

`codeql.yml`, `release.yml`, and `mutation-testing.yml` are separate workflows. Use `act -W <workflow>` manually only when you specifically need local workflow parsing or action wiring validation.

## Quick Start

```bash
# Show the wrapper's supported jobs
scripts/ci/act-local.sh --list

# Run one wrapped job
scripts/ci/act-local.sh build
scripts/ci/act-local.sh rust-network-e2e

# Run all wrapped jobs
scripts/ci/act-local.sh --all

# Manual dry run for a workflow/job outside the wrapper
act -n -j release-verification -W .github/workflows/ci.yml
```

## Docker Image Configuration

The repo uses `catthehacker/ubuntu:full-latest` in `.actrc`.

Why:

- includes JDK
- includes Android SDK command-line tools
- is a better fit for Gradle plus Android builds than the minimal act image

Rust toolchains, NDK packages, and most cargo tools are still installed by workflow steps.

## Third-Party Action Handling

| Action | Works in act | Notes |
|--------|:-:|---|
| `actions/checkout@v6` | Yes | Mounts the local repo |
| `actions/setup-java@v5` | Yes | Downloads JDK |
| `dtolnay/rust-toolchain@master` | Yes | Installs Rust |
| `android-actions/setup-android@v4` | Yes | Sets up SDK paths |
| `Swatinem/rust-cache@v2` | Yes | Works well for the native workspace |
| `gradle/actions/setup-gradle@v6` | Yes | Preferred Gradle cache/setup path |
| `taiki-e/install-action@v2` | Yes | Installs cargo tools |
| `EmbarkStudios/cargo-deny-action@v2` | Yes | Runs cargo-deny |
| `actions/upload-artifact@v7` | No-op | Expected locally |
| `reactivecircus/android-emulator-runner@v2` | No | Requires KVM |
| `github/codeql-action/*` | Mixed | Fine for YAML wiring checks; low value for daily local use |

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `GITHUB_OUTPUT: No such file or directory` | Old act version | `brew upgrade act` |
| `sdkmanager: command not found` | Wrong image | Ensure `.actrc` still points to `full-latest` |
| Gradle OOM / build killed | Docker memory too low | Raise Docker Desktop memory to 8 GB or more |
| Container architecture mismatch | Apple Silicon without the repo `.actrc` settings | Keep the existing `linux/amd64` override |
| Dispatch jobs skipped | Wrong event payload | Use `.github/act/event-dispatch.json` |
| `upload-artifact` warnings | Expected | Ignore locally |
| Rust target not found | Workflow has not installed targets yet | Wait for the toolchain steps or preinstall locally |
| NDK download is slow | First-run cost | Subsequent runs are faster with cached layers |

## Event Payloads

The repo stores three payload stubs in `.github/act/`:

| File | Simulates | Used by |
|------|-----------|---------|
| `event-push.json` | push to `main` | wrapped push-style jobs |
| `event-pr.json` | PR synchronize | manual PR-shape experiments |
| `event-dispatch.json` | manual dispatch | `rust-native-soak` and other workflow-dispatch experiments |

The wrapper script selects the matching payload automatically for the jobs it knows about.

## Gotchas

- Concurrency groups are ignored locally.
- `RUNNER_TEMP` is set by the wrapper to `/tmp/runner-temp`.
- Artifact actions are no-ops; outputs stay in the container filesystem.
- Secrets are absent, so signing and release jobs are poor candidates for local act parity.
- First run is slow because Docker images and actions must be pulled.
