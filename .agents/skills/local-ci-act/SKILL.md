---
name: local-ci-act
description: Local GitHub Actions checks with act and workflow wiring triage on macOS.
---

# Local CI with act

## Overview

Use `act` for local workflow parsing and a narrow subset of practical CI-parity jobs. Do not assume every GitHub-hosted Ubuntu lane is worth reproducing inside Docker on macOS.

### Prerequisites

- **act**: `brew install act`
- **Docker Desktop**: running with at least 8 GB memory
- **Apple Silicon**: `.actrc` already forces `linux/amd64`

## Wrapper Coverage

`scripts/ci/act-local.sh --list` is the source of truth for currently wrapped,
native-fallback, and intentionally skipped lanes. Do not copy that changing
matrix into this skill.

## Workflow Surface Outside The Wrapper

These jobs exist in CI but are not curated by the wrapper yet:

| Job | Practical on macOS with act | Preferred local fallback |
|-----|:-:|---|
| `release-verification` | Likely | use the flavor-qualified release tasks from the current workflow |
| `cli-packet-smoke` | Maybe | `bash scripts/ci/run-cli-packet-smoke.sh` |
| `rust-turmoil` | Likely | `bash scripts/ci/run-rust-turmoil-tests.sh` |
| `coverage` | Heavy | `./gradlew coverageReport` plus the CI-scoped `bash scripts/ci/run-rust-coverage.sh` |
| `rust-criterion-bench` | Likely | `cd native/rust && cargo bench --locked --package ripdpi-bench` |
| `android-macrobenchmark` | No | Needs emulator/KVM; use GitHub CI or a native Linux host |
| `android-instrumented-tests` | No | GMD instrumented-test matrix; needs emulator/KVM -- use GitHub CI |
| `rust-loom` | Likely | `cd native/rust && cargo test --locked --features loom -- loom` |
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

## Third-party action handling

Run the exact action SHAs and versions committed in the current workflow. Do
not maintain a tag-based compatibility table here. Artifact upload is normally
a local no-op, emulator actions still require host virtualization, and CodeQL
actions have limited value in daily local runs.

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
