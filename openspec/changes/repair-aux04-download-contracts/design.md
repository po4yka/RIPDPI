## Context

The confirmed findings affect two delivery stacks. Retrofit owns host, geo, and priors downloads. Native Hyper HTTP/1.1 over BoringSSL owns strategy downloads and browser fallback. This change is SVC-1794755640074922.

## Goals / Non-Goals

- Goal: repair F1 through F4 with separate commits and observable regressions.
- Non-goal: new protocols, browser fingerprint parity, arbitrary provider signing, or physical-device and external-server probes.

## Decisions

- Add Streaming to success bodies and enforce limits at the OkHttp response boundary before Retrofit can buffer errors. Preserve valid bytes and close rejected responses.
- Preserve TLS authenticated closure information at the stream boundary. Reject unexpected EOF when HTTP completeness depends on closure; retain complete Content-Length and chunked bodies.
- Use the existing request-handle registry and cancellation pattern for HTTP. Keep per-call current-thread Tokio runtime ownership and use a native cancellation receiver that wakes pending I/O. Kotlin cancellation signals the handle; the operation owner releases it.
- Add nullable Content-Type to the HTTP response DTO and propagate it to the browser decoder. No persistence schema changes.

The existing jniExecute JSON entry point accepts create, execute, cancel, and release commands. Requests without an operation retain legacy execution. No JNI export or ABI is added.

## Contracts and ownership

- limits-worker: core/service download services, bounded response helper, and owning tests.
- closure-worker: tls_profile.rs, TLS transport helper, request.rs, closure fixtures.
- bridge-worker: fetch executor/DTO/lib, JNI bridge, core/engine fetch wrapper, browser response, cancellation and metadata tests. F3 precedes F4 and has a separate commit.
- Integration: task and OpenSpec, generated API/JNI snapshots through owning commands, combined review and gates. Shared changes are applied sequentially. No dependency, locale, golden, or baseline modifications.

## Risks / Trade-offs

- Native/Kotlin lifecycle races can leak a handle or cancel a newer call. Verify pre-cancel, active cancel, release, completion races, and independent requests.
- TLS EOF handling must not reject complete HTTP framing. Test clean and abrupt close with close-delimited, Content-Length, and chunked bodies.
- Error buffering must be bounded before Retrofit parseResponse. Count real source reads in loopback regressions.
- Compiler outputs need per-worktree Cargo targets and the machine build gate. Keep jobs at four or fewer and inspect free disk space.

## Migration Plan

No stored-data migration. Ship matching Kotlin and Rust. Revert each fix with its tests and contract updates if needed. Run owning Kotlin/Rust tests, JNI contract/API checks, just ci-gates, staticAnalysis, architecture health, locked metadata, and independent review before main integration. Hosted CI and Android device evidence remain distinct from local tests.
