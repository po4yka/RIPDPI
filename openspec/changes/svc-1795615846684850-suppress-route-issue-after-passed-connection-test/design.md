## Context

Portfolio task `SVC-1795615846684850` owns this change. A new user who passes the onboarding connection test sees a healthy connection on the home screen, not a route warning caused by start-up noise.

## Goals / Non-Goals

- Goal: deliver `Stop showing Route issue right after a passed connection test` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Root cause (audit logcat, Pixel 8 Pro, 2026-11-25 10:06:39): a tunnelled app connection maps to a host that resolves to `127.0.0.1`. The proxy dials `127.0.0.1:443`, the kernel refuses in 0 ms, and the proxy advances the route (`route advanced from=0 to=6`). The telemetry adapter already kept local-target failures out of `connection_failure_events`, but `on_route_advanced` counted every advance in `route_changes`. `RecentWarningTracker` treats any new `routeChanges` value as Route evidence for 60 s, so the home screen showed `Connected · Route issue` until the window expired.
- Fix: `ProxyTelemetryObserver::on_route_advanced` counts `route_changes` only for internet targets, the same `is_internet_target` gate that failure counting uses. A local target refuses for every desync group, so its advance says nothing about the route. The event and last-group fields are still recorded for every target, so logs keep the advance.
- The app-side Route rule and its 60 s window stay unchanged. A persistent fault on a public target still increments `route_changes` and `connection_failure_events` and surfaces in the existing window. No start-up grace period was added, because a grace period would also hide a real early fault.
- The diagnostics route-change metrics read the same counter, so they also stop counting local-target advances. That is intended: an advance for a loopback target is not a route retry.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
