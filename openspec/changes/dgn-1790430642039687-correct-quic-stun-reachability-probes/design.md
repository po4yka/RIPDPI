## Context

The suite replaces the bundled Cloudflare QUIC probe with a 125-byte synthetic fixture even when the native builder can provide a valid 1200-byte packet. The production factory also falls back to synthetic bytes after a native failure. The STUN probe checks only UDP response length. See `proposal.md` for user impact.

## Goals / Non-Goals

- Goal: Base QUIC and STUN reachability verdicts on valid local packets and matching responses.
- Non-goal: Change native packet construction, the full DPI classifier, or VPN routing policy.

## Decisions

- Remove the fixture override from production suite wiring. Keep fixture loading and the synthetic builder available for tests only; the production factory fails when native generation fails. The suite already maps probe exceptions to a failed row.
- Check STUN binding success type, declared message length, magic cookie, matching 12-byte transaction ID, and source address/port. Return a failed trace for an invalid datagram. Keep the existing timeout result.
- Use focused JUnit regression tests with fake sockets and packet factories. They do not require public network access.

## Contracts and ownership

- `:core:diagnostics` owns `dpi/QuicH3FingerprintProbe.kt`, `dpich/PluggableTransportReachabilityProbe.kt`, and their tests. `:app` owns only `activities/DiagnosticsDpiSuiteExecutionSupport.kt` wiring.
- The Rust `ripdpi-android-platform-adapter` and `ripdpi-packets` crates already produce valid native packets; no Rust change is planned.
- No JNI, protobuf, storage, configuration, or serialized shared file changes. Other writers retain diagnostics finalization, export, and RKN files.

## Risks / Trade-offs

- A missing native library now produces an explicit failed probe instead of a misleading blocked verdict. Unit tests must inject packet factories; device behavior still needs device validation.
- STUN servers that return malformed or unrelated datagrams now appear unavailable, which is the intended fail-closed behavior.

## Migration Plan

No data migration or compatibility break is required. Each fix is a separate reversible commit. Validate with focused `:core:diagnostics:testDebugUnitTest` tests, then the module suite and `:app:compileGithubFullDebugKotlin` if local dependencies allow. Do not infer device or hosted CI proof from local tests.
