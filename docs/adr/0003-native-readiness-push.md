# ADR 0003: Native runtime-readiness push via one-shot JNI lifecycle callback

| | |
| --- | --- |
| **Status** | Accepted (2026-05-30) |
| **Area** | Runtime lifecycle / JNI boundary |
| **Supersedes** | The 50 ms `RuntimeReadiness` telemetry-polling loop (kept as a fallback) |
| **Gated to CI/device** | JNI symbol export + `jni-symbols.baseline` approval; on-device latency measurement; 4-ABI cdylib rebuild |

## Context

`RuntimeReadiness.awaitRuntimeReady()` (`core/engine/.../core/RuntimeReadiness.kt`) polls
native telemetry every 50 ms (`READY_POLL_INTERVAL_MS = 50L` in `RipDpiProxy.kt`;
`ReadyPollIntervalMs = 50L` in `RipDpiRelay.kt`) looking for a `runtime_ready` native
event, completing a `CompletableDeferred<Unit>` startup signal under a
`withTimeout(timeoutMillis)`. This costs up to one full 50 ms interval of latency on every
proxy/relay/warp startup, plus a periodic CPU wake.

The native runtime **already announces readiness** at three confirmed sites, all fired from
inside the blocking runtime thread *before* the accept loop starts:

- **Proxy** — `ProxyTelemetryState::mark_running()`
  (`ripdpi-android-telemetry-adapter/src/lifecycle.rs`) emits `kind="runtime_ready"` after
  the listener binds.
- **Relay** — `RelayRuntime::run()` (`ripdpi-relay-core/src/runtime.rs`) calls
  `set_running(true)` then `emit_runtime_ready(&bind_addr)` after `TcpListener::bind`.
- **Warp** — `WarpRuntime::run()` (`ripdpi-warp-core/src/runtime.rs`) stores `running=true`
  then `emit_runtime_ready(...)` after `TcpListener::bind`.

So the readiness *content* exists; only the *delivery* is a poll. This ADR is purely about
the push transport.

Terminal failure MUST win the race: if `bind` fails or the runtime exits before ready, the
wait must end promptly with a failure, not block to timeout. This is **already handled** —
the proxy's blocking `start()` returns an errno and relay/warp return codes only (no
exceptions); `startProxy`/`start`'s `finally` block calls
`startupSignal.completeExceptionally("…exited before becoming ready")` when the signal is
not yet complete.

**Toolchain facts (verified):** the `jni` crate resolves to a RIPDPI fork whose API surface
is `EnvUnowned<'_>`, `env.with_env(|env| …)`, `env.get_java_vm()`, `env.new_global_ref()`,
`Global<JObject<'static>>`, `vm.attach_current_thread(|env| …)`, and the `jni_str!`/`jni_sig!`
macros — see `ripdpi-android-vpn-protect-adapter/src/protect_callback.rs` and
`ripdpi-warp-android/src/vpn_protect.rs`. Generation-token register/unregister lives in
`ripdpi_native_protect`. Host is Darwin; host tests run via
`cargo nextest run --workspace --locked` with an in-process JVM available through the
`jni` invocation API (JDK present).

## Decision

Adopt a **one-shot `onRuntimeReady()` JNI callback** fired from the runtime thread
immediately after each readiness point, using a per-session `(JavaVM, Global<JObject<'static>>)`
listener registered at session start — a direct clone of the proven `JniProtectCallback`
(`ripdpi-android-vpn-protect-adapter`) / warp `vpn_protect.rs` pattern, including the
generation-token registry. The callback completes the Kotlin `startupSignal`, ending
`awaitRuntimeReady` without polling. The terminal-failure path is **unchanged**: the
callback simply never fires when the runtime exits before ready, and the existing
`finally`/`completeExceptionally` wins the race for proxy, relay, and warp alike.

A hand-written `ReadinessNotifier` trait (`fn on_ready(&self)`) is the seam (the trait/doc
contract is authored, not delegated). Production impl `JniReadinessCallback { vm, listener }`;
test impl `RecordingReadinessCallback` (mirrors `flow_attribution::RecordingNotifier`) for
pure-Rust host tests.

**Graceful degradation is mandatory:** the Kotlin side keeps the 50 ms poll loop as a
fallback. If the native readiness symbols are absent (e.g. an `.so` built before the export
lands) or registration fails, the wrapper falls back to polling — so every commit in the
rollout is independently safe to merge with no regression.

## Considered options

