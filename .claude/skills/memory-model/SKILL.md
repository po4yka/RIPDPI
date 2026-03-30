---
name: memory-model
description: Rust memory model skill for concurrent programming on ARM64 Android. Use when understanding memory ordering, writing lock-free code, using Rust atomics, diagnosing data races, or selecting the correct memory order for atomic operations. Activates on queries about memory ordering, acquire-release, seq_cst, relaxed atomics, happens-before, memory barriers, or Rust atomic ordering.
---

# Memory Model

## Purpose

Guide agents through Rust memory ordering on ARM64 Android: orderings, the happens-before relation, atomic operations, fences, and patterns used in the RIPDPI codebase.

## Triggers

- "What memory order should I use for my atomic operation?"
- "What is the difference between acquire-release and seq_cst?"
- "What is acquire/release in Rust atomics?"
- "Is this atomic ordering correct for ARM64?"

## RIPDPI project context

Target: ARM64 Android (weakly ordered -- all barriers needed explicitly).
No C++ source code. All concurrency is in Rust crates under `native/rust/`.

### Crates using atomics

| Crate | File | Types | Orderings | Pattern |
|-------|------|-------|-----------|---------|
| ripdpi-cli | telemetry.rs | AtomicU64 | Relaxed | Statistics counters |
| ripdpi-ws-tunnel | relay.rs | AtomicBool | Release/Acquire | Shutdown flag (cross-thread signal) |
| ripdpi-ws-tunnel | connect.rs | AtomicBool | SeqCst | Test-only assertion flag |
| ripdpi-monitor | engine.rs | AtomicBool | (needs audit) | Cancel flag |
| ripdpi-dns-resolver | tests.rs | AtomicUsize | Relaxed | Test call counters |
| local-network-fixture | http/socks/echo.rs | AtomicBool | Relaxed | Stop flags (poll loops) |

### Common patterns in this codebase

**1. Statistics counter (Relaxed)** -- used in telemetry:
```rust
// Correct: counters only need atomicity, not ordering.
self.total_sessions.fetch_add(1, Ordering::Relaxed);
let sessions = self.total_sessions.load(Ordering::Relaxed);
```

**2. Shutdown/cancel flag (Release/Acquire)** -- used in ws-tunnel relay:
```rust
// Signal side: Release ensures prior work is visible.
shutdown.store(true, Ordering::Release);

// Poll side: Acquire ensures we see work done before the signal.
if shutdown.load(Ordering::Acquire) { break; }
```

**3. Stop flag in poll loop (Relaxed)** -- used in test fixtures:
```rust
// Acceptable when the flag only controls loop termination
// and no other data needs to be synchronized.
while !stop.load(Ordering::Relaxed) {
    // accept connections ...
}
```

Note: fixture stop flags use Relaxed because they do not publish/subscribe
any shared data -- they only signal "stop looping." On ARM64 this may
delay observation by a few iterations, which is acceptable for test teardown.

## Workflow

### 1. Memory ordering overview

```text
Ordering strength (weakest to strongest):
Relaxed < Release/Acquire < AcqRel < SeqCst

Stronger ordering = more synchronization = more correct, but slower
Weaker ordering  = fewer barriers = faster, but needs careful analysis
```

### 2. Choosing the right ordering

```text
Use case?
+-- Counter (just needs atomicity, order irrelevant)    -> Relaxed
+-- Shutdown/cancel flag (no dependent data)            -> Relaxed (or Acquire if data depends on it)
+-- Publish data from one thread to another             -> Release (store), Acquire (load)
+-- Reference counting (decrement + final check)        -> Relaxed (inc), AcqRel (dec), Acquire (zero check)
+-- Mutual exclusion / mutex implementation             -> AcqRel / SeqCst
+-- Need global ordering across multiple atomics        -> SeqCst (but benchmark!)
```

### 3. Memory orderings reference

| Order | Rust | What it means |
|-------|------|--------------|
| Relaxed | `Ordering::Relaxed` | No ordering guarantee; just atomicity |
| Acquire | `Ordering::Acquire` | This load sees all writes before the matching release |
| Release | `Ordering::Release` | All writes before this store are visible to acquire |
| AcqRel | `Ordering::AcqRel` | Both acquire and release on RMW ops |
| SeqCst | `Ordering::SeqCst` | Total order across all seq_cst operations |

### 4. Valid orderings by operation type

| Operation type | Valid orderings |
|----------------|----------------|
| Atomic load | Relaxed, Acquire, SeqCst |
| Atomic store | Relaxed, Release, SeqCst |
| Read-Modify-Write (fetch_add, CAS) | All orderings |
| Fence | Acquire, Release, AcqRel, SeqCst |

### 5. Publish/subscribe pattern (idiomatic Rust)

```rust
use std::sync::atomic::{AtomicBool, Ordering, fence};
use std::sync::Arc;

struct SharedState {
    ready: AtomicBool,
    data: std::sync::Mutex<Option<u64>>,  // Mutex guards non-atomic data
}

// Publisher: write data, then signal readiness.
let state = Arc::new(SharedState {
    ready: AtomicBool::new(false),
    data: std::sync::Mutex::new(None),
});
*state.data.lock().unwrap() = Some(42);
state.ready.store(true, Ordering::Release);

// Subscriber: wait for signal, then read data.
while !state.ready.load(Ordering::Acquire) {
    std::hint::spin_loop();
}
let val = state.data.lock().unwrap().unwrap(); // Guaranteed to see 42
```

### 6. Common mistakes

| Mistake | Fix |
|---------|-----|
| Using Relaxed for publish/subscribe | Use Release on store, Acquire on load |
| Using SeqCst everywhere | Profile first; use weakest correct ordering |
| Using `unsafe` mutable statics for shared data | Use Mutex, RwLock, or atomics |
| Assuming ARM64 behaves like x86 TSO | ARM64 is weakly ordered; test on-device |

### 7. ARM64 considerations

ARM64 (this project's target) is weakly ordered. Key implications:
- Relaxed loads/stores have no barrier cost but provide no ordering
- Acquire loads emit a `dmb ish` barrier; Release stores emit `dmb ish`
- SeqCst emits `dmb ish` + sequential constraint (expensive)
- x86 tests may pass with incorrect orderings that fail on ARM64
- Always validate concurrent code on ARM64 device or with Miri

For cross-language equivalences and platform details, see [references/platform-memory-models.md](references/platform-memory-models.md).

## Related skills

- Use `skills/rust-sanitizers-miri/` -- Miri detects Rust memory ordering violations
- Use `skills/rust-unsafe/` -- understanding unsafe blocks around atomic patterns
- Use `skills/rust-async-internals/` -- async task scheduling and atomics interaction
