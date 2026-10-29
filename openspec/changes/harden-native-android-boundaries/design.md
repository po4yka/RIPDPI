## Context

The audit was made at 022af2d070ef. Implementation starts at 12878ea4c13f. The user authorized five separate fix commits, integration into main, and push.

## Goals / Non-Goals

- Goal: close all five confirmed defects with tests at the owning boundary.
- Non-goal: alter OS VPN lockdown policy, third-party PT process isolation, or dependency versions.

## Decisions

- Generate session credentials for every configured relay frontend. Reuse the native RFC1929 implementation for GAS and Cloudflare wrappers. Deliver NaiveProxy local credentials through stdin and require them on both SOCKS and HTTP CONNECT. Authenticate readiness and the owned PT bridge. Raw upstream PT listeners accept caller-supplied PT arguments; they do not receive the saved fixed-target bridge configuration through this frontend contract.
- The user chose removal of legacy Shadowsocks UDP. Remove the legacy datagram codec/path and update capability callers while keeping TCP. This avoids a finite cache that later admits old ciphertext.
- SS2022 keeps current and previous authenticated sessions. Use monotonic inactivity time and strict greater-than-60-second retirement. Authenticate and validate recipient/type/timestamp before changing state.
- Import uses bounded admission and cancellable provider open. Reads must use a deadline-aware descriptor path so a silent pipe cannot retain a blocked read indefinitely. Cancellation must not block the main thread on an uncooperative provider.
- Fixed-read completion sets length outside debug assertions, including zero-length reads. Tests exercise real buffer completion in a Linux release build without requiring kernel io-uring access.

## Contracts and ownership

The portfolio Ownership section assigns isolated writers. Primary owns shared generated files, tasks, and integration. No new production dependencies, persisted migrations, JNI methods, or protobuf fields are needed. Helper stdin contract is changed together with Android launch and readiness.

## Risks / Trade-offs

- Legacy UDP removal is an intentional compatibility break; explicit rejection and capability tests prevent silent loss or downgrade.
- A provider can ignore cancellation of a Binder open; a fixed worker bound prevents process resource growth.
- Session eviction can re-enable replay; strict monotonic inactivity and boundary regressions protect retention.

## Migration Plan

Ship updated helper and caller together. Existing legacy TCP profiles remain valid; users need SS2022 for UDP. Rollback must preserve rejection of unauthenticated helper traffic.

## Validation

Run owning crate/module regression suites first, then affected Kotlin staticAnalysis and Rust clippy. Linux release completion tests do not skip on restricted io-uring kernels. Before integration run architecture health and locked Cargo metadata. Independent pr-reviewer and async/unsafe specialists review matching changes. Record CI, device, APK and deployment evidence separately; do not infer them from local tests.
