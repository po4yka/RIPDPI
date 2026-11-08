## Purpose

Keep root packet processing and cleanup bound to the correct app instance, and reject file paths that leave its private directory.

## ADDED Requirements

### Requirement: REQ-NFQ-MONITOR — Report loss before monitor registration

An active backend MUST report helper loss to its current session even when the helper exits before monitor registration. A stale session MUST NOT fail a newer session.

#### Scenario: Helper exits before monitoring

- **WHEN** activation succeeds and the helper exits before monitoring starts
- **THEN** the session receives one backend failure and no silent plain fallback

### Requirement: REQ-NFQ-REAP — Retain stale cleanup until confirmed

The helper MUST retain failed stale-rule discovery and deletion as pending cleanup, retain discovered ownership and remaining commands after partial deletion, retry with bounded work, and reject backend activation until the debt is cleared.

#### Scenario: Firewall lock exceeds startup cleanup deadline

- **WHEN** the lock prevents stale cleanup until its deadline and later clears
- **THEN** cleanup remains visible and retries without an app restart
- **AND** both IP families are clean before a new consumer activates

### Requirement: REQ-NFQ-PROCESS — Stop only verified owned predecessors

Predecessor cleanup MUST target only a verified helper for the current private app directory. It MUST allow bounded graceful cleanup before forced termination, and MUST NOT signal a process whose identity has changed.

#### Scenario: Another app instance remains active

- **WHEN** the app cleans up its predecessor while another instance has a helper
- **THEN** the other helper is not signalled
- **AND** the owned helper receives a graceful stop before any justified escalation

### Requirement: REQ-NFQ-CHAINS — Keep firewall ownership unambiguous

New chain names MUST encode app UID and helper PID without ambiguity. Recovery of legacy chain names MUST prove the app owner before mutation. Unproven or foreign state MUST NOT be removed.

#### Scenario: App UIDs share a hexadecimal prefix

- **WHEN** a listing contains chains for UIDs 10196 and 2610196
- **THEN** cleanup for either UID does not mutate the other's rules

#### Scenario: Recover a proven legacy chain

- **WHEN** a stale legacy chain has an exact owned OUTPUT owner match or a verified paired chain
- **THEN** recovery removes only the proven owned legacy state

### Requirement: REQ-NFQ-FILE — Validate the selected Lua file

Lua file validation MUST check the actual file selected by upstream, including its compressed fallback, and MUST reject a selected path outside the private app directory.

#### Scenario: Compressed fallback is a symlink outside the directory

- **WHEN** the plain Lua path is absent and its compressed fallback leaves the directory
- **THEN** activation rejects the file argument

#### Scenario: Compressed fallback stays private

- **WHEN** the plain Lua path is absent and the compressed file stays in the directory
- **THEN** validation accepts the file for the existing upstream loader

### Requirement: REQ-NFQ-COMPAT — Preserve root and strategy boundaries

The change MUST preserve non-root baseline operation, explicit root activation for nfqws2, YAML-chain incompatibility rejection, the upstream trusted Lua boundary, and the existing NFQUEUE bypass policy. No new public IPC or configuration schema is required.

#### Scenario: Root is disabled or YAML is set

- **WHEN** explicit nfqws2 activation lacks root mode or has a nonblank YAML chain
- **THEN** activation fails explicitly without enabling a weaker sender
