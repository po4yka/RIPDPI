---
id: CIC-1794577079488154
title: Update Rust to 1.100.0 and refresh project dependencies
kind: chore
status: review
area: ci
priority: high
owner: Project maintainer
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-13
updated: 2026-11-13
spec_reason: dependency-only
---

## Goal

Build and test RIPDPI with Rust 1.100.0 and current stable dependency releases.
Preserve local native patches, dependency verification, and all quality gates.
The owner requested implementation, commit, and push directly on `main`.

## Acceptance criteria

- Rust toolchain, CI pins, and Clippy policy use 1.100.0.
- Dependency manifests and generated lockfiles use current stable releases where
  the project can preserve its existing contracts. Record any unresolved upgrade.
- Rust tests, Clippy, dependency policy, Miri, Android builds and unit tests,
  static analysis, and repository source gates pass, or have recorded failures.
- Commit the verified result on `main` and confirm the remote commit.

## Ownership

The main agent owns all writes, including Cargo.lock, the Gradle version catalog,
native vendor sources, workflow pins, and generated task assets. Audit agents
inspect independent dependency groups without changing files.

## Implementation

- Pin Rust 1.100.0 in the workspace, lint policy, and CI. Update Cargo lockfiles
  and the patched BoringSSL 5.5.0 and Quinn-proto 0.11.19 sources.
- Update Gradle to 9.9.1, Android NDK to 30.0.16248370, CMake to 4.1.2,
  compile SDK to 37.2, and the changed Android library versions in the catalog.
- Update Go, npm, Bun, Python, native source, Docker image, and CI tool pins.
  Regenerate lockfiles with each owning package manager.
- Migrate libXray v26.11.9 under `OUT-1794578142905704`. Preserve the protected
  dialer, managed instance, and authenticated UDP session contracts.
- Give the Android NDK toolchain sole ownership of CMake compilers. Support
  Cargo 1.100's new build-script directory layout when removing stale caches.
- Install shutdown handlers before publishing the daemon PID file. The final
  workspace test run exposed an existing SIGTERM race in the old order.

## Retained security pin

Hickory remains at its reviewed Git revision `ac6cc706` because stable 0.26.3
does not include the required RDATA bounds fix. Do not replace that revision
with the older stable source. Existing security policy and advisory waivers
are unchanged. The notify CC0 license exception follows 8.2.2, whose license
text matches the previous 8.2.0 release.

## Observed checks

- `cargo nextest run --workspace --locked`: 7628 passed, 63 skipped by the
  existing suite configuration. Nextest marked the cancelled Tor bootstrap
  retry test as leaky; the command returned success.
- The existing daemon SIGTERM regression passed 50 consecutive stress runs.
- Workspace Clippy with `--all-targets -- -D warnings` passed on Rust 1.100.0.
  `cargo deny --locked check advisories bans licenses sources` passed.
- Registry audit: 151 changed package versions matched registry and archive
  checksums; none was yanked. New package identities and compile-time entry
  points were inspected. Audited Cargo.lock SHA-256:
  `3fb7ee061fea2581e4b3e5c2f1874edc9bed03020bc12ddc6251614527ae1ad0`.
- Build-logic convention tests and Android arm64 Rust/nfqws2 builds passed.
  A second BoringSSL configure retained the marked CMake cache, NDK 30 Linux
  compiler, and Android API 27. It did not delete the cache or switch to Darwin.
- `just harness-check`, README selector checks, task validation, and strict
  OpenSpec validation passed.
- `just ci-gates` passed, including architecture and cross-language contract
  checks, locale parity, translation export, and Rust API snapshots. The
  Linux-owned runtime-platform snapshot is not evaluated on Darwin.
- Nightly fuzz smoke passed all 18 targets with a one-second time limit per
  target. Its five TLS input regression tests also passed. This is a smoke
  check, not a sustained fuzz campaign.
- Workspace doctests passed: two passed and two existing examples ignored.
  The repository Miri script passed: 34 passed, one existing live-hook test
  ignored, and 203 tests outside the selected filters. This is the existing
  unsafe-boundary suite, not full-workspace Miri coverage. The live BoringSSL
  hook passed the ordinary native test run.
- Strict Clippy for `ripdpi-proxy-runtime --all-targets` passed again after
  the signal-handler ordering fix.
- Rust native libraries and nfqws2 passed the NDK 30 build for armeabi-v7a,
  arm64-v8a, x86, and x86_64. `verify_native_elfs.py` passed for all four ABI
  output directories, including required dependencies, exports, and page alignment.
- The combined Python artifact, CI pin, Reality vector, native-cache, and task
  layout tests passed: 53 tests.
- The real linked GithubFullDebug build, library/app unit tests, and
  `staticAnalysis` passed. Thirteen Android unit-test tasks report 9876 tests,
  zero failures/errors/skips; the app accounts for 3490 tests.
- Catalog and service regressions verify canonical Go pseudo-versions without
  admitting pre-fix ancestors. The refused-port test reserves the upstream port
  until the bridge binds its own listener, then closes it before the request.
- After the final instrumentation fixture update, app Full/Simple lint and
  ktlint passed again. Real Xray JNI emulator acceptance passed both methods
  once with no skips on API 37 arm64, 4096-byte pages. The test explicitly
  allows its owned echo endpoint under the new upstream finalrule policy.

Imported zapret2 sources remain byte-identical to the source lock. In particular,
upstream `lua/zapret-obfs.lua` contains one whitespace-only line that
`git diff --check` reports. Removing it would break the source checksum.

## Validation boundary

The all-flavor `assembleDebug` gate cannot complete without the local embedded
relay bundle used by the Simple flavor. Validate GithubFull with real native
artifacts separately. Local host tests and artifact checks do not prove
physical-device or external-network acceptance.
