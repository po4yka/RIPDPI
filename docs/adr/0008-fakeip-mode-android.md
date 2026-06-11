# ADR 0008: FakeIP mode on Android

> Status: Accepted (no-go for a user-facing FakeIP mode). Decision date: 2026-06-11.

## Context

The spike task `spike-fakeip-mode-compatibility-on-android` asked whether RIPDPI should
offer FakeIP as an advanced Android profile option, keeping Real IP plus a domain-mapping
cache as the production default. FakeIP hands an application a synthetic IP from a reserved
pool instead of the real resolved address, then maps that synthetic IP back to the real
target (or to a routing decision) at connect time — improving domain-aware routing at the
cost of breaking flows that assume a real IP.

**The decisive fact: RIPDPI is not greenfield for FakeIP — the mechanism already ships in
production under the name "MapDNS."** The spike's premise that "no FakeIP implementation
exists" is true only for the literal string `fakeip`; the underlying technique (synthetic-IP
pool + DNS-answer rewrite + reverse mapping) is live code:

- Synthetic-IP allocator and reverse map: [`dns_cache/state.rs`](../../native/rust/crates/ripdpi-tunnel-core/src/dns_cache/state.rs) — allocates from a synthetic pool, keeps a `rev: HashMap<u32, DnsCacheEntry>`, and pins synthetic IPs for active TCP sessions against LRU eviction.
- DNS response rewrite (real answer → synthetic): [`dns_cache/mod.rs`](../../native/rust/crates/ripdpi-tunnel-core/src/dns_cache/mod.rs) `rewrite_response`.
- Reverse rewrite (synthetic dst → real upstream before SOCKS connect), **fail-closed**: [`io_loop/dns_intercept/mapping.rs`](../../native/rust/crates/ripdpi-tunnel-core/src/io_loop/dns_intercept/mapping.rs) `resolve_mapped_target` — a reverse-lookup miss on a synthetic target **drops the connection** rather than leaking to a bogus `198.18.x.x`.
- Pool and listener: MapDNS listener `198.18.0.53:53`, synthetic pool `198.18.0.0/15`. Documented in [`docs/architecture/RUNTIME_MODES.md`](../architecture/RUNTIME_MODES.md) and [`docs/native/tunnel.md`](../native/tunnel.md).
- **Scope constraint:** MapDNS runs **only in TUN mode and only when encrypted DNS is enabled**; proxy mode (no TUN) does not run MapDNS at all ([`RUNTIME_MODES.md`](../architecture/RUNTIME_MODES.md)).

So the real question is not "build FakeIP" but "**expose MapDNS as a user-selectable profile
mode**," and the spike's compatibility analysis must be read against that.

The production default it would compete with is **Real IP + a route-aware cache**:
[`ripdpi-runtime-dns-cache/src/route_aware_cache.rs`](../../native/rust/crates/ripdpi-runtime-dns-cache/src/route_aware_cache.rs) keys answers by `(domain, qtype, RouteDecision)` and stores the **real** IPs plus `ResolverPath` and `route_decision`. The "resolver-path metadata" the task says to compare against is `enum ResolverPath { BootstrapDirect, BootstrapProxy, TunneledDoh, SystemFallback }`, remembered per network-fingerprint scope key via [`NetworkDnsPathPreferenceStore.kt`](../../core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/diagnostics/data/NetworkDnsPathPreferenceStore.kt). This already delivers domain-aware routing **without** handing apps a synthetic IP.

### Compatibility envelope (the spike's required flow coverage)

