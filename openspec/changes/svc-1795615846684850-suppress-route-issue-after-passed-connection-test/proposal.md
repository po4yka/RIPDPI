# Change: Stop showing Route issue right after a passed connection test

Task ID: `SVC-1795615846684850`

## Why

A new user who passes the onboarding connection test sees a healthy connection on the home screen, not a route warning caused by start-up noise.

## What Changes

- A regression test feeds start-up connection refusals like the observed ones and the route health stays healthy.
- A persistent route fault still shows Route issue within the existing detection window.
- On a device, finishing onboarding with a passed test shows Connected without Route issue.

## Capabilities

### New Capabilities

- `svc-1795615846684850-suppress-route-issue-after-passed-connection-test`: Stop showing Route issue right after a passed connection test

### Modified Capabilities

- None.

## Impact

- Portfolio area: `service`.
- Exact code, contracts, and gates are constrained by the linked task and design.
