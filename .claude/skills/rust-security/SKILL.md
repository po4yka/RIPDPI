---
name: rust-security
description: Rust supply chain security skill. Use when auditing dependencies with cargo-audit, enforcing policies with cargo-deny, reviewing RUSTSEC advisories, or hardening the dependency supply chain. Activates on queries about cargo-audit, cargo-deny, RUSTSEC advisories, supply chain security, Rust CVEs, or dependency policy enforcement.
---

# Rust Supply Chain Security

## Purpose

Guide agents through Rust supply chain security: vulnerability scanning with cargo-audit, policy enforcement with cargo-deny (as configured in this project), and RUSTSEC advisory triage. For FFI safety, fuzzing, sanitizers, and Miri, see cross-references at the end.

## Triggers

- "How do I check my Rust dependencies for CVEs?"
- "How do I use cargo-audit / cargo-deny?"
- "How do I enforce dependency policies in CI?"
- "What's the RUSTSEC advisory database?"
- "A dependency has a security advisory -- what do I do?"

## Project context

- 23 Rust crates under `native/rust/`
- Policy config: `native/rust/deny.toml`
- CI job: `cargo-deny` in `.github/workflows/ci.yml` (cargo-deny v0.19.0, installed via `taiki-e/install-action@v2`)
- CI invocation: `cargo deny --manifest-path native/rust/Cargo.toml check`

## Workflow

### 1. cargo-audit -- vulnerability scanning

```bash
# Install
cargo install cargo-audit --locked

# Scan current project
cargo audit

# Strict mode: treat warnings as errors (good for CI)
cargo audit --deny warnings

# Audit a specific lockfile
cargo audit --file native/rust/Cargo.lock

# JSON output for scripting
cargo audit --json | jq '.vulnerabilities.list[].advisory.id'
```

Output format:

```
error[RUSTSEC-2023-0052]: Vulnerability in `vm-superio`
    Severity: low
       Title: MMIO Register Misuse
    Solution: upgrade to `>= 0.7.0`
```

### 2. cargo-deny -- policy enforcement

cargo-deny goes beyond audit: it enforces license policies, bans specific crates, checks source origins, and validates duplicate dependency versions.

```bash
# Run all checks against project config
cargo deny --manifest-path native/rust/Cargo.toml check

# Run a specific check
cargo deny --manifest-path native/rust/Cargo.toml check advisories
cargo deny --manifest-path native/rust/Cargo.toml check licenses
cargo deny --manifest-path native/rust/Cargo.toml check bans
cargo deny --manifest-path native/rust/Cargo.toml check sources
```

#### Project deny.toml (`native/rust/deny.toml`)

The project config enforces:

- **advisories**: vulnerabilities denied by default; `ignore = []` (no exemptions)
- **licenses**: allowlist of permissive licenses (MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC, 0BSD, Zlib, CDLA-Permissive-2.0, Unicode-3.0, OpenSSL); confidence threshold 0.8
- **bans**: `multiple-versions = "warn"`, `wildcards = "warn"`, `highlight = "all"`
- **sources**: `unknown-registry = "deny"`, `unknown-git = "warn"`, only crates.io allowed via `allow-registry`

When modifying `deny.toml`, always run `cargo deny --manifest-path native/rust/Cargo.toml check` locally before pushing to validate changes.

### 3. RUSTSEC advisory database

The RUSTSEC database at https://rustsec.org/ tracks vulnerabilities, unmaintained crates, and unsound code.

```bash
# Sync and browse local advisory DB
cargo audit fetch
ls ~/.cargo/advisory-db/crates/

# View a specific advisory on the web
# https://rustsec.org/advisories/RUSTSEC-2023-0001.html

# Advisory categories:
# vulnerability -- exploitable security bug
# unmaintained -- no longer maintained (supply chain risk)
# unsound     -- documented unsoundness in safe API
# yanked      -- crate version yanked from crates.io
```

### 4. Responding to a new advisory

```bash
# 1. Identify affected crate and version
cargo audit 2>&1 | grep -A3 'RUSTSEC-'

# 2. Check if an upgrade is available
cargo update -p <crate_name> --dry-run

# 3. If upgrade exists, apply it
cargo update -p <crate_name>

# 4. If no fix yet, assess and optionally add to deny.toml ignore list
#    Only add with a comment explaining why and a tracking issue
#    Example in deny.toml:
#    ignore = [
#        { id = "RUSTSEC-2024-XXXX", reason = "not reachable in our code path, tracking #123" },
#    ]

# 5. Verify
cargo deny --manifest-path native/rust/Cargo.toml check advisories
```

### 5. Supply chain hardening

```bash
# Always commit Cargo.lock for applications/binaries
# Use --locked in CI to enforce lockfile matches
cargo fetch --locked

# Audit full dependency tree
cargo tree                  # view full tree
cargo tree -d               # show duplicate versions

# Find unused dependencies
cargo machete

# Vet dependencies with cargo-vet (peer review)
cargo install cargo-vet
cargo vet
```

### 6. CI integration

The project CI runs cargo-deny as a standalone job on every PR (skipped for scheduled builds). The job:

1. Checks out the repo
2. Installs cargo-deny v0.19.0 via `taiki-e/install-action@v2`
3. Runs `cargo deny --manifest-path native/rust/Cargo.toml check`

If the CI job fails:
- Check the failing check category (advisories, licenses, bans, sources)
- Run the same command locally to reproduce
- Fix the root cause; do not extend `ignore` lists without a tracking issue

## Related skills

- `.claude/skills/rust-sanitizers-miri` -- Miri and sanitizer usage for memory safety validation
- `.claude/skills/rust-unsafe` -- unsafe code audit patterns and safe abstraction design
- `.github/skills/rust-jni-bridge` -- JNI FFI patterns (the project's FFI boundary)
- `.claude/skills/cargo-workflows` -- Cargo.lock management, workspaces, and feature flags
