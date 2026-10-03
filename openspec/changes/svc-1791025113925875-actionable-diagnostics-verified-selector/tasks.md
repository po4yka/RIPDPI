# SVC-1791025113925875: Deliver actionable diagnostics and verified selector activation

## Objective

Implement the audited deletions and working recommendation/selector integration with evidence at the real interfaces.

## Ownership

Root owns selector/service/probe integration, diagnostics compatibility/wrappers and task state. Native writer owns Rust/manifests/lock/native docs. Detection writer owns core/detection, app detection/navigation and all locales. Writers use separate dedicated worktrees; no overlapping serialized lanes.

## Execution

## Verification

Use build-gate for compiler-backed checks. Record exact local, hosted CI and device evidence; retain unresolved requirements until exercised.
- [ ] SVC-1791025379377245 Remove native reexport crate and unused forwarding inventory #chore !high @item:SVC-1791025113925875
- [x] SVC-1791025379880611 Remove diagnostics compatibility and trivial coroutine wrappers #chore !high @item:SVC-1791025113925875
- [ ] SVC-1791025380381572 Expose typed diagnostic recommendation actions and remove empty fixes #feature !high @item:SVC-1791025113925875
- [ ] SVC-1791025380877118 Reconcile durable selector choices and switch active sessions safely #feature !high @item:SVC-1791025113925875
- [ ] SVC-1791025381376340 Validate candidate transport payload before selector ranking #feature !high @item:SVC-1791025113925875
- [ ] SVC-1791025381882198 Verify combined tree, hosted CI and available device behavior #chore !high @item:SVC-1791025113925875
