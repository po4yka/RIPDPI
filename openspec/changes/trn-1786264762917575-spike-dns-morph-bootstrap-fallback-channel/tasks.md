# TRN-1786264762917575: Spike: DNS-Morph bootstrap as fallback bootstrap channel

## Objective

Spike: DNS-Morph bootstrap as fallback bootstrap channel

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [ ] TRN-1786264762919094 ripdpi-dns-morph crate compiles for all 4 Android ABIs #feature @item:TRN-1786264762917575
- [ ] TRN-1786264762919702 Bootstrap completes against a synthetic DNS-Morph bridge in test-lab/dns/ scenario (~3–8 s end-to-end per paper) #feature @item:TRN-1786264762917575
- [ ] TRN-1786264762919740 Active-probing defense verified: probing the bridge with dig @bridge www.example.com returns normal DNS responses #feature @item:TRN-1786264762917575
- [ ] TRN-1786264762919131 Integration test in core/diagnostics-data/ covers bootstrap → primary-transport handoff #feature @item:TRN-1786264762917575
- [ ] TRN-1786264762919009 LOW-confidence dedup explicitly resolved in PR description: confirmed NOT a duplicate of ripdpi-dns-resolver or any current bootstrap transport code #feature @item:TRN-1786264762917575

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
