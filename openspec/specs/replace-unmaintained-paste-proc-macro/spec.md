# replace-unmaintained-paste-proc-macro Specification

## Purpose
Replace the archived crates.io `paste` implementation while preserving the
macro API used by Arti and netlink. Remove the RUSTSEC-2024-0436 waiver.

## Requirements

### Requirement: REQ-RST-1786264762917234-001 — Implement Replace unmaintained paste proc-macro dependency and verify its portf…

The workspace MUST resolve all `paste` dependencies to a repository-owned,
`no_std` adapter that re-exports `paste`, `item`, and `expr` from `pastey`.
The graph for all target platforms MUST contain no registry-sourced `paste`.
The RUSTSEC-2024-0436 ignore and waiver metadata MUST be removed.

#### Scenario: Verify criterion 1

- **WHEN** the compatibility tests, workspace tests, and Android debug build run
- **THEN** existing macro imports and expansions MUST compile and their tests MUST pass

#### Scenario: Verify dependency and advisory policy

- **WHEN** Cargo metadata for all targets and the CI-pinned cargo-deny are checked
- **THEN** `paste` MUST resolve only to the local adapter with a `pastey` dependency
- **AND** the advisory and waiver checks MUST pass without a RUSTSEC-2024-0436 exception