1. **A — JNI callback (CHOSEN).** Cost: +6 JNI exports and 3 `unsafe impl Send/Sync` blocks
   (one per callback struct), and one `attach_current_thread` + `call_method` per session
   startup. In exchange: zero change to the cancel-safe Kotlin wait path; zero new
   terminal-failure signalling; zero novel JNI primitives (warp already ships the identical
   attach-per-call + `GlobalRef` pattern for VPN-protect); fully host-testable on Darwin via
   the trait seam and the in-process JVM. The `vpnservice-protect-invariant` rule prefers
   fd/non-hot-path models because attach overhead matters **on the data plane**; a single
   lifecycle event per session is exactly the class `JNI_CONTRACT` permits, so the
   attach-overhead warning is satisfied (one-shot, not per-packet).
2. **B — eventfd + `jniGetReadinessFd`.** Zero `JNIEnv`/`GlobalRef` on the signal path and
   low unsafe surface — but `libc::eventfd` is Linux-only and the dev host is Darwin, so the
   Rust path is unbuildable/untestable on host without `cfg`-gates and a pipe fallback that
   double the unsafe surface across three crates and may touch `Cargo.lock` (high-risk lane).
   Worse, the Kotlin `FileInputStream.read` on `Dispatchers.IO` is **not** interruptible by
   coroutine cancellation — a cancel-correctness regression versus today's `delay(50)`.
   Rejected.
3. **C — blocking `jniAwaitReady` + Condvar latch.** Clean audit surface (no
   `GlobalRef`/attach) and a host-testable latch — but `Condvar::wait_timeout` pins a
   `Dispatchers.IO` thread for the full timeout under coroutine cancellation (not a
   cancellation point), introduces three duplicated `std::sync::Mutex` + `Condvar` latches
   (each tripping the `llm-rust-prompts` Mutex gate), and requires invasive restructuring of
   relay-core/warp-core `RuntimeState` to thread the latch into `run()`. Rejected for
   cancel-regression + structural churn.

Scores (threadSafety / cancelCorrectness / testFeasibility / auditCost / latency → total):
**A 4/5/5/3/5 = 22**, B 5/2/2/4/4 = 17, C 4/2/4/4/5 = 19.

## Consequences

The TUN worker uses a separate readiness mechanism because its JNI `start` call
already runs on `Dispatchers.IO`: a bounded native one-shot channel gates the
return from `jniStart` until `ripdpi-tunnel-core` has completed packet-loop
initialization. It adds no JNI callback or `GlobalRef`, keeps the established
TUN as a fail-closed barrier, and prevents the service from publishing
`Connected` after worker spawn but before packet forwarding is ready.
The readiness callback runs only after `setup_io_loop` has completed. If the
five-second deadline expires, cancellation is requested and a runtime-owned
blocking reaper retains the worker join and duplicated TUN fd; the JNI call
returns on time and the session can be destroyed without detaching ownership.

**Positive:** P99 readiness latency drops from ~50 ms to sub-millisecond; the periodic 50 ms
CPU wake is eliminated; the Kotlin wait stays cancel-safe and unchanged in shape; no new
terminal-failure code on any runtime; the shared poll helper is retained as a fallback, not
deleted.

**Negative:** +6 export symbols, 3 new `unsafe impl Send/Sync` (each needs a `// SAFETY:`
block matching `protect_callback.rs` and a separate `pr-reviewer` pass per the
`llm-rust-prompts` diff gate), and a **human-gated `jni-symbols.baseline` edit** for the
proxy crate. The design MUST be documented as a strict lifecycle-class event to prevent
future misuse for higher-frequency callbacks (`JNI_CONTRACT` discourages callback-per-event
Rust→Kotlin paths).

## Failure-race handling (proxy AND relay AND warp)

The callback fires **only** on success, after the readiness point. On terminal failure the
callback never fires and the existing completion path wins:

- **Proxy** — `mark_running()` is the only call site; on earlier bind/runtime failure
  `proxy_start_entry` returns errno and `startProxy`'s `finally` completes the signal
  exceptionally.
- **Relay** — `emit_runtime_ready` is reached only after `TcpListener::bind`; on `Err` from
  `run()`, `relay_start_entry` returns `2` and `RipDpiRelay.start`'s finally completes
  exceptionally.
- **Warp** — `emit_runtime_ready` is reached only after `TcpListener::bind`; on `Err` from
  `run()` the warp start path returns non-zero and the wrapper's finally completes
  exceptionally.

