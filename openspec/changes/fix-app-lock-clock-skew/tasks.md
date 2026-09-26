# AND-1790430239664191: Stabilize app lock timers

## Objective

PIN lockout and app relock resist wall-clock changes.

## Ownership

`PinLockoutManager.kt`, `AppLockLifecycleObserver.kt`, and their direct unit
tests in `:app`. This worktree owns this task's OpenSpec artifacts.

## Execution

- [x] AND-1790430363742695 Reproduce and fix PIN lockout bypass after wall-clock change #bug !high @item:AND-1790430239664191
- [x] AND-1790430370488377 Reproduce and fix delayed app relock after wall-clock change #bug !high @item:AND-1790430239664191

## Verification

Run focused `:app` unit tests, all `:app` unit tests, `staticAnalysis`, and
`./taskctl verify AND-1790430239664191`. Device reboot validation remains an
explicit follow-up if no device is available.
