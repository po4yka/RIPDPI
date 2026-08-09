# RST-1786264762917192: Split the 12-method PolicyPort trait into selection and learning sub-traits

## Objective

Split the 12-method PolicyPort trait into selection and learning sub-traits

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- RST-1786264762919503 DROPPED: PR confirms current 12-method shape at policy.rs:138 #feature @item:RST-1786264762917192
- RST-1786264762919510 DROPPED: Two sub-traits exist; selection-only and learning-only callers depend on the narrower one #feature @item:RST-1786264762917192
- RST-1786264762919196 DROPPED: No behavior change; existing impls satisfy both #feature @item:RST-1786264762917192
- RST-1786264762919348 DROPPED: Test mocks simplify (selection tests no longer stub learning methods) #feature @item:RST-1786264762917192
- RST-1786264762919122 DROPPED: cargo nextest run --locked green for the decision-ports consumers; clippy clean #feature @item:RST-1786264762917192

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
