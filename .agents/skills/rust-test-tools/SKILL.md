---
name: rust-test-tools
description: Dynamic check toolkit beyond cargo test --locked — cargo-careful (Miri fallback for FFI), loom (concurrency model checker), proptest (property tests), cargo-fuzz (libFuzzer), cargo-mutants (mutation testing). Use when authoring tests for unsafe code, custom atomics/locks, packet parsers, or before promoting an AI-generated module past basic test coverage.
---

# Rust Test Tools -- RIPDPI

## Purpose

`cargo test --locked` and `cargo nextest run --locked` are necessary but insufficient for the failure modes RIPDPI cares about: UB in `unsafe`, data races in lock-free atomics, missing edge cases in packet parsers, and behavioral changes that pass naive tests but break under exhaustive exploration. This skill catalogs the dynamic toolkit beyond Miri and tests when to reach for each tool.

## Tool selection decision tree

```
Is there `unsafe` in the change?
├── FFI / inline-asm / syscalls / JNI?
│   └── YES → cargo-careful + sanitizers (ASan/TSan/MSan).
│             Miri can't model FFI. See `rust-sanitizers-miri` for ASan/TSan invocations.
└── Pure-Rust unsafe (raw pointers, transmute, mem::* tricks)?
    └── YES → Miri (primary) + cargo-careful (cheaper continuous check).
              Use `MIRIFLAGS="-Zmiri-tree-borrows -Zmiri-strict-provenance"`.

Is the change a custom synchronization primitive (atomic-based lock, lock-free queue, publish/subscribe flag)?
├── YES → loom with `cfg(loom)` test. Standard Mutex/RwLock does NOT need loom.

Is the change a parser, decoder, or any function taking untrusted bytes?
├── YES → proptest (input-space coverage) + cargo-fuzz (corpus-driven OOM/panic/UB hunting).

Is the change a refactor or rewrite of well-tested logic?
├── YES → cargo-mutants on the changed file. Mutation score < 80% means tests
          do not actually constrain behavior — add more before merging.

None of the above?
└── cargo nextest run --locked + standard tests are sufficient.
```

## cargo-careful — the Miri fallback

Miri is the gold standard for UB detection in pure Rust, but it runs 50–400× slower than tests and refuses FFI. `cargo-careful` rebuilds `std` with extra debug assertions (alignment checks, initialization tracking, etc.) and runs your tests against that hardened `std`. ~2–3× slowdown vs normal tests; catches a substantial subset of what Miri would find.

```bash
# Install once.
cargo install cargo-careful

# Run for crates with unsafe + FFI where Miri is unavailable.
cd native/rust
cargo +nightly careful test -p ripdpi-tunnel-android --no-fail-fast
cargo +nightly careful test -p ripdpi-proxy-runtime --no-fail-fast
```

Use when:
- The crate has FFI / JNI / libc and Miri's `-Zmiri-disable-isolation` is not enough.
- You want CI coverage on every PR without paying Miri's 50–400× cost.
- A test reproduces an Android-only crash and you want a host-runnable diagnosis path.

## loom — the concurrency model checker

`loom` exhaustively explores thread interleavings for code under `#[cfg(loom)]`. It is a model checker, not a stress test — it proves absence of races within the bounded interleaving set, where stress tests only fail to find one in a finite run.

Apply to:
- Custom atomic-based publish/subscribe flags (e.g., the `ripdpi-monitor-engine` scan cancellation path, the `ripdpi-ws-tunnel::relay.rs` shutdown flag).
- Any new lock-free data structure or hand-rolled spinlock.
- Code that uses `Ordering::Relaxed` on a publish/subscribe pair (the `memory-model` skill enumerates known sites).

```rust
// src/lib.rs — gate the real and loom impls of the synchronization primitive.
#[cfg(loom)]
use loom::sync::atomic::{AtomicBool, Ordering};
#[cfg(not(loom))]
use std::sync::atomic::{AtomicBool, Ordering};

// tests/loom_shutdown.rs
#[cfg(loom)]
#[test]
fn shutdown_flag_publishes_to_reader() {
    loom::model(|| {
        let flag = loom::sync::Arc::new(AtomicBool::new(false));
        let f2 = flag.clone();
        let writer = loom::thread::spawn(move || {
            f2.store(true, Ordering::Release);
        });
        let reader = loom::thread::spawn(move || {
            while !flag.load(Ordering::Acquire) {
                loom::thread::yield_now();
            }
        });
        writer.join().unwrap();
        reader.join().unwrap();
    });
}
```

Run:

```bash
cd native/rust
RUSTFLAGS="--cfg loom" cargo test --locked --release --test loom_shutdown
# Bound the search space if loom takes too long:
LOOM_MAX_PREEMPTIONS=3 RUSTFLAGS="--cfg loom" cargo test --locked --release --test loom_shutdown
```

Cost: exponential in atomic count and preemption bound. Keep loom tests small (one primitive at a time). Pair with the `memory-model` skill — every Relaxed publish/subscribe site without a loom test is a tracking debt.

## proptest — input-space property testing

