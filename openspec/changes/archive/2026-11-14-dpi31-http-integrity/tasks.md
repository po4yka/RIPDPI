# ENG-1794677657331523

## Objective

Preserve meaningful request data and expose independent HTTP mutation outcomes without changing chain disposition.

## Ownership

- Packet worker owns `ripdpi-packets/src/http/mutation.rs` and owning HTTP tests in its worktree.
- Receipt worker owns desync planning, native receipts, runtime API and diagnostics receipt consumers in Rust and Kotlin in its worktree.
- Integrator owns this task/OpenSpec state, API snapshots, independent review, combined gates, and main integration. Shared writes are serialized.
- No dependency, Cargo.lock, locale, baseline, golden, or root-mode change.

## Execution

- [x] ENG-1794677777812641 Preserve meaningful User-Agent bytes in packets Method EOL and verify data-preservation regressions #bug !high @item:ENG-1794677657331523
- [x] ENG-1794677778638690 Decline Method EOL on prefixed requests and verify metadata-preservation regressions #bug !high @item:ENG-1794677657331523
- [x] ENG-1794677779483214 Propagate optional HTTP mutation evidence through native and Kotlin receipts with compatibility tests #bug !high @item:ENG-1794677657331523
- [x] ENG-1794677781056020 Run independent review and affected Rust Kotlin contract and source gates before integration #chore !high @item:ENG-1794677657331523

## Verification

Run defect regressions first, affected Rust crate tests with `--locked`, strict Clippy and format checks, affected Kotlin decode and contract tests, independent read-only review, `just ci-gates`, architecture health, and locked Cargo metadata. Record actual commands and exact commit SHA in verification.md. Device, hosted CI, external compatibility, and deployment proof remain separate.
