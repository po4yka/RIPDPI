## Context

The old protect server deletes a shared filesystem socket after accept-thread and handler waits. A new session can bind that same path while the old stop is in progress. Session cleanup also clears the active path without an owner check and unregisters the latest process-global JNI tokens. Native generation checks do not protect a newer token when Kotlin passes that newer token from an older lifecycle.

The c2a runtime proves overlapping listener and old-stop windows, TCP empty EOF, UDP timeout, and absent restart TCP fixture receipts. It does not capture the exact unlink or errno. Deterministic reproduction and fresh runtime evidence must separate the source mechanism from that historical inference.

## Goals / Non-Goals

- Goal: each session owns its endpoint, active advertisement, and native registration lease; old cleanup preserves a new owner.
- Goal: failed and repeated operations close only owned resources and remain fail closed.
- Non-goal: change JNI signatures, ACK bytes, routing policy, provider timeouts, or native socket selection.

## Decisions

- Give each service session a unique short filesystem socket basename, allocated once for that server. Keep the full pathname within the Android Unix socket bound. Startup must not delete an existing endpoint owned by another operation. Teardown removes only a successfully bound endpoint owned by the server.
- Keep start/stop on the same server serialized and idempotent. Close every allocated bind/listen/server resource after partial startup failure. Close workers with existing bounded waits.
- Return an internal owner lease for path advertisement. Conditional withdrawal checks the captured owner; direct protection from a replaced lease remains fail closed.
- Return and retain an internal per-session group of native generation tokens. Cleanup takes that group, not the latest process-global tokens. Preserve registration ordering, rollback of partial registration, and failed release tokens for bounded caller retry.
- Inject the existing @Inject/@Singleton ConnectionPolicyRuntimeContextAssembler into DefaultConnectionPolicyResolver instead of duplicating its sole-construction dependencies in that resolver constructor. Keep dependencies used by resolver preparation. The assembler is stateless apart from immutable dependencies; use the same dependency instances and explicit protection owner in constructor fixtures. This avoids new constructor and function-size violations without baseline or suppression edits. Extract the same captured native-registration callback from createShellDelegate to preserve its size bound.
- Update every protect path consumer, including ConnectionPolicyRuntimeContextAssembler, to use the current session endpoint. Native tunnel assembly already uses server.socketPath. Do not leave a hardcoded legacy pathname in proxy or root-helper runtime context.
- Reject a pathname-only inode check: check and unlink can race. A constant-path process-wide ownership lock could work but adds shared coordination and does not isolate endpoints as directly as distinct session paths.

## Contracts and ownership

- Android owns :core:service VpnProtectSocketServer, VpnServiceSessionModule, ActiveProtectSocketPathProvider, VpnNativeProtectRegistration, VpnServiceSessionLifecycle, ConnectionPolicyRuntimeContextAssembler, DefaultConnectionPolicyResolver wiring, and their tests. Existing Android E2E tests may record privacy-safe endpoint presence and failure errno if needed.
- Android also owns core/service/src/testFixtures/kotlin/com/poyka/ripdpi/services/ProtectSocketOwnershipProbe.kt, app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/ProtectSocketOwnershipInstrumentedTest.kt, and one app/build.gradle.kts androidTestImplementation(testFixtures(project(":core:service"))) line. This reuses the existing repository test fixture dependency and adds no production dependency.
- The actual probe uses production path allocation in an isolated task directory, real LocalSocket/SCM_RIGHTS, and the active VpnService.protect callback. It does not replace routing, protection, or the live VPN endpoint. Extracting the current allocator into an internal function preserves its old behavior for the actual pre-fix probe.
- The coordinator owns this task and all OpenSpec artifacts. Linux and the reviewer inspect without source writes.
- No Rust source, dependency, schema, locale, golden, or architecture baseline change is planned. No migration or serialized shared-file writer is needed.
- Filesystem address, SCM_RIGHTS transfer, and one-byte ACK remain unchanged. Native generation-token APIs remain unchanged. Protection failures close outbound connections.

## Risks / Trade-offs

- Dynamic paths can expose a stale hardcoded consumer: inspect every path assembly call and add runtime context tests.
- A unique basename can exceed Unix pathname capacity: use a short basename and check the bound in tests.
- Partial cleanup can release a newer registration: test overlapping groups, all five native slots, registration failure, failed unregister retry, and repeated withdrawal.
- Host doubles cannot prove Android LocalSocket behavior: retain deterministic ownership RED and run an actual owned emulator overlap/connect/ACK probe, then full catalog Android traffic and repeats.

## Migration Plan

No persisted schema changes are needed. Endpoints are session-local runtime resources. Rollback restores the source implementation and rebuilds artifacts; it does not rewrite user settings.

Gates: :core:service:testDebugUnitTest --rerun, staticAnalysis, architecture health, Cargo metadata --locked, manifest check, lab validate, taskctl validate, genuine Full Android preparation and complete Android profile, full Xray repeat, VM-routed Xray, Simple cancel/share, and exact published main CI. Each complete lab report must pass lab verify. Original failures and all new directories remain separate.
