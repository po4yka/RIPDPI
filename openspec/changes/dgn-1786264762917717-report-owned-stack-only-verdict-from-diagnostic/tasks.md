# DGN-1786264762917717: Report OWNED_STACK_ONLY verdict from diagnostic

## Objective

Report OWNED_STACK_ONLY verdict from diagnostic

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] DGN-1786264762919430 Diagnostic orchestrator emits OWNEDSTACKONLY when the winning arm is A9 or A10 and no transparent arm succeeded #feature @item:DGN-1786264762917717
- [x] DGN-1786264762919079 UI/diagnostics surface: "Transparent mode: no / Owned-stack mode: yes" with a direct action to open the URL in the in-app browser #feature @item:DGN-1786264762917717
- [x] DGN-1786264762919319 Persisted policy sets outcome = OWNEDSTACKONLY on the TransportPolicy when owned-stack-only diagnostic evidence is present #feature @item:DGN-1786264762917717
- [x] DGN-1786264762919187 Third-party apps hitting this host in transparent mode get a structured "not supported in transparent mode" result, not a silent failure #feature @item:DGN-1786264762917717

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
