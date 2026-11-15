# DGN-1794732416004558

## Objective

Repair DIA-03 probe verdicts and verify the H3 resource risks.

## Ownership

Use the exact path ownership and shared-file lanes in the linked portfolio task.

## Execution

- [x] DGN-1794732507106058 Reject malformed H3 response fields and verify pseudo-field rules #bug !high @item:DGN-1794732416004558
- [x] DGN-1794732512662461 Bound H3 non-DATA frame buffering and verify both probe callers #bug !high @item:DGN-1794732416004558
- [x] DGN-1794732517555608 Reject unsupported HTTP content coding without a clean verdict #bug !high @item:DGN-1794732416004558
- [x] DGN-1794732524778961 Remove generic text-only blockpage false positives #bug !high @item:DGN-1794732416004558
- [x] DGN-1794732531296335 Distinguish PMTU local timeout from scan deadline #bug !high @item:DGN-1794732416004558
- [x] DGN-1794732539648594 Review and validate the combined repairs before main integration #bug !high @item:DGN-1794732416004558

## Verification

Run the affected Rust crate tests and checks with --locked, independent review, cargo metadata, architecture health, and just ci-gates. Record exact-SHA evidence in verification.md. Do not claim device, artifact, hosted CI, or deployment acceptance from local tests.
