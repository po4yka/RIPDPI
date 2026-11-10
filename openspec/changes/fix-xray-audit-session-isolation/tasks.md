# RLY-1794335291032951

## Objective

Isolate managed Xray UDP sessions and keep unavailable stub cleanup recoverable.

## Ownership

See the linked portfolio task for exact worktree and serialized-file ownership. Go owns patches and host tests; Rust owns the diagnostic client; stub owns unavailable bridge/tests; integrator owns planning and combined gates.

## Execution

- [ ] RLY-1794335407480209 Isolate managed Go UDP and update authenticated diagnostic clients #bug !high @item:RLY-1794335291032951
- [x] RLY-1794335408051246 Release unavailable stub ownership and preserve linked cleanup guards #bug !high @item:RLY-1794335291032951
- [ ] RLY-1794335408527493 Review and validate both fixes and artifact provenance #chore !high @item:RLY-1794335291032951

## Verification

Run actual Go managed UDP tests, diagnostic Rust tests with --locked, stub regression and linked owner-retention tests, independent review, artifact verification, and just ci-gates.
