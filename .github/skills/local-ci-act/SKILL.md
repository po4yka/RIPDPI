---
name: local-ci-act
description: Running CI workflows locally with act, troubleshooting CI failures, understanding job compatibility
---

# Local CI with act

## Overview

Run GitHub Actions CI workflows locally using [act](https://github.com/nektos/act) for faster feedback loops. This avoids waiting for remote CI on push/PR cycles.

### Prerequisites

- **act**: `brew install act`
- **Docker Desktop**: Running with at least 8 GB memory allocated
- **Apple Silicon**: `.actrc` sets `--container-architecture linux/amd64` automatically

## Job Compatibility Matrix

| Job | act-compatible | Blocker | Local alternative |
|-----|:-:|---|---|
| `build` | Yes | -- | -- |
| `static-analysis` | Yes | -- | -- |
| `rust-network-e2e` | Yes | -- | -- |
| `rust-native-soak` | Yes* | Needs dispatch event payload | -- |
| `android-network-e2e` | No | Emulator needs KVM (unavailable in Docker on macOS) | `./gradlew :app:connectedDebugAndroidTest` on local emulator |
| `linux-tun-e2e` | No | TUN device + sudo in Docker is fragile | CI-only or Linux VM |
| `linux-tun-soak` | No | Same as above | CI-only or Linux VM |

*`rust-native-soak` requires a `workflow_dispatch` event payload -- the wrapper script handles this automatically.

## Quick Start

```bash
# List all jobs with compatibility info
scripts/ci/act-local.sh --list

# Run a single job
scripts/ci/act-local.sh build
scripts/ci/act-local.sh rust-network-e2e    # lightest job, good for quick checks

# Run all compatible jobs sequentially
scripts/ci/act-local.sh --all

# Dry run (verify workflow parsing without executing)
act -n -j build -W .github/workflows/ci.yml

# Run with verbose output for debugging
act -j build -W .github/workflows/ci.yml -v
```

## Docker Image Configuration

The project uses `catthehacker/ubuntu:full-latest` (~12 GB) configured in `.actrc`. This image includes:

- JDK (multiple versions including 17)
- Android SDK command-line tools
- Rust prerequisites (curl, build-essential)
- Node.js, Python, and other common CI tools

### Image Size Tradeoffs

| Image | Size | JDK | Android SDK | Rust | Use case |
|-------|------|-----|-------------|------|----------|
| `catthehacker/ubuntu:act-latest` | ~600 MB | No | No | No | Simple workflows |
| `catthehacker/ubuntu:full-latest` | ~12 GB | Yes | Yes | Partial | This project |
| Custom image | Varies | Yes | Yes | Yes | Optimized for this project |

The `full-latest` image is the best default -- it has JDK and Android SDK pre-installed. Rust toolchain and NDK are installed by workflow steps (`dtolnay/rust-toolchain` and `sdkmanager`).

## Third-Party Action Handling

| Action | Works in act | Notes |
|--------|:-:|---|
| `actions/checkout@v4` | Yes | Mounts local repo |
| `actions/setup-java@v4` | Yes | Downloads JDK |
| `dtolnay/rust-toolchain@master` | Yes | Installs Rust |
| `android-actions/setup-android@v3` | Yes | Sets up SDK |
| `gradle/actions/setup-gradle@v4` | Yes | Configures Gradle |
| `taiki-e/install-action@v2` | Yes | Installs cargo tools |
| `EmbarkStudios/cargo-deny-action@v2` | Yes | Runs cargo-deny |
| `actions/upload-artifact@v4` | No-op | Expected, artifacts not uploaded locally |
| `reactivecircus/android-emulator-runner@v2` | No | Requires KVM |

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `GITHUB_OUTPUT: No such file or directory` | Outdated act version | `brew upgrade act` |
| `sdkmanager: command not found` | Using minimal Docker image | Ensure `.actrc` uses `full-latest` |
| Gradle OOM / build killed | Docker memory too low | Docker Desktop > Settings > Resources > Memory: 8 GB+ |
| Container architecture mismatch errors | Apple Silicon without Rosetta | Ensure `.actrc` has `--container-architecture linux/amd64` |
| Schedule/dispatch jobs skipped | Wrong event type | Use `event-dispatch.json`: `act -e .github/act/event-dispatch.json` |
| `upload-artifact` warnings | Expected in act | Artifact upload is a no-op locally -- ignore |
| Rust target not found | Workflow installs targets | Wait for `rustup target add` step, or pre-install in image |
| NDK download slow | First run downloads NDK | Subsequent runs use Docker layer cache if image is kept |
| `cargo-deny` timeout | Network issues in container | Run `cargo deny check` directly: `cd native/rust && cargo deny check` |

## Event Payloads

Three event payloads in `.github/act/` match the workflow triggers:

| File | Simulates | Triggers jobs |
|------|-----------|---------------|
| `event-push.json` | Push to main | `build`, `static-analysis`, `rust-network-e2e`, `android-network-e2e` |
| `event-pr.json` | PR synchronize | Same as push (both satisfy `!= 'schedule'`) |
| `event-dispatch.json` | Manual dispatch | `rust-native-soak`, `linux-tun-e2e`, `linux-tun-soak` |

The wrapper script `scripts/ci/act-local.sh` selects the correct payload automatically based on the job's `if:` condition.

## Gotchas

- **Concurrency groups** are ignored by act -- jobs won't cancel each other locally
- **`RUNNER_TEMP`** defaults to `/tmp` in act containers; the wrapper sets it to `/tmp/runner-temp`
- **Artifact actions** (`upload-artifact`, `download-artifact`) are no-ops -- test results stay in the container filesystem
- **Secrets** are not available -- jobs that need `SIGNING_STORE_FILE` etc. will fail (not relevant for CI jobs)
- **Docker-in-Docker** is not supported -- jobs that spin up containers inside the workflow won't work
- **First run is slow** -- Docker pulls the ~12 GB image and actions are downloaded; subsequent runs are faster with `--action-offline-mode`
