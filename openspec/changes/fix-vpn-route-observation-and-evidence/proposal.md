# Change: Fix VPN route observation and evidence

Task ID: `DGN-1786867116840500`

## Why

RIPDPI intentionally excludes its own package from the Android VPN to avoid a
self-loop. Android therefore returns the physical underlay as
`ConnectivityManager.activeNetwork` for RIPDPI's calling UID even while the
owner-created VPN network exists. The current diagnostics path treats that
calling-UID default as the VPN existence oracle, reports `vpnPresent=false`,
and projects a Route warning.

The attached API 36 archive proves the observers disagree: every exported
runtime snapshot reports Wi-Fi, `NOT_VPN`, and `vpnPresent=false`, while the
same archive records an Android network callback for a validated VPN and a
generation-bound cross-layer TUN return. Future archives also lack the
provenance needed to separate this observer false negative from an installed
route mismatch or a real forwarding failure.

## What Changes

- Observe a VPN network correlated to the current RIPDPI service lifecycle
  independently of the calling UID's default network and use that observation
  for VPN presence and installed-route evidence.
- Treat the self-excluded owner's physical default network as expected context,
  not as automatic evidence that the VPN route is unavailable.
- Compare privacy-safe intended and observed IPv4/IPv6 route families and keep
  route installation separate from Android validation and native forwarding
  health.
- Project Route as checking, healthy, degraded, or unavailable from explicit,
  generation-consistent evidence instead of a single coarse boolean.
- Add privacy-safe archive provenance for observer source, callback/lifecycle
  freshness, route families, categorical app-routing shape, applied tunnel
  receipt, and forwarding correlation.
- Add regression and device scenarios that distinguish a false-negative owner
  observation, a missing VPN network, a route-policy mismatch, and a degraded
  TUN data plane.
- No external JNI or diagnostics-engine wire contract is broken. Archive model
  additions remain additive; any archive schema bump and fixture update must
  follow the repository's governed golden workflow.

## Capabilities

### New Capabilities

- `vpn-route-observation`: Observe and classify the RIPDPI-owned Android VPN
  route independently of the VPN owner's process-default underlay, with
  privacy-safe diagnostic provenance.

### Modified Capabilities

- None.

## Impact

- `:core:diagnostics`: route-evidence projection, runtime snapshots, and archive
  summaries consumed from a service provider.
- `:core:service`: VPN callback state machine plus generation-bound lifecycle,
  route-plan, and app-routing receipts.
- `:core:data:model`: additive Android-type-free observation DTOs and provider
  contracts shared by service, diagnostics, and app projection.
- `:app`: Route health projection and regression tests; locale resources only
  if user-facing wording changes.
- Physical-device verification is required for API 36 callback ownership and
  lifecycle races; local unit tests do not constitute device proof.
