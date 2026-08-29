# DAT-1787994690722107: Mirror probe matrix report schema 3

## Objective

Synchronize RIPDPI's vendored probe-matrix report schema with the frozen
deployment producer contract and prove exact local and hosted compatibility.

## Ownership

This task exclusively owns
`core/data/src/test/resources/contract/probe-matrix-report.schema.json` and its
new task/OpenSpec records. It does not own client runtime, schema 2 window
semantics, or network-exposure contracts.

## Execution

- [x] DAT-1787995524017674 Mirror schema 3 and run local contract gates #chore !high @item:DAT-1787994690722107
- [ ] DAT-1787995524551814 Publish exact client commit and verify hosted checks #chore !high @item:DAT-1787994690722107

## Verification

Required gates: producer SHA and byte comparison, JSON validity, the complete
contract mirror test, strict task/OpenSpec validation, architecture health,
proportional Android tests, and exact-head hosted CI.