- **Browser / proxied app flows:** MapDNS already serves these correctly in TUN mode; no new evidence needed.
- **Captive portal:** a synthetic `198.18.x.x` is unreachable by the portal. RIPDPI already has a host-scoped real-IP escape during portal sign-in — [`CaptivePortalDnsAssist.kt`](../../core/service/src/main/kotlin/com/poyka/ripdpi/services/CaptivePortalDnsAssist.kt) returns a real `InetAddress` only for the exact portal host while in `CaptiveAssist`. A FakeIP *mode* would have to suppress synthesis during `CaptiveAssist`; the escape hatch exists but the interaction is an added invariant to maintain.
- **Local LAN / hardcoded-IP flows:** `bypassLan` and `bypassPrivateIp` default ON ([`DeviceProfile.kt` `ProfileRoutingConfig`](../../core/data/model/src/main/kotlin/com/poyka/ripdpi/data/model/DeviceProfile.kt)), and hardcoded-IP flows skip DNS entirely — so they never receive a synthetic IP. This is a compatibility *win* (FakeIP can't break them), but it also means FakeIP gives them **zero** routing benefit; the synthetic pool must never collide with bypassed RFC1918 ranges (the `198.18.0.0/15` benchmark range is chosen precisely to avoid that).
- **Bank / gov-direct flows:** these are exactly the VPN-detection-positive apps routed **direct** via per-package exclusion (see `adopt-process-based-per-package-routing-via-xray-tun-routeonly`); routed direct, they bypass the tunnel and MapDNS, so FakeIP is irrelevant to them.
- **IPv6:** six policy modes ([`DeviceProfile.kt` `Ipv6Policy`](../../core/data/model/src/main/kotlin/com/poyka/ripdpi/data/model/DeviceProfile.kt)). The synthetic pool is IPv4-only and MapDNS's reverse path only handles `IpAddr::V4`. FakeIP composes cleanly with the secure default `IPV4_ONLY`, but interacts non-trivially with `ALLOW`/`NATIVE` (real AAAA forwarded → no synthetic mapping → asymmetric routing) and conflicts with `TRANSLATED` (DNS64 already synthesises AAAA from A — two synthesis layers).

## Decision

**RIPDPI will not expose a user-selectable "FakeIP mode" profile option.** The FakeIP
primitive (MapDNS) stays an **internal TUN-mode mechanism**, armed by the existing
encrypted-DNS-in-TUN condition, not a profile toggle. Real IP + the route-aware cache
remains the production default and the only user-visible resolution model.

## Rationale

- **The capability already exists where it is useful.** MapDNS provides synthetic-IP domain
  routing in TUN mode today, fail-closed and TCP-session-pinned. There is no missing
  capability to ship — only a toggle to add.
- **The default already delivers the benefit FakeIP is wanted for.** The route-aware cache
  binds `(domain, qtype, route)` and carries `ResolverPath` metadata, giving domain-aware
  routing precision without handing apps synthetic IPs — so it sidesteps FakeIP's entire
  compatibility tax (captive portals, hardcoded-IP, OEM network probes).
- **A user toggle multiplies compatibility surface for marginal gain.** It would add: an
  IPv6-mode interaction matrix (`TRANSLATED` double-synthesis, `NATIVE`/`ALLOW` asymmetry),
  a new captive-portal suppression invariant, and a proxy-mode footgun (MapDNS does not run
  without TUN, so the toggle would be a silent no-op in proxy mode). The flows that would
  actually benefit are the minority that go through DNS and are neither LAN, hardcoded-IP,
  nor direct-routed — a thin slice already well served by the route-aware cache.
- **Fail-closed UX risk.** MapDNS drops connections on a reverse-map miss (correct for a
  background mechanism). As a user-selected mode, eviction-induced drops become a
  user-visible "it broke" with no obvious cause — a poor trade for the marginal routing gain.

## Alternatives Considered

### Expose FakeIP as an advanced opt-in profile mode

Rejected (this ADR). Adds the IPv6/captive-portal/proxy-mode surface above for a benefit the
route-aware Real-IP cache already provides. The fail-closed drop semantics are acceptable for
an internal mechanism but poor as a user-selected mode.

### Replace the Real-IP route-aware cache with FakeIP as the default

Rejected. The task itself fixes Real IP + domain mapping as the production default, and the
captive-portal/LAN/hardcoded-IP/OEM compatibility risks are exactly why FakeIP should not be
a default without on-device evidence that does not exist.

### Keep MapDNS internal (status quo)

Accepted. MapDNS continues to serve TUN-mode encrypted-DNS routing as an implementation
detail; no profile schema change, no new settings surface, no IPv6-mode matrix to validate.

## Consequences

- No `DeviceProfile`/settings schema change; no new user-facing toggle; no IPv6-mode
  compatibility matrix to validate on-device for a shipped feature.
- The MapDNS internals (`dns_cache`, `io_loop/dns_intercept`) remain the single owner of
  synthetic-IP routing, gated by the existing TUN + encrypted-DNS condition.
- Domain-aware routing precision continues to come from the route-aware Real-IP cache and
  `ResolverPath` metadata, which carry no synthetic-IP compatibility tax.
- The spike task is closed as a documented no-go; its stale "no implementation exists"
  work-log note is corrected to point here and at the MapDNS docs.

## Revisit Trigger

Revisit this ADR if: proxy mode (non-TUN) gains a requirement for domain-aware routing that
MapDNS cannot serve (since MapDNS is TUN-only); a measurement shows the route-aware Real-IP
cache is a material routing-precision bottleneck versus synthetic-IP mapping; a censorship
shift makes decoupling resolution from connection (FakeIP's core property) necessary for
evasion; or upstream sing-box/Xray FakeIP behavior changes in a way RIPDPI must match for
parity.

## Implementation Sketch

No production code changes in this ADR. The decision is to **not** add a FakeIP profile mode.
If a future revisit reverses this, the work is: a `DeviceProfile` resolution-mode field
(`RealIp | FakeIp`), arming the existing MapDNS path in TUN mode based on it, a captive-portal
suppression guard around synthesis, an explicit IPv6-mode interaction policy (reject or define
`TRANSLATED` + FakeIP), and a proxy-mode guard that disables/greys the toggle when TUN is off.
