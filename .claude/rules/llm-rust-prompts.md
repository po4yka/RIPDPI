---
paths:
  - "native/rust/**/*.rs"
  - "native/rust/**/*.toml"
---

## LLM-generated Rust — prompt and review discipline

LLM-generated Rust can pass build and test gates while still containing UB, deadlocks, or semver hazards. Apply these patterns when delegating Rust work to an available write-capable specialist or accepting an agent-generated diff.

### Prompt construction (when delegating Rust to a subagent)

1. **Pin versions in the prompt.** Quote each relevant package and version from the current `Cargo.lock`, never from a stored example or memory.
2. **Specify runtime flavor.** `multi_thread(N)` vs `current_thread`. The cancel-safety rules differ.
3. **Require a cancel-safety annotation on every async fn.** Phrase as: "Give each async fn you produce a `# Cancel safety:` rustdoc section that starts with `cancel-safe: <reason>` or `NOT cancel-safe: <reason>`." This walks every function, and it is the form the `async-cancel-safety` agent checks. "Write cancel-safe code" alone is ignored.
4. **Require `// SAFETY:` blocks for every `unsafe` block.** List invariants per-pointer/per-fd, matching the format in the `rust-unsafe` skill.
5. **Require a call-site usage example for non-trivial lifetime signatures.** Forces the model out of local optimization; surfaces lifetime laundering at write time.
6. **Do not delegate trait hierarchy design.** Write the trait + doc contract by hand, then delegate implementations. Strategic errors here are expensive to undo.
7. **Do not delegate `unsafe` byte-buffer parsing without explicit alignment context.** Specify the source of bytes ("from network, alignment unknown" or "from `Vec<u8>`, 8-byte aligned"). See the `rust-unsafe` skill section on "Pointer reads from untrusted byte buffers".

Give the delegate a concrete oracle (the failing test, compiler output, or gate command). Before you or a delegate guess a type, lifetime, or trait bound, query rust-analyzer. Without a rust-analyzer server, `cargo doc --locked --no-deps` answers cross-crate signatures and `cargo expand -p <crate> <module>` answers macro questions.

### Diff acceptance gate

Any AI-generated diff that touches **any** of the following requires a separate read-only `pr-reviewer` pass (add `rust-api-auditor`, `unsafe-code-auditor`, or `async-cancel-safety` where they match) before commit; the author then fixes the findings:

- `unsafe` block (any)
- `.unwrap()` or `.expect()` outside tests
- `std::mem::transmute`
- `Arc<T>` constructor where the inner type contains state mutated under concurrency
- `std::sync::Mutex`, `parking_lot::Mutex`, or `tokio::sync::Mutex` introduction
- Manual `unsafe impl Send for T` or `unsafe impl Sync for T`
- `impl<T: Bound> PubTrait for T` (blanket impl on public trait)
- Custom `PartialEq` without matching custom `Hash`
- Manual `impl Drop` on a struct containing async resources

### CI infrastructure expectations

- **Miri nightly** for every crate without `#![forbid(unsafe_code)]`; `scripts/ci/run-rust-miri.sh` tracks the covered set and the triaged gap list. LLM-generated `unsafe` has a materially higher UB rate here than hand-written code.
- **`clippy::pedantic` + selected nursery lints** for substantially AI-authored code, opted in **per crate** at the crate root, not as a workspace-wide group flip; `ripdpi-tor/src/lib.rs` is the maintained demonstration.

### Sentinel patterns for review attention

Skim AI-generated Rust diffs first for these patterns. Each one is a 70%+ predictor of a bug below:

**Generic Rust:**

- `<'a>` appearing in both a function parameter and a `&mut` collection in the same signature → lifetime laundering risk.
- `std::sync::Mutex` in async code → near-certain deadlock under load.
- `ptr::read(buf.as_ptr() as *const T)` on `buf: &[u8]` → near-certain ARM64 UB.
- `Box::new([0u8; N])` for N > 16 KiB → stack overflow in debug.
- `impl<T: ...> PubTrait for T` in a `pub` API → semver hazard.
- `tokio::select!` arm calling a function with no cancel-safety annotation → audit the awaits inside.
- `commit().await?` on a transaction with no explicit rollback path on commit-failure → blocking Drop in async context.

**Android-specific (RIPDPI niche):**

- A non-loopback direct socket that can run while the VPN protection callback is active but bypasses `protect_socket(fd)` → packet-routing loop risk. Callback-free RAW_PATH scans and loopback sockets are intentional exceptions; see `vpnservice-protect-invariant.md`.
- `Box::leak`, `mem::transmute`, or raw pointer cast applied to a `JNIEnv` / `EnvUnowned` / `AttachGuard` value → LLM "fix" for a lifetime error; reject.
- `&mut JNIEnv` captured in a `tokio::spawn(async move { ... })` closure → compile error at best, UB through unsafe escape at worst.
- Bare `std::io::Read::read(tun_file, ...)` inside an `async fn` (no `AsyncFd` wrapper, no `spawn_blocking`) → tokio runtime stall.
- `tracing::event!` / `tracing::span!` / `log::info!` on a per-packet / per-byte code path → ~3 µs/event JNI overhead is a measurable CPU bottleneck at 1 Gbps.
- Logging containing raw `BSSID`, `IMEI`, `IMSI`, `SSID`, operator identity, or device IP instead of the scope hash/redacted summary → privacy defect; see `network-fingerprint-privacy.md`.
- `addAddress` chain on `VpnService.Builder` without a matching `addRoute`, or `setMtu(1500)` without justification (cellular MTU is < 1500) → broken VPN tunnel.
- `RIPDPI_BLESS_GOLDENS=1`, a Roborazzi record task, or a bless script issued without explicit current-conversation user authorization for the affected fixture family → reject. See `golden-bless-discipline.md`.
- `NetdClient.h::protectFromVpn` reference → non-ABI API, breaks between Android releases.
- `serde_json::to_writer` to a path under `~/data` or `/data/data/.../files` without an explicit `fsync` call → state loss on LMK SIGKILL.

### `--locked` discipline

Every agent-issued Cargo command passes `--locked`, so a dependency vetted by `cargo deny --locked check` cannot drift silently and `Cargo.lock` gains no unrelated diff. Only `cargo update -p <crate> --precise <version>` and `cargo update --workspace` bump dependencies, each in its own PR; `rust-toolchain-pin.md` covers the toolchain channel.

### Cross-references

- `rust-discipline` skill — lifetime, blanket-impl, and stack-array sentinels.
- `rust-lints` skill — `[workspace.lints]` and `clippy.toml` that catch the sentinels at build time.
- `rust-async-internals` skill — cancel-safety annotations, Drop contracts, `CancellationToken` patterns.
- `rust-test-tools` and `rust-sanitizers-miri` skills — loom, proptest, fuzz, mutants, and the Miri job.
- `rust-security` skill — RUSTSEC triage SLA for `cargo audit` advisories.