For any function that takes "bytes" or "config" and produces a parsed/validated output, write a `proptest` strategy that generates the input shape and asserts invariants. Catches edge cases tests miss: length-zero inputs, all-zero/all-FF inputs, near-overflow lengths, malformed framing.

```rust
use proptest::prelude::*;

proptest! {
    #[test]
    fn parse_then_serialize_roundtrips(buf in prop::collection::vec(any::<u8>(), 0..4096)) {
        match Header::parse(&buf) {
            Ok(hdr) => {
                let mut out = Vec::new();
                hdr.write_to(&mut out);
                prop_assert_eq!(&out, &buf[..hdr.len()]);
            }
            Err(_) => {} // parse errors are fine; just don't panic or UB.
        }
    }

    #[test]
    fn parse_never_panics(buf in prop::collection::vec(any::<u8>(), 0..1024)) {
        let _ = Header::parse(&buf); // must not panic, must not UB.
    }
}
```

Apply to: `ripdpi-packets`, `ripdpi-vless`, `ripdpi-xhttp`, `ripdpi-masque`, `ripdpi-ipfrag`, `ripdpi-desync`, `ripdpi-config`. Any AI-generated parser without a proptest is incomplete.

## cargo-fuzz — corpus-driven fuzzing

`proptest` finds bugs reachable from a strategy; fuzzing finds bugs reachable from a corpus + coverage feedback. Reaches bugs proptest misses, especially in protocol decoders.

```bash
# One-time setup per crate.
cd native/rust/crates/ripdpi-vless
cargo fuzz init
cargo fuzz add parse_header

# fuzz/fuzz_targets/parse_header.rs
# #![no_main]
# use libfuzzer_sys::fuzz_target;
# fuzz_target!(|data: &[u8]| {
#     let _ = ripdpi_vless::wire::parse_header(data);
# });

# Run for a fixed wall-clock budget.
cargo +nightly fuzz run parse_header -- -max_total_time=600

# Reproduce a crash.
cargo +nightly fuzz run parse_header fuzz/artifacts/parse_header/crash-<id>
```

Run nightly or weekly in CI. Crashes should reduce to a minimized regression test added under `tests/regressions/`.

## cargo-mutants — mutation testing

`cargo-mutants` modifies your source code (replaces a method body with `Default::default()`, flips a comparison, etc.) and reruns tests. If tests still pass, the mutation "survived" — meaning your tests do not actually constrain the original behavior. A mutation score < 80% is a strong signal that the test suite is rubber-stamping.

```bash
cargo install cargo-mutants
cd native/rust
cargo mutants -p ripdpi-vless --jobs 4
# Output: target/mutants/missed.txt lists mutations no test caught.
```

Use after a refactor or after an AI-generated rewrite of a well-tested module. Do NOT block CI on mutation score in normal flow — it is too slow (minutes to hours) and produces false-positive mutations (equivalent transformations).

## Cost / cadence summary

| Tool | Cost vs `cargo test --locked` | Cadence | Catches |
|------|----------------------|---------|---------|
| `cargo nextest run --locked` | baseline | every PR | functional regressions |
| `cargo-careful` | 2–3× | every PR if FFI present | uninit reads, alignment, std-debug-assert violations |
| Miri | 50–400× | nightly + on `unsafe` PRs | UB, aliasing, provenance (pure Rust) |
| loom | exponential, bounded by preemptions | every PR touching custom concurrency | data races, atomic reorderings |
| proptest | minutes | every PR on parsers | edge-case parse failures, roundtrip violations |
| cargo-fuzz | hours-days | nightly / weekly | OOMs, panics, slow inputs, UB on adversarial bytes |
| cargo-mutants | minutes-hours | manual, post-refactor | weak tests, untested branches |
| ASan / TSan / MSan | 2–10× | nightly on FFI crates | UAF, data races, uninit reads (in FFI) |

## CI wiring

```yaml
# .github/workflows/dynamic-checks.yml — sketch
jobs:
  careful:
    runs-on: ubuntu-latest
    steps:
      - run: rustup default nightly
      - run: cargo install cargo-careful
      - run: cargo +nightly careful test --workspace --no-fail-fast

  loom:
    runs-on: ubuntu-latest
    steps:
      - run: RUSTFLAGS="--cfg loom" cargo test --locked --release --tests

  fuzz_nightly:
    if: github.event_name == 'schedule'
    runs-on: ubuntu-latest
    steps:
      - run: cargo install cargo-fuzz
      - run: |
          for target in $(cargo fuzz list); do
            cargo +nightly fuzz run "$target" -- -max_total_time=900
          done
```

Miri + ASan/TSan setup lives in `rust-sanitizers-miri`; this skill is for the rest.

## Related skills

- `rust-sanitizers-miri` — Miri primary path, ASan/TSan/MSan/HWASan for FFI.
- `memory-model` — every Relaxed publish/subscribe site enumerated there is a loom-test candidate.
- `rust-unsafe` — `#[cfg(miri)]` stubbing for FFI, ManuallyDrop / from_raw_parts caution.
- `rust-discipline` — allocation hot-path rules; large_stack_frames lint.
- `cargo-workflows` — workspace setup; nextest profiles (`default`, `ci`).
