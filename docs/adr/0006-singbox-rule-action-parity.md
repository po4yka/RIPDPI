# ADR 0006: sing-box 1.14 rule-action model — policy DSL parity

> Status: accepted (decision spike). Decision date: 2026-06-05. Recommendation: **DIVERGE on structure, ALIGN tactic vocabulary at the export boundary.** RIPDPI's `TransportPolicy` stays a learned per-host outcome cache (not a match→action routing DSL), but the names of the desync/transport tactics it shares with sing-box are aligned so strategy expressions stay mutually legible with the peer community.

## Context

`docs/tasks/issues/` carried a spike to summarize sing-box 1.14's rule-action model and decide whether RIPDPI's direct-mode transport-policy DSL should align its vocabulary with it. The stated benefit of alignment (per `ripdpi-android-research-2026-04-20`): exchanging strategy expressions with the peer community becomes cheaper.

## sing-box 1.14 rule-action model (summary)

Source: <https://sing-box.sagernet.org/configuration/route/rule_action/>. A route rule pairs **matchers** (the rule conditions — `domain`/`domain_suffix`/`ip_cidr`/`port`/`network`/`protocol`/`process_name`/`clash_mode`/… on the [route rule](https://sing-box.sagernet.org/configuration/route/rule/) page) with an **`action`**. Actions are split into two classes:

**Final actions** (terminate matching and dispose of the connection):

- `route` — route to `outbound` (the classic behavior; carries `route-options` fields).
- `bypass` (1.13) — kernel-level bypass for Linux `auto_redirect` connections in pre-match; falls back to `route` if `outbound` is set.
- `reject` — `method`: `default` (TCP RST / ICMP port-unreachable for UDP; ICMP host-unreachable or `reply` for echo), `drop`; `no_drop` (auto-downgrades to `drop` after 50 triggers/30 s unless set).
- `hijack-dns` — divert DNS to the internal DNS module.

**Non-final actions** (run, then **continue matching** — "pre-matching"):

- `sniff` — protocol sniffing (`sniffer[]`, `timeout`) before the routing decision.
- `resolve` — resolve destination domain→IP (`server`, `strategy` ∈ {`prefer_ipv4`,`prefer_ipv6`,`ipv4_only`,`ipv6_only`}, `disable_cache`, `disable_optimistic_cache` (1.14), `rewrite_ttl`, `timeout` (1.14), `client_subnet`).
- `route-options` — per-route tactics: `override_address`/`override_port`, `network_strategy`, `fallback_delay`, `udp_disable_domain_unmapping`, `udp_connect`, `udp_timeout`, **`tls_fragment`** (1.12, TCP-segment fragmentation of the TLS handshake), `tls_fragment_fallback_delay`, **`tls_record_fragment`** (1.12, TLS record fragmentation), **`tls_spoof`** + **`tls_spoof_method`** (1.14, pre-handshake forged-ClientHello SNI desync; methods per the shared TLS `spoof_method` table).

The defining structural property is **pre-matching**: non-final actions (`sniff`/`resolve`) enrich the connection, then matching re-runs so later rules can match on the sniffed protocol / resolved IP. It is a *declarative, user-authored router*.

## RIPDPI's model (what we'd be aligning)

`core/data/model/.../TransportPolicy.kt` is **not** a router. `TransportPolicy` (+ `TransportPolicyEnvelope`) is a **learned, per-`(network, host)` outcome cache** produced by direct-mode probing and TTL'd/cooled-down:

- `quicMode` (`ALLOW`/`SOFT_DISABLE`/`HARD_DISABLE`), `preferredStack` (`H3`/`H2`/`H1`), `dnsMode`, `tcpFamily` (`SEG_*`/`REC_*`/`TWO_PHASE_SEND` — TLS segmentation/record-fragmentation families around the SNI), `outcome` (`TRANSPARENT_OK`/`OWNED_STACK_ONLY`/`NO_DIRECT_SOLUTION`).
- Envelope: `dnsClassification`, `transportClass`, `reasonCode`, `cooldownUntil`, `ipSetDigest`.

There is **no user-authored matcher→action rule DSL** in RIPDPI; routing/transport choice is *learned*, not *declared*.

## Decision

**DIVERGE on structure; ALIGN tactic vocabulary at the export boundary.**

1. **Do not adopt the rule-action structure.** sing-box's matchers + final/non-final actions + pre-matching solve a *declarative routing* problem RIPDPI does not have. Reshaping the learned `TransportPolicy` cache into a match→action DSL is a category mismatch with no functional gain (no user routing rules to express), and a large refactor of a load-bearing, serialized (`TransportPolicyEnvelope` version 1) type.

2. **Align the names of the shared desync tactics**, because that is where the interop value actually is. The tactics RIPDPI learns map almost 1:1 onto sing-box `route-options`:

   | RIPDPI concept | sing-box equivalent |
   |---|---|
   | `tcpFamily` `REC_PRE_SNI`/`REC_MID_SNI` | `tls_record_fragment` |
   | `tcpFamily` `SEG_PRE_SNI`/`SEG_MID_SNI`/`SEG_POST_SNI` | `tls_fragment` (+ `tls_fragment_fallback_delay`) |
   | relay-side `ripdpi-tls-spoof` pre-handshake SNI desync | `tls_spoof` + `tls_spoof_method` (1.14) |
   | `dnsMode` / IP-family selection | `resolve.strategy` (`prefer_ipv4`/…) |
   | `outcome = NO_DIRECT_SOLUTION` | `reject` (give up direct) — loose analogy |

3. **Keep RIPDPI-specific concepts as deliberate divergences** (record them so they are not mistaken for gaps): `quicMode` `SOFT_DISABLE`/`HARD_DISABLE`, the learned `outcome`/`OWNED_STACK_ONLY` verdict vocabulary, `cooldownUntil`, `ipSetDigest`. sing-box has no equivalent because it is a static-config router, not a learning client.

The net interop win (cheaper strategy-expression exchange) is captured by (2) without paying for (1).

## Import/export sketch

A thin, additive **strategy-expression (de)serialization layer** at the import/export boundary — *not* a change to `TransportPolicy` itself:

- An export mapper: `TransportPolicy` → a sing-box-vocabulary `route-options`-shaped JSON fragment (`tcpFamily` → `tls_fragment`/`tls_record_fragment`; the tls_spoof work → `tls_spoof`/`tls_spoof_method`; `dnsMode` → `resolve.strategy`).
- An import mapper for the reverse, tolerant of sing-box fields RIPDPI does not model (ignored, not errored).
- This lives next to the strategy-pack pipeline (the existing `CensorLab-style offline strategy-pack` work), leaves `TransportPolicyEnvelope` version 1 untouched, and needs no schema bump.
- The implemented relay-side `ripdpi-tls-spoof` config follows the `tls_spoof` / `tls_spoof_method` vocabulary; any future strategy-expression mapper should reuse that surface rather than inventing a parallel one.

## References

- sing-box rule action — <https://sing-box.sagernet.org/configuration/route/rule_action/> (1.12 `tls_fragment`/`tls_record_fragment`; 1.13 `bypass`/`reject` ICMP; 1.14 `tls_spoof`/`tls_spoof_method`, `resolve.timeout`/`disable_optimistic_cache`).
- `core/data/model/src/main/kotlin/com/poyka/ripdpi/data/TransportPolicy.kt` — the learned per-host policy/envelope this ADR compares against.
- [`docs/native/proxy-engine.md`](../native/proxy-engine.md) — current `ripdpi-tls-spoof` runtime and scope.
