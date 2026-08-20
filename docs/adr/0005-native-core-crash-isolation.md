# ADR 0005: Native relay-core crash isolation

> Status: accepted (ratified 2026-06-05). Decision date: 2026-06-05. Recommendation: **NO-GO** for out-of-process isolation of the relay-core data plane; keep the in-process core hardened by panic sentinels and the existing supervisor.

## Context

xivpn's headline architectural feature is running Xray-core in a **separate OS process**, so a core panic or memory leak cannot crash the app and a restart cannot leak. RIPDPI instead runs its Rust relay-core **in-process via JNI**, inside the `VpnService` process that owns the TUN fd. That choice is the reason `.claude/rules/` carry heavy invariants — `vpnservice-protect-invariant.md`, `android-vpn-lifecycle.md` (LMK `SIGKILL` with no Drop, tokio shutdown self-deadlock, SIGPIPE, `JNI_OnUnload` ordering), and `llm-rust-prompts.md` (AI-generated code raises the panic/UB surface).

RIPDPI already runs some relays as supervised **external subprocesses** — `naiveproxy`, `snowflake`, `obfs4` — so the subprocess-supervision pattern partially exists, and the runtime-lifecycle/supervisor work (`SupervisorExitCause`, readiness events) gives us typed crash/exit classification for in-process runtimes too. The question this spike answers: should the panic-prone surface of the native relay-core be moved out-of-process like xivpn, and at what cost to the data path and the `VpnService.protect()` JNI callback?

Key architectural fact: RIPDPI's data plane is a **userspace smoltcp stack** driven from `ripdpi-tunnel-core` (TUN ↔ session ↔ outbound socket). Every packet crosses TUN→core→exit-socket. The relay-core is therefore on the per-packet hot path, with a throughput goal in the ~1 Gbps range.

## Options weighed

### (a) Status quo + panic sentinels (in-process)
The relay-core stays in the `VpnService` process. Panics are contained at JNI/task boundaries (`catch_unwind`), SIGPIPE is masked process-wide, and the supervisor classifies crashes via `SupervisorExitCause`. A panic/UB/OOM that escapes the sentinels takes down the `VpnService` (tunnel drops; user reconnects).

- **Data path:** zero added cost — packets never leave the process.
- **`protect()`:** unchanged; the existing UDS+SCM_RIGHTS or direct-JNI-`GlobalRef` callback delivers `protect(fd)` in-process.
- **Crash blast radius:** whole-process. Mitigated, not eliminated, by sentinels.

### (b) Full out-of-process relay-core with an IPC boundary
The relay-core runs as a supervised child process; the `VpnService` process keeps the TUN fd and brokers `protect()`.

- **Data path:** every packet crosses a process boundary in **both** directions. At ~1 Gbps this is the dominant cost — exactly the hot path you do **not** want to IPC. Shared-memory rings reduce but do not erase the copy/scheduling cost, and add significant complexity.
- **`protect()`:** deliverable — the child sends its outbound socket fd over a UDS to the `VpnService` process, which calls `protect(int)` and replies. This is **already** the preferred protect implementation in `vpnservice-protect-invariant.md` (UDS + `SCM_RIGHTS`), so the boundary is feasible. But it now runs for every outbound socket across processes.
- **LMK behavior with two processes:** the foreground `VpnService` is LMK-shielded by its persistent notification; a **child** process is *more* LMK-eligible. If the OS kills the core child under memory pressure, the data plane stops until the supervisor respawns it — trading "a crash kills everything" for "the child can be killed independently and asymmetrically." Net **availability does not clearly improve**, and Android 17's per-app memory cap (see `android-vpn-lifecycle.md`) accounts memory app-wide, so a second process does not escape the cap.
- **Complexity:** a full IPC transport, fd brokering on the hot path, two-process lifecycle/Doze/readiness coordination, and doubled tokio runtimes.

### (c) Hybrid — isolate only the highest-panic-risk crates
Keep most of the core in-process; move only the panic-prone surface out.

- The panic-prone surface is **untrusted byte parsing**: the desync engine, protocol parsers, and the smoltcp data path. But those are **also** the latency-critical path. The two sets overlap almost completely — you cannot isolate the panic risk without IPC-ing the data plane (collapsing to option (b)).
- The genuinely separable surfaces — config parsing, the diagnostics scan engine, strategy learning — are **lower** panic risk and are already partially isolated (diagnostics runs as its own scan pipeline; PT relays are already external subprocesses). So hybrid buys little new isolation where it would actually help.

## Cost summary

| Axis | (a) Status quo | (b) Out-of-process | (c) Hybrid |
|---|---|---|---|
| Per-packet data-path cost | none | high (boundary crossing both ways) | high for the isolated portion |
| `protect()` delivery | in-process, simple | UDS+SCM_RIGHTS per outbound socket | mixed |
| Crash blast radius | whole process | core child only | partial |
| LMK / Android 17 mem-cap | one process | child more kill-eligible; cap is app-wide | same |
| Implementation complexity | low (sentinels exist) | high (IPC + fd broker + 2× lifecycle) | high, with little isolation gain |

## Decision

**NO-GO** for out-of-process isolation of the relay-core data plane.

Rationale: in RIPDPI the panic-prone surface *is* the latency-critical data plane (untrusted-byte parsing on the per-packet smoltcp path), so the only isolation that would meaningfully reduce crash blast radius (option b) also IPC-es the gigabit hot path — defeating the throughput goal — while **not** clearly improving availability (an LMK-eligible child can be killed asymmetrically, and Android 17's memory cap is app-wide). Hybrid (c) cannot cleanly separate panic-risk from latency, so it degenerates toward (b) for any isolation that matters. xivpn's model fits xivpn because it shells out to an opaque Go core it does not control; RIPDPI owns its Rust core and can harden it in place.

Instead, continue investing in **option (a)**:
- Keep `catch_unwind` panic traps at every JNI export and at tokio task boundaries (per `rust-jni`).
- Keep the process-wide SIGPIPE handler and the `JNI_OnUnload`/tokio-shutdown discipline in `android-vpn-lifecycle.md`.
- Keep classifying in-process crashes via `SupervisorExitCause` and restarting the in-process runtime.
- Continue running only genuinely-external binaries (`naiveproxy`/`snowflake`/`obfs4`) as supervised subprocesses — that boundary is for foreign code, not for the owned Rust core.

## Follow-up

NO-GO ⇒ no follow-up epic; this ADR is the artifact and the decision should not be re-litigated unless a trigger below changes.

**Re-open triggers:** (1) the throughput goal is dropped such that data-path IPC cost becomes acceptable; (2) the relay-core gains a large surface of *non*-data-path, high-panic-risk code that is cleanly separable; (3) Android changes LMK/memory-cap accounting so a child process is genuinely more survivable than its parent.

## References

- `.claude/rules/vpnservice-protect-invariant.md` — the two valid `protect(fd)` implementations; UDS+SCM_RIGHTS is the cross-process-capable one.
- `.claude/rules/android-vpn-lifecycle.md` — LMK SIGKILL, tokio shutdown self-deadlock, SIGPIPE, Android 17 memory-cap accounting.
- `.claude/rules/llm-rust-prompts.md` — AI-authorship panic/UB surface that motivates the question.
- `native/rust/crates/ripdpi-tunnel-core/` — the in-process smoltcp data plane on the per-packet hot path.
- Existing supervised external PTs (`naiveproxy`, `snowflake`, `obfs4`) — the precedent subprocess boundary, scoped to foreign binaries.
