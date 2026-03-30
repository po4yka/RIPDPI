# Platform Memory Models Reference

## Happens-Before Relation

A **happens-before** (HB) relationship between operation A and B means: the effects of A are guaranteed to be visible when B executes.

Establishing HB:

1. **Sequenced-before**: program order within a single thread
2. **Synchronizes-with**: a release store synchronizes-with an acquire load that reads its value
3. **Transitivity**: if A HB B and B HB C, then A HB C

```text
Thread 1:                    Thread 2:
x.lock() = 42;  <--------------------------------------
flag.store(true,             while(!flag.load(
  Release);      - sync-with -  Acquire));
                             assert(x.lock() == 42);  // HB guarantees this
```

## Platform Memory Models

| Platform | Default ordering | Barrier cost | Notes |
|----------|-----------------|-------------|-------|
| x86/x86-64 | TSO (total store order) | Acquire/Release free | SeqCst needs `mfence` or locked op |
| **ARM64** | **Weakly ordered** | **All barriers explicit** | **RIPDPI target; most permissive reordering** |
| POWER | Weakly ordered | Even weaker than ARM in some cases | |
| RISC-V | RVWMO | Defined per-instruction | |

ARM64 implications for RIPDPI:
- Acquire loads emit `dmb ishld`; Release stores emit `dmb ish`
- SeqCst adds full sequential constraint (expensive on ARM64)
- Bugs may hide on x86 (stronger model) but surface on ARM64
- Always test on-device or validate with Miri (`cargo +nightly miri test`)

## C++ / Rust Ordering Equivalence

Retained for cross-referencing when reading literature or C++ codebases:

| C++ | Rust | Notes |
|-----|------|-------|
| `memory_order_relaxed` | `Ordering::Relaxed` | |
| `memory_order_acquire` | `Ordering::Acquire` | |
| `memory_order_release` | `Ordering::Release` | |
| `memory_order_acq_rel` | `Ordering::AcqRel` | Only for RMW |
| `memory_order_seq_cst` | `Ordering::SeqCst` | |
| `memory_order_consume` | (use Acquire) | Consume is deprecated in practice |
| `atomic_thread_fence(acquire)` | `fence(Ordering::Acquire)` | |
| `atomic_signal_fence` | `compiler_fence(Ordering::*)` | |

## SeqCst Total Order

`Ordering::SeqCst` establishes a single total order across all SeqCst operations in all threads. Every thread observes these operations in the same order.

Use case: when correctness depends on multiple atomics being observed in a consistent global order. Rare in RIPDPI -- currently only used in test assertions (`connect.rs`).

## CAS (Compare-And-Swap) in Rust

```rust
use std::sync::atomic::{AtomicUsize, Ordering};

let val = AtomicUsize::new(0);

// Strong CAS (never spuriously fails)
match val.compare_exchange(0, 42, Ordering::AcqRel, Ordering::Relaxed) {
    Ok(prev) => { /* swapped: prev == 0 */ }
    Err(actual) => { /* failed: actual is current value */ }
}

// Weak CAS (may spuriously fail -- use in retry loops)
while val.compare_exchange_weak(0, 42, Ordering::AcqRel, Ordering::Relaxed).is_err() {}
```

CAS ordering rule: failure ordering must not be stronger than success ordering; failure ordering cannot be Release or AcqRel (it is a load-only path).

## Quick Selection Guide

```text
Counter only (e.g., statistics):
    -> Relaxed for all operations

Shutdown/cancel flag (no dependent data):
    -> Relaxed (delay is acceptable)
    -> Or Acquire/Release if promptness matters

One writer, one reader flag + dependent data:
    -> Release on store, Acquire on load

Reference count:
    -> Relaxed for addref
    -> AcqRel for release (detect zero)
    -> Acquire for optional final load

Need global ordering across multiple atomics:
    -> SeqCst on everything (but benchmark!)
```
