# ADR-005: Cloudflare Tunnel-Mode Dispatch Boundary

**Status:** Accepted | 2026-04-27
**Deciders:** RIPDPI maintainers

## Context

The Cloudflare Tunnel relay kind supports two modes controlled by the
`cloudflareTunnelMode` field in the relay profile:

- `consume_existing` (default) — the device connects to an already-published
  Cloudflare Tunnel. The Kotlin layer builds an xHTTP config, passes it to
  `ripdpi-relay-core`, and Rust handles the full transport lifecycle.
- `publish_local_origin` — the device acts as the tunnel **origin**. Kotlin
  launches two subprocesses (`ripdpi-cloudflare-origin` and `cloudflared`) via
  `CloudflarePublishRuntime`. Rust relay-core still handles the xHTTP-over-VLESS
  transport to `cloudflared`'s local listener, exactly as it would for
  `consume_existing`.

### Facts established during P3.1 investigation (2026-04-27)

1. **UI is wired**: `app/src/main/kotlin/.../config/RelayFields.kt` renders the
   `publish_local_origin` option; `ConfigDraftValidationSupport.kt` validates it;
   `UpstreamRelaySupervisorSupport.kt` feature-gates it behind
   `StrategyFeatureCloudflarePublish`.

2. **Kotlin dispatch exists**: `UpstreamRelaySupervisor.kt` (line 126-129)
   checks `resolvedConfig.cloudflareTunnelMode ==
   RelayCloudflareTunnelModePublishLocalOrigin` and routes to
   `cloudflarePublishRuntimeFactory.create()`, which returns a
   `CloudflarePublishRuntime` instance.

3. **`CloudflarePublishRuntime` owns the mode difference entirely**:
   it starts `ripdpi-cloudflare-origin` (the xHTTP-to-VLESS bridge) and
   `cloudflared` (the tunnel daemon), waits for both to signal readiness, then
   delegates to the normal `RipDpiRelayFactory` for the xHTTP connection — the
   same factory used in `consume_existing` mode.

4. **Rust relay-core is transport-only**: for both modes, relay-core receives an
   xHTTP config pointing at a loopback address and runs the VLESS-over-xHTTP
   transport. It has no knowledge of — and no need to know about — whether the
   tunnel is consumed or published. The `cloudflare_tunnel_mode` and
   `cloudflare_publish_local_origin_url` fields in `ResolvedRelayRuntimeConfig`
   are forwarded through the JSON config payload but are intentionally **not
   dispatched on** inside relay-core.

5. **Tests exist**: `UpstreamRelaySupervisorTest`, `UpstreamRelayRuntimeConfigResolverTest`,
   `CloudflarePublishRuntimeTest`, and `RelayStoresTest` all exercise the
   `publish_local_origin` path. `CloudflarePublishRuntimeTest` is a unit test
   for the subprocess management layer.

6. **Documentation exists**: `docs/native/cloudflare-tunnel-operations.md` and
   `docs/relay-profile-examples.md` describe the `publish_local_origin` mode.

### Problem framing

Superficially, `ResolvedRelayRuntimeConfig` in `ripdpi-relay-core` carries
`cloudflare_tunnel_mode` and `cloudflare_publish_local_origin_url` without any
`match` on them inside the crate. This looks like an incomplete dispatch but is
not: the mode selection has already happened in Kotlin before the config reaches
Rust. The fields are present in the struct only because `ResolvedRelayRuntimeConfig`
is deserialized from the same JSON blob that Kotlin serializes for both modes,
and removing them would break the wire format without benefit.

## Decision

**Variant C — document the by-design boundary.**

The Kotlin/Rust split of responsibilities for `cloudflare_tunnel_mode` is
correct as implemented:

- The **mode dispatch lives in Kotlin** (`UpstreamRelaySupervisor`), where
  subprocess lifecycle, feature-gating, and config validation are co-located.
- **Rust relay-core is intentionally transport-blind**: it always executes an
  xHTTP/VLESS connection; the question of who set up the tunnel on the other end
  is above its abstraction layer.

No code is deleted (the feature is live and feature-gated). No dispatch is added
to relay-core (it would be dead logic — the xHTTP target is already resolved by
the time Rust sees the config). The change in this ADR is limited to:

1. Rustdoc `#[doc]` comments on the three `cloudflare_*` fields in
   `ResolvedRelayRuntimeConfig` explaining why relay-core does not dispatch on
   `cloudflare_tunnel_mode`.
2. This ADR file.

## Alternatives considered

**Variant A — delete publish_local_origin**

Rejected. The mode is reachable from UI, feature-gated behind
`StrategyFeatureCloudflarePublish`, tested in four test files, and documented in
two doc files. Removing it would delete a working, intentional feature.

**Variant B — add match in relay-core**

Rejected. Relay-core has no subprocess management capability and no access to
Android assets or file system paths needed to launch `ripdpi-cloudflare-origin`.
Adding a `match cloudflare_tunnel_mode` in Rust would produce dead branches:
for `publish_local_origin` the Kotlin side already launched the subprocesses
before `relay_runtime_start` is called; for `consume_existing` the field is
empty. The match would be misleading rather than informative.

## Consequences

- **No behaviour change.** The `publish_local_origin` feature continues to work
  exactly as before.
- **Future readers** of `ResolvedRelayRuntimeConfig` will understand why the
  fields exist and are not dispatched on, preventing a well-intentioned but
  incorrect "fix" that adds a relay-core match.
- **The Kotlin dispatch site** (`UpstreamRelaySupervisor`) remains the single
  location where tunnel-mode routing decisions are made, consistent with
  ADR-001 (Kotlin is the authoritative config contract owner).

## Owner

Kotlin dispatch: `core/service/src/main/kotlin/com/poyka/ripdpi/services/UpstreamRelaySupervisor.kt`
Runtime implementation: `core/service/src/main/kotlin/com/poyka/ripdpi/services/CloudflarePublishRuntime.kt`
Rust config struct: `native/rust/crates/ripdpi-relay-core/src/lib.rs` (`ResolvedRelayRuntimeConfig`)
