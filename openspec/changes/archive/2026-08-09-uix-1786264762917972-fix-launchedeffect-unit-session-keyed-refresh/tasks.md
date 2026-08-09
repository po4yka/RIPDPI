# UIX-1786264762917972: Key session-scoped LaunchedEffect refreshes on the session id, not Unit

## Objective

Key session-scoped LaunchedEffect refreshes on the session id, not Unit

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- UIX-1786264762918128 DROPPED: PR confirms current state at the three cited sites #feature @item:UIX-1786264762917972
- UIX-1786264762918217 DROPPED: Each refresh LaunchedEffect keys on the data-determining argument, not Unit #feature @item:UIX-1786264762917972
- UIX-1786264762918575 DROPPED: Test (Compose/Robolectric or unit on the VM): changing the session key triggers a refresh #feature @item:UIX-1786264762917972
- UIX-1786264762918016 DROPPED: /gradlew :app:testDebugUnitTest --locked green; goldens unchanged #feature @item:UIX-1786264762917972

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
