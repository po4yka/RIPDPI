# TRN-1794198667426286

## Objective

Correct all three confirmed STD-06 defects and keep explicit caller policies.

## Ownership

The portfolio task and design name each isolated writer and the primary integration owner. Shared generated API snapshots are serialized under the primary agent.

## Execution

- [x] TRN-1794198793896705 Reject CN-only standard TLS identities and preserve explicit pin callers #bug !high @item:TRN-1794198667426286
- [x] TRN-1794198794706108 Use only bundled and explicit native roots with subprocess regressions #bug !high @item:TRN-1794198667426286
- [x] TRN-1794198795509654 Remove the unused diagnostic verification flag and update every caller #bug !high @item:TRN-1794198667426286
- [x] TRN-1794198796214639 Validate the combined change and complete independent review #bug !high @item:TRN-1794198667426286

## Verification

Run focused red/green behavior regressions, affected Rust crate tests and clippy, cargo metadata --locked, architecture health, just ci-gates, and an independent read-only review.
