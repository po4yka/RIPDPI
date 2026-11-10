# Change: Replace unmaintained paste proc-macro dependency

Task ID: `RST-1786264762917234`

## Why

Remove the expired RUSTSEC-2024-0436 waiver. Arti and netlink still use the
archived crates.io `paste` implementation, which blocks the CI advisory gate.

## What Changes

- Replace crates.io `paste` with a local compatibility adapter that re-exports
  the macros from `pastey` 0.2.3.
- Preserve the `paste` package name, compatible version, and three macro exports.
- Remove the advisory ignore and its waiver metadata after the old implementation
  leaves the dependency graph for all target platforms.

## Capabilities

### New Capabilities

- `replace-unmaintained-paste-proc-macro`: Replace unmaintained paste proc-macro dependency

### Modified Capabilities

- None.

## Impact

- Portfolio area: `rust-native`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
