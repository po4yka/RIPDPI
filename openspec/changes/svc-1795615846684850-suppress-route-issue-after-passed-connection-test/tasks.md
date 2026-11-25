# SVC-1795615846684850: Stop showing Route issue right after a passed connection test

## Objective

A new user who passes the onboarding connection test sees a healthy connection on the home screen, not a route warning caused by start-up noise.

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md`.
- [ ] SVC-1795615847257249 A regression test feeds start-up connection refusals like the observed ones and the route health stays healthy. #bug !high @item:SVC-1795615846684850
- [ ] SVC-1795615847721706 A persistent route fault still shows Route issue within the existing detection window. #bug !high @item:SVC-1795615846684850
- [ ] SVC-1795615848162479 On a device, finishing onboarding with a passed test shows Connected without Route issue. #bug !high @item:SVC-1795615846684850
