---
paths:
  - "native/rust/**"
  - ".github/workflows/**/*.yml"
  - ".github/workflows/**/*.yaml"
---

## Rust toolchain pin and `--locked` discipline

RIPDPI pins its Rust toolchain at `native/rust/rust-toolchain.toml`. The pin is **load-bearing**: it eliminates a class of LLM failure where the model writes code targeting a feature stabilized in a newer rustc than the workspace allows. Agentic flows that resolve dependencies without `--locked` are equally hazardous.

### Current pin

```toml
# native/rust/rust-toolchain.toml
[toolchain]
channel = "1.98.1"
components = ["rustfmt", "clippy", "rust-src", "rust-analyzer"]
targets = [
    "aarch64-linux-android",
    "armv7-linux-androideabi",
    "i686-linux-android",
    "x86_64-linux-android",
]
```

Recommended additions to `components` for full agent productivity:

```toml
components = ["rustfmt", "clippy", "rust-src", "rust-analyzer"]
```

- `rust-src` is needed by Miri and by `-Zbuild-std` sanitizer builds.
- `rust-analyzer` is needed by any rust-analyzer MCP integration.
- `miri` is a nightly-only component — install separately via `rustup +nightly component add miri`.

### Rules

1. **Every dependency-resolving workspace `cargo` invocation that supports lock enforcement passes `--locked`.** This includes hook scripts, sub-agent Bash commands, CI jobs, and manual diagnostic runs by the developer when reproducing an agent failure. Commands that intentionally mutate the lockfile (`cargo update`, `cargo generate-lockfile`) and commands that do not resolve workspace dependencies (`cargo fmt`, `cargo audit`, `cargo --version`) are outside this rule. Without `--locked`, Cargo may transparently bump a dependency that was previously vetted by `cargo deny --locked check`.
2. **MSRV bump is a tracked decision, never a side-effect.** Bumping the `channel` in `rust-toolchain.toml` requires:
   - A tracking issue with the motivation (specific feature needed, security patch, etc.).
   - `cargo nextest run --workspace --locked` passing on the new toolchain.
   - `cargo +nightly miri test --locked` passing on the unsafe-bearing crates.
   - A workspace-wide `cargo deny --locked check` pass (advisories may differ across rustc versions).
3. **`Cargo.lock` is committed.** Already true for RIPDPI (cdylib workspace) — do not regress.
4. **Edition migration follows the per-crate leaf-first procedure in `cargo-workflows`.** Do not bump workspace-wide edition in a single commit.

### When the agent disagrees with the pin

If a model wants to use a feature that requires a newer rustc, the answer is one of:
- Refactor to avoid the feature (preferred).
- File the tracking issue, do the MSRV bump as its own PR, then land the feature change.

Never tell the agent "the toolchain is wrong, ignore the pin." The pin is the source of truth.

### CI verification

`.github/workflows/` should fail any PR where:
- `cargo --version` does not match the pinned channel.
- `Cargo.lock` differs from the committed version after a `cargo fetch --locked`.
- `cargo deny --locked check` regresses against the previous main-branch baseline.

### Related

- `cargo-workflows` skill — workspace structure, edition migration, nextest profiles.
- `rust-lints` skill — `clippy.toml` `msrv` field that mirrors this pin (must be kept in sync).
- `llm-rust-prompts.md` rule — diff-acceptance gate references the pin discipline.
- `rust-security` skill — `cargo-deny` configuration and RUSTSEC SLA.
