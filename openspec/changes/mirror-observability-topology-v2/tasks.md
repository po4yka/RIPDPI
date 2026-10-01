# DAT-1790868146040476: Mirror observability topology schema v2

## Objective and ownership

Publish the exact frozen producer topology test resource. Own that schema,
the authorized Arc normalization and existing `Dissect::tcp_mss` snapshot repair,
this task's records, and its generated board entry; runtime and other mirrors
remain unchanged.

## Execution

- [x] DAT-1790868271135771 Mirror frozen topology v2 and verify local contract gates #chore !high @item:DAT-1790868146040476
- [ ] DAT-1790868271659032 Verify hosted checks and integrate the authorized mirror PR #chore !high @item:DAT-1790868146040476

## Verification

Full mirror byte comparison, JSON/schema checks, taskctl validation,
architecture health, locked Cargo metadata, core data unit tests, required
exact-head hosted checks, and protected-main merge status.
