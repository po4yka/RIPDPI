# Verifying the (host, NetProfile) resolver-selection cache

The fastest-resolver selection cache (`ResolverMappingCache` in
`ripdpi-runtime-dns-cache`, wired through `ripdpi-ws-bootstrap` policy) learns
which encrypted-DNS resolver succeeded for a `(host, network_scope)` pair and
prefers it on later resolutions for the same network. This note records how it
is verified, and why the on-device *effect* check is a field procedure rather
than an instrumented test.

## What is verified automatically

- **Cache logic** — host unit tests in `ripdpi-ws-bootstrap` (`policy::cache_tests`)
  cover hit/miss, scope isolation, selection precedence (capability > runtime
  DNS > learned preference > default), recording on success, and the
  no-scope no-op guard.
- **TTL + key shape** — `ripdpi-runtime-dns-cache` tests cover the family-cache
  7-day TTL and `(host, network_scope)` keying.
- **Scope plumbing** — `ripdpi_proxy_config::active_network_scope` round-trips,
  and `ripdpi-runtime-services` publishes the scope on network change.

## Why there is no instrumented cache-effect test

The app's instrumented integration harness (`IntegrationTestOverrides`) binds a
**fake** proxy runtime, so it never exercises the real native encrypted-DNS
resolver where the cache lives. Driving the real resolver on-device requires a
live VPN session (system consent UI) plus an encrypted-DNS resolution against a
real host — not something the instrumented suite can orchestrate deterministically.
Rather than ship an instrumented test that silently bypasses the cache, the
cache is made **observable** and verified in the field.

## Observability events

`ripdpi-ws-bootstrap` emits two `tracing` events on the resolution path (per
resolution — not per packet). They route to logcat via the
`android-support` `tracing -> AndroidLogLayer` bridge (tag `ripdpi-native`,
Debug level in debug builds) and to the telemetry event ring:

| Event | Fired when | Fields |
|---|---|---|
| `resolver_selection_recorded` | a successful resolver is learned for `(host, scope)` | `host`, `network_scope`, `resolver_id` |
| `resolver_selection_cache_hit` | a learned preference is applied as the default | `host`, `network_scope`, `resolver_id` |

## Field verification procedure

On a device with the debug build installed and encrypted DNS enabled:

```sh
adb logcat -c
adb logcat -s ripdpi-native | grep -E 'resolver_selection_(recorded|cache_hit)'
```

1. Start the VPN on network A; trigger encrypted-DNS resolution (e.g. open a
   tunnelled flow). Expect `resolver_selection_recorded` with network A's scope.
2. Move to network B and back to A. On the next resolution for the same host,
   expect `resolver_selection_cache_hit` carrying the `resolver_id` learned on
   network A — and a different (or absent) entry for network B, confirming
   scope isolation.

The `network_scope` value is the privacy-safe network identity hash (see
`.claude/rules/network-fingerprint-privacy.md`); no raw network identifiers are
logged.

## Links

- `native/rust/crates/ripdpi-ws-bootstrap/src/policy.rs` — selection + events.
- `native/rust/crates/ripdpi-runtime-dns-cache/src/resolver_mapping_cache.rs` — cache + TTL.
- `docs/contributor/dns-measurement-consent.md` — adjacent DNS privacy posture.
