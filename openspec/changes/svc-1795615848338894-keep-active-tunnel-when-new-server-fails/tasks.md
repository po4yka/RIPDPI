# SVC-1795615848338894: Keep the working tunnel when a new server fails to start

## Objective

A failed attempt to connect to a new server does not leave the user without the connection they had, and the error tells them what to check.

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md`.
- [ ] SVC-1795615848875322 When a server switch fails, the service restores the previous working mode, or the UI states that the device is now unprotected and offers to restore it. #bug !high @item:SVC-1795615848338894
- [ ] SVC-1795615849359538 An AmneziaWG readiness timeout shows a user message that names the likely causes (endpoint, keys, port) and a next step; the raw state stays in diagnostics only. #bug !high @item:SVC-1795615848338894
- [ ] SVC-1795615849834416 A start failure while the app is in the background posts a notification. #bug !high @item:SVC-1795615848338894
- [ ] SVC-1795615850332527 Service lifecycle tests cover a failed switch from a running tunnel. #bug !high @item:SVC-1795615848338894
