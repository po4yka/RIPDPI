# RST-1786264762917099: Triage undocumented orphan crates and document NATIVE_RUST.md prune candidates

## Objective

Triage undocumented orphan crates and document NATIVE_RUST.md prune candidates

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [ ] RST-1786264762919395 PR states a verdict for each of the two new orphans and the five prune candidates #feature !low @item:RST-1786264762917099
- [ ] RST-1786264762919083 NATIVERUST.md lists every workspace crate (no undocumented crate remains) or the orphan is deleted #feature !low @item:RST-1786264762917099
- [ ] RST-1786264762919084 prune-candidates / planned-crates metadata lists exist where crates are kept #feature !low @item:RST-1786264762917099
- [ ] RST-1786264762919069 CI guard prevents new direct deps on prune-candidate crates #feature !low @item:RST-1786264762917099
- [ ] RST-1786264762919718 cargo metadata + cargo deny check clean after any deletions; Cargo.lock change is its own reviewed hunk #feature !low @item:RST-1786264762917099

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
