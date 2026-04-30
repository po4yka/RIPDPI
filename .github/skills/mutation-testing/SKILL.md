---
name: mutation-testing
description: Rust mutation testing with cargo-mutants, survived-mutant triage, and test adequacy fixes.
---

# Mutation Testing

## What mutation testing reveals that coverage does not

Coverage tells you which lines execute during tests, not whether the tests
verify the behavior. A function with 100% coverage can have zero assertions.

Mutation testing injects small semantic changes (mutants) -- flipping operators,
replacing return values, deleting calls -- and re-runs the test suite for each.
If tests still pass after a mutation, that mutant "survived," meaning tests
execute the code but never check its correctness. Surviving mutants point
directly at weak assertions, missing edge-case tests, or untested error paths.

## Running locally

All commands assume your working directory is the repository root.

### Full workspace run

```bash
cargo mutants \
  --manifest-path native/rust/Cargo.toml \
  --test-tool nextest \
  --jobs auto \
  --output target/mutants-output
```

### Incremental: only lines changed vs main

Add `--in-diff "git diff origin/main...HEAD"` to the command above. This only
generates mutants for changed lines, so a focused PR might produce 10-50
mutants instead of thousands.

### Single package

Add `--package ripdpi-desync` to target one crate. Combine with `--in-diff`
for the fastest feedback loop.

### Useful flags

| Flag | Purpose |
|------|---------|
| `--list` | Dry-run: print mutants without executing tests |
| `--file <path>` | Restrict to a specific source file |
| `--re <regex>` | Only mutants matching a regex on the function name |
| `--timeout-multiplier <N>` | Override the config timeout multiplier |
| `-j <N>` / `--jobs <N>` | Parallel mutant jobs (default: auto) |

## Configuration: native/rust/mutants.toml

```toml
timeout_multiplier = 3.0
minimum_test_timeout = 30
```

- **timeout_multiplier**: Each mutant gets `baseline_duration * 3.0` before
  being killed. The 3x accounts for build overhead and CI variability.
- **minimum_test_timeout**: 30-second floor prevents flaky timeout kills.

All 23 workspace crates are mutation targets by default. Prefer narrow
source-level excludes over disabling entire packages.

To exclude specific functions or modules, add to `mutants.toml`:

```toml
exclude_re = ["Display::fmt", "impl.*Debug"]
```

## CI workflow: .github/workflows/mutation-testing.yml

**Schedule**: Weekly on Monday 06:00 UTC (`cron: "0 6 * * 1"`).

**Manual dispatch inputs**:
- `packages` -- space-separated crate names; empty = all 23 crates
- `in_diff` -- `true` to only mutate lines changed vs main

**Key details**:
- Rust toolchain pinned to 1.94.0 (keep in sync with `rust-toolchain.toml`)
- Installs `cargo-nextest` and `cargo-mutants` via `taiki-e/install-action`
- Runs on `ubuntu-latest` with a 90-minute timeout
- Uploads `target/mutants-output/` as artifact, retained 14 days
- Concurrency group cancels in-progress runs on the same ref

To trigger manually from the CLI:

```bash
gh workflow run mutation-testing.yml \
  -f packages="ripdpi-desync ripdpi-packets" \
  -f in_diff=true
```

## Interpreting results

Results land in `target/mutants-output/`. Key files:

| File | Content |
|------|---------|
| `caught.txt` | Mutants killed by tests (good) |
| `missed.txt` | Mutants that survived (test gaps) |
| `timeout.txt` | Mutants that caused infinite loops / hangs |
| `unviable.txt` | Mutants that failed to compile (not interesting) |
| `outcomes.json` | Machine-readable full results |

### What each category means

- **Caught**: A test failed -- the suite adequately covers this behavior.
- **Missed**: All tests passed despite the mutation. The actionable category --
  each entry names the function and the undetected change.
- **Timeout**: Mutation caused a hang (broken loop/retry). Usually not a test
  gap, but check for missing timeout assertions.
- **Unviable**: Did not compile. Ignore; type system prevented the mutation.

### Triage workflow

1. Open `missed.txt`
2. For each survived mutant, read the function and the mutation description
3. Ask: "Should a test catch this?" If yes, write a targeted test
4. If the mutation is in genuinely untestable code (FFI glue, logging), add
   an exclude pattern to `mutants.toml` rather than writing a meaningless test

## Writing mutation-resistant tests

### Assert specific values, not just "no panic"

```rust
// Weak: survives mutations that change the return value
assert!(compute_ttl(input).is_ok());

// Strong: catches any change to the computed value
assert_eq!(compute_ttl(input), Ok(Duration::from_secs(30)));
```

### Test boundary conditions

Mutants often flip `<` to `<=` or `+1` to `-1`. Pin the boundaries:

```rust
#[test]
fn fragment_offset_boundary() {
    // Exactly at limit -- should succeed
    assert!(validate_offset(MAX_OFFSET).is_ok());
    // One past limit -- should fail
    assert!(validate_offset(MAX_OFFSET + 1).is_err());
}
```

### Cover error paths and side effects

Error branches often survive because no test triggers them. Write explicit tests
for malformed input, invalid state, and rejection paths. Similarly, if a
function has side effects (incrementing a counter, sending a metric), assert the
effect occurred -- mutations that delete the call survive otherwise.

## Common false positives

Suppress these in `mutants.toml` with `exclude_re` rather than writing
low-value tests:

- **Display / Debug impls**: Format string mutations are not real bugs.
- **Logging calls**: `tracing::debug!` argument changes do not affect correctness.
- **Unreachable branches**: `unreachable!()` in type-guarded match arms.
- **Builder defaults**: Survived only because tests always override the field.

## The runner script: scripts/ci/run-rust-mutants.sh

The script is the single entry point for both CI and local batch runs.

**What it does**: Locates `native/rust/Cargo.toml`, reads env vars
(`MUTANTS_TEST_TOOL`=nextest, `MUTANTS_PACKAGES`=all, `MUTANTS_JOBS`=auto),
filters packages via `cargo metadata` if a subset is requested, then calls
`cargo mutants` with assembled args. Extra CLI args (e.g., `--in-diff`) are
passed through.

**Local usage**:

```bash
bash scripts/ci/run-rust-mutants.sh                                          # all crates
MUTANTS_PACKAGES="ripdpi-desync" bash scripts/ci/run-rust-mutants.sh         # one crate
bash scripts/ci/run-rust-mutants.sh --in-diff "git diff origin/main...HEAD"  # incremental
MUTANTS_JOBS=4 bash scripts/ci/run-rust-mutants.sh                           # limit parallelism
```

The script uses `run_workspace_mutants` and currently targets only the main
workspace. Add another call at the bottom to extend to additional workspaces.
