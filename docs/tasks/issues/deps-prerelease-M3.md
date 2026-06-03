# M3 — Pre-release dependency posture and bump plan

Status: **documented** (no `Cargo.lock` version changed — every pre-release is upstream-pinned).

Motivated by `rust-security` (supply-chain / advisory exposure of `-rc`/`-pre`
crates) and `.claude/rules/rust-toolchain-pin.md` (this is the single isolated
dependency PR allowed to touch `Cargo.lock` versions; `cargo` always `--locked`).

Toolchain: `rustc/cargo 1.96.0` (matches `native/rust/rust-toolchain.toml`).

## Summary

All 18 pre-release crates in `native/rust/Cargo.lock` are **transitive**, pulled
exclusively through `russh v0.61.1` (consumed by the workspace crate
`ripdpi-ssh`, declared in the root workspace as `russh = "=0.61.1"`).

`russh v0.61.1` pins its RustCrypto-family dependencies with **exact (`=`)
version requirements** to the pre-release line. An exact `=X-rc.N` requirement
forbids cargo from selecting any other version — including a published stable —
so `cargo update -p <crate> --precise <stable>` is **rejected at resolution
time** for every one of these crates. None can be bumped without first bumping
`russh` itself, which is a separate, deliberate decision (out of scope for M3:
M3 only authorizes `--precise` bumps of the pre-release crates, not a major
upstream dependency swap).

Result: **0 bumps performed.** `Cargo.lock` is unchanged.

## Pre-release inventory (all transitive via `russh v0.61.1`)

| Crate | Locked version | Constrained by | Bumpable to stable? |
|-------|----------------|----------------|---------------------|
| `curve25519-dalek` | `5.0.0-pre.6` | russh `=5.0.0-pre.6` | No — exact `=` pin |
| `ed25519-dalek` | `3.0.0-pre.7` | russh `=3.0.0-pre.7`, ssh-key | No — exact `=` pin |
| `elliptic-curve` | `0.14.0-rc.32` | russh `=0.14.0-rc.32` | No — exact `=` pin |
| `p256` | `0.14.0-rc.9` | russh `=0.14.0-rc.9`, ssh-key | No — exact `=` pin |
| `p384` | `0.14.0-rc.9` | russh `=0.14.0-rc.9`, ssh-key | No — exact `=` pin |
| `p521` | `0.14.0-rc.9` | russh `=0.14.0-rc.9`, ssh-key | No — exact `=` pin |
| `rsa` | `0.10.0-rc.18` | russh `=0.10.0-rc.18`, ssh-key | No — exact `=` pin |
| `ssh-encoding` | `0.3.0-rc.9` | russh `=0.3.0-rc.9`, ssh-key | No — exact `=` pin |
| `ssh-key` | `0.7.0-rc.10` | russh `=0.7.0-rc.10` | No — exact `=` pin |
| `ecdsa` | `0.17.0-rc.18` | elliptic-curve / p256 / p384 / p521 (rc line) | No — required by rc deps above |
| `primeorder` | `0.14.0-rc.9` | elliptic-curve (rc line) | No — required by rc deps above |
| `primefield` | `0.14.0-rc.9` | rustcrypto-ff / p256-family (rc line) | No — required by rc deps above |
| `pkcs1` | `0.8.0-rc.4` | rsa / ssh-key (rc line) | No — required by rc deps above |
| `aead` | `0.6.0-rc.10` | aes-gcm / ssh-cipher (rc line) | No — required by rc deps above |
| `aes-gcm` | `0.11.0-rc.4` | ssh-cipher / russh AEAD ciphers | No — required by rc deps above |
| `argon2` | `0.6.0-rc.8` | ssh-key (rc line) | No — required by rc deps above |
| `blake2` | `0.11.0-rc.6` | rsa / ssh-key (rc line) | No — required by rc deps above |
| `ssh-cipher` | `0.3.0-rc.9` | ssh-key / russh (rc line) | No — required by rc deps above |

Note: several of these crate names also appear in the lock at **stable**
versions (e.g. `aead 0.5.2`, `ssh-key 0.6.x` is *not* present but the family is
duplicated). The stable copies belong to unrelated subtrees (tor-llcrypto,
other consumers); the `-rc`/`-pre` copies are isolated to the `russh` subtree.
`wasi 0.11.1+wasi-snapshot-preview1` and `wasip3 0.4.0+wasi-0.3.0-rc-2026-01-06`
are **not** pre-releases — the `+...` segment is SemVer build metadata, and the
embedded `rc` is part of a WASI snapshot label, not a crate pre-release tag.

## Evidence

`russh v0.61.1` `Cargo.toml` (from the registry cache) declares exact pins:

```
[dependencies.curve25519-dalek] version = "=5.0.0-pre.6"
[dependencies.ed25519-dalek]    version = "=3.0.0-pre.7"
[dependencies.elliptic-curve]   version = "=0.14.0-rc.32"
[dependencies.p256]             version = "=0.14.0-rc.9"
[dependencies.p384]             version = "=0.14.0-rc.9"
[dependencies.p521]             version = "=0.14.0-rc.9"
[dependencies.rsa]              version = "=0.10.0-rc.18"
[dependencies.ssh-encoding]     version = "=0.3.0-rc.9"
[dependencies.ssh-key]          version = "=0.7.0-rc.10"
```

Attempting a bump is rejected by cargo at resolution time:

```
$ cargo update -p ssh-key@0.7.0-rc.10 --precise 0.6.7 --dry-run --locked
error: failed to select a version for the requirement `ssh-key = "=0.7.0-rc.10"`
candidate versions found which didn't match: 0.6.7
required by package `russh v0.61.1`
```

The same `=`-pin rejection applies to every crate in the table above; the
leaf RustCrypto crates (`ecdsa`, `primefield`, `aead`, …) are dragged along by
the pinned parents and cannot independently move to a stable that the parents'
trait/version requirements would not accept.

## Verification (run with `--locked`, host target, toolchain 1.96.0)

- `cargo deny --locked check advisories` → **advisories ok** (no RUSTSEC
  advisory currently fires against any pre-release crate in the graph).
- `cargo deny --locked check` → **advisories ok, bans ok, licenses ok,
  sources ok**.
- No `Cargo.lock` mutation: dry-run bumps were all rejected, so the lockfile is
  byte-identical to `main`.

## Decision

- **Bumps performed:** none. `outcome="documented"`.
- **Un-bumpable list:** all 18 crates above — every one is held by `russh
  v0.61.1`'s exact (`=`) pre-release pins, directly or via a pinned parent.
- **Forward path (separate PR, NOT this unit):** the only way to retire these
  pre-releases is to bump `russh` itself (workspace pin `russh = "=0.61.1"` in
  `native/rust/Cargo.toml`) once an upstream `russh` release migrates to the
  stable RustCrypto line. That is a behavior-bearing dependency swap requiring
  its own tracking issue, `cargo nextest run --workspace --locked`, and a fresh
  `cargo deny check` per `rust-toolchain-pin.md` MSRV/dep-bump discipline — out
  of scope for M3.

## Re-check command (for the future russh bump)

```sh
cd native/rust
cargo update -p russh --precise <new-russh-stable> --locked   # only when one exists
cargo check --workspace --locked
cargo deny --locked check
```