`CompletableDeferred.complete`/`completeExceptionally` is first-wins and a no-op thereafter,
so a late push after a timeout/cancellation is harmless, and a push that wins over the
`finally` yields ready. The narrow window (push fires, then runtime crashes before Kotlin
observes) is identical to today's poll semantics, so no new behavior is introduced.

## Thread-ownership vs `vpnservice-protect-invariant`

The signalling thread (proxy runtime thread at `mark_running`; relay/warp tokio worker at
`emit_runtime_ready`) is **not** JVM-attached. `JniReadinessCallback::on_ready` performs
exactly **one** `vm.attach_current_thread(|env| env.call_method(listener, jni_str!("onRuntimeReady"),
jni_sig!("(J)V"), [JValue::Long(handle)]))`; the attach guard detaches on drop — one attach +
one detach per session startup. This is the lifecycle class the rule permits: that rule
prefers fd/UDS for the **data-plane hot path** to avoid per-packet attach and `JNIEnv`
non-`Send` hazards; here there is no hot path and the `JNIEnv` never escapes the closure.
`JniReadinessCallback` stores only `JavaVM` (Send+Sync) + `Global<JObject<'static>>`
(keeps the listener alive, Send+Sync); `unsafe impl Send/Sync` mirrors `protect_callback.rs`.
`GlobalRef` lifecycle: created via `env.new_global_ref(listener)` in the register entry
(Kotlin thread), dropped in the generation-guarded unregister called from
`startProxy`/`start`'s finally — isomorphic to the protect generation-token pattern.
`android-vpn-lifecycle` JNI-shutdown self-deadlock does **not** apply: the callback fires at
startup (before the accept loop), is one-shot, and never calls
`runtime.block_on(runtime.shutdown_*)`; an LMK `SIGKILL` leaks the `GlobalRef` but also kills
the process, making it moot.

## Verification plan and limits

**Verifiable on host (Darwin):**

1. `cargo nextest run --workspace --locked` — unit-test `ReadinessNotifier` via
   `RecordingReadinessCallback`, asserting `on_ready` fires exactly once on the success path
   and zero times on the early-`Err` path, for the proxy/relay/warp injection sites.
2. Kotlin unit (Robolectric/JVM, fake bindings): fake registration invokes `onRuntimeReady`
   synchronously → `awaitReady` returns with no poll; fake fails-before-ready → `awaitReady`
   throws via the `finally`/`completeExceptionally` path; cancel the awaiting coroutine
   before the push → no thread pinned, unregister called in `finally`; missing-symbol →
   fallback to poll.
3. Gates: `cargo clippy --workspace --locked -- -D warnings`; `pr-reviewer` pass for the 3
   `unsafe impl Send/Sync` and the `GlobalRef`-in-`Arc` construction.

**Deferred to CI/device (NOT verifiable in the Darwin sandbox):**

1. The 4-ABI cdylib rebuild (`aarch64`/`armv7`/`i686`/`x86_64-linux-android`), 16 KiB page
   alignment, and ELF symbol-allowlist checks — Android CI matrix only.
2. Real on-device push latency (`attach_current_thread` cost on warm vs cold ART thread
   pools, scheduler wakeup) — the host JVM measures the API path, not ART/Doze/App-Standby
   behavior. The sub-ms claim is host-measured only; device P50/P99 must be captured via the
   tracing-timestamp delta.
3. Real JNI symbol resolution / `dlopen` of the new exports against `libripdpi*.so`.
4. LMK `SIGKILL` `GlobalRef`-leak-is-moot and JNI-shutdown ordering under `onDestroy` —
   require `adb shell am kill` mid-session per `android-vpn-lifecycle`.
5. The **`jni-symbols.baseline` edit is a human-gated approval** (and is `PreToolUse`-hook
   blocked for agents), so the JNI-export commit must be completed by a human reviewer.

## Cross-references

- `.claude/rules/vpnservice-protect-invariant.md` — the data-plane preference this ADR
  honors by keeping the callback a one-shot lifecycle event.
- `.claude/rules/android-vpn-lifecycle.md` — why the `GlobalRef` leak is moot under LMK.
- `.claude/rules/llm-rust-prompts.md` — diff-acceptance gate for the new `unsafe impl`s.
- `docs/architecture/JNI_CONTRACT.md` — handle lifecycle, panic containment, the
  lifecycle-event class this callback belongs to.
