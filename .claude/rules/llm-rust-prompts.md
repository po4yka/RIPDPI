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
3. **Require cancel-safety annotation on every async fn.** Phrase as: "For each async fn you produce, prefix with `// cancel-safe: <reason>` or `// NOT cancel-safe: <reason>`." This walks every function. "Write cancel-safe code" alone is ignored.
4. **Require `// SAFETY:` blocks for every `unsafe` block.** List invariants per-pointer/per-fd, matching the format in the `rust-unsafe` skill.
5. **Require a call-site usage example for non-trivial lifetime signatures.** Forces the model out of local optimization; surfaces lifetime laundering at write time.
6. **Do not delegate trait hierarchy design.** Write the trait + doc contract by hand, then delegate implementations. Strategic errors here are expensive to undo.
7. **Do not delegate `unsafe` byte-buffer parsing without explicit alignment context.** Specify the source of bytes ("from network, alignment unknown" or "from `Vec<u8>`, 8-byte aligned"). See the `rust-unsafe` skill section on "Pointer reads from untrusted byte buffers".

### Diff acceptance gate

Any AI-generated diff that touches **any** of the following requires a separate `pr-reviewer` agent pass before commit:

- `unsafe` block (any)
- `.unwrap()` or `.expect()` outside tests
- `std::mem::transmute`
- `Arc<T>` constructor where the inner type contains state mutated under concurrency
- `std::sync::Mutex`, `parking_lot::Mutex`, or `tokio::sync::Mutex` introduction
- Manual `unsafe impl Send for T` or `unsafe impl Sync for T`
- `impl<T: Bound> PubTrait for T` (blanket impl on public trait)
- Custom `PartialEq` without matching custom `Hash`
- Manual `impl Drop` on a struct containing async resources

No exceptions. The reviewer pass costs minutes; an unaudited bug from this list costs days.

### CI infrastructure expectations

- **Miri nightly** for every crate without `#![forbid(unsafe_code)]`. The 10× test slowdown is the price; empirical measurement shows 22/40 (~55%) of LLM-generated `unsafe` samples have UB. Run as a scheduled (nightly) job, not on every PR.
- **`clippy::pedantic` + selected nursery lints** for substantially AI-authored code. Adopt this **per-crate** (crate-root `#![warn(clippy::pedantic)]` opt-in), not as a workspace-wide group flip; `ripdpi-tor/src/lib.rs` is the maintained demonstration.
- **`cargo deny --locked` on every PR** — already wired in RIPDPI; do not regress.
- **`cargo audit` daily on `main`** to catch published advisories against pinned deps.

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

**Deploy-stack adjacent (sibling `ripdpi-vpn-deploy` repo):**

- xray `routing.rules` entry with `"type": "field"` and NO selector field (`domain`/`ip`/`port`/`network`/`source`/`user`/`inboundTag`/`protocol`/`attrs`) → xray v26+ rejects with `app/router: this rule has no effective fields` at start-test. A catch-all "default" rule must be expressed as `"network": "tcp,udp"`; the intuitively-empty form is invalid. See `ansible-molecule.md` Rule 3.
- ansible molecule `scenario.yml` where the platform name appears at the top of `inventory.hosts.<name>` (no group nesting) → playbook `hosts: vpn` matches nothing, converge logs `PLAY RECAP : ok=0 changed=0` and exits 0. See `ansible-molecule.md` Rule 1.
- ansible molecule scenario that references a variable from `ansible/group_vars/all.yml` without mirroring it under the scenario's `inventory.group_vars.all` block → role aborts with `<var> is undefined` mid-converge even though the variable *is* defined for the real playbook. See `ansible-molecule.md` Rule 2.

### Author and verifier separation

For high-risk Rust work, separate authorship from verification when the active runtime exposes a suitable specialist. Use only agents present in `.claude/agents/`, `.codex/agents/`, and the current tool catalog; do not route through invented aliases or dated model snapshots.

1. A write-capable implementation agent produces the narrow diff and its focused tests in an isolated worktree.
2. A read-only specialist such as `rust-api-auditor`, `unsafe-code-auditor`, `async-cancel-safety`, or `pr-reviewer` reviews the diff against concrete oracle output.
3. The author resolves actionable findings, then the integration verifier reruns the required `cargo ... --locked` gates on the rebased tree.

Respect the model and sandbox declared by the selected profile rather than overriding them from this rule.

### `--locked` discipline in agentic flows

Every cargo invocation issued by an agent — sub-agent Bash command, hook script, slash command, manual diagnostic — MUST pass `--locked`. Rationale:

- Without `--locked`, cargo may transparently bump a dependency that was previously vetted by `cargo deny --locked check`. The vetting becomes stale silently.
- Agentic loops that run `cargo build` repeatedly without `--locked` can drift `Cargo.lock` across iterations, producing diffs unrelated to the task.
- The `rust-toolchain.toml` pin (`rust-toolchain-pin.md` rule) provides the channel guarantee; `--locked` provides the dependency-version guarantee. They are complementary.

Exception: `cargo update -p <crate> --precise <version>` and `cargo update --workspace` are the only valid ways to bump deps, and they must be in their own PR.

### rust-analyzer MCP — query before guessing

When a model would otherwise need to "guess" a Rust type, lifetime, trait bound, or signature, the model MUST first query rust-analyzer (via MCP, when available) for `hover` / `find_references` / `goto_definition` on the relevant identifier. Empirical: this reduces retry-to-compile by ~2× for borrow-checker errors specifically.

Hook this into sub-agent dispatch: any agent whose prompt involves a type or borrow-checker question should be told "consult rust-analyzer MCP before guessing." For any agent class focused on borrow-checker error review, this is mandatory; for general code generation, it is strongly preferred.

When rust-analyzer MCP is not configured, the fallback is `cargo expand -p <crate> <module>` (for macro-related questions) and reading the `cargo doc --locked --no-deps --message-format=json` output (for cross-crate types). Never let the model guess a signature when one of these tools can answer in < 5 seconds.

### When the LLM disagrees with this rule file

The LLM is wrong. The rules are derived from production failures, not theory. Push back; do not accept "but the code compiles" as an answer.

### Cross-references

- `rust-discipline` skill — items 22–25 cover the lifetime/blanket-impl/stack-array sentinels above.
- `rust-lints` skill — canonical `[workspace.lints]` and `clippy.toml` template that catches the sentinels at build time.
- `rust-unsafe` skill — pointer reads from untrusted byte buffers section; lint floor for unsafe crates.
- `rust-async-internals` skill — cancel-safety annotation discipline, library Drop contracts, async closures, extended `CancellationToken` patterns.
- `rust-test-tools` skill — cargo-careful / loom / proptest / fuzz / mutants beyond the standard `cargo test --locked`.
- `rust-sanitizers-miri` skill — Miri configuration for the nightly UB-detection job.
- `rust-security` skill — RUSTSEC triage SLA for advisories surfacing via `cargo audit`.
- `rust-toolchain-pin.md` rule — toolchain channel pin and `--locked` discipline.
- `ansible-molecule.md` rule — molecule scenario inventory / group_vars / xray routing-rule discipline for diffs touching the sibling `ripdpi-vpn-deploy` repo.
- `async-cancel-safety` sub-agent — the automated audit for the cancel-safety requirements in this file.
