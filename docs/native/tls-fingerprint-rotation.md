# TLS ClientHello fingerprint rotation

## Threat model

Nation-state DPI (Russia's TSPU, and comparable systems) increasingly classify
flows by the **exact bytes of the TLS ClientHello** — cipher-suite list,
extension order, supported groups, signature algorithms, GREASE placement —
hashed into a [JA3] or [JA4] fingerprint. Because a proxy client that always
emits one fingerprint is trivially correlated across destinations, a *stable*
ClientHello becomes a high-confidence proxy-detection signal even when the SNI,
destination IP, and ports all vary.

Rotating the impersonated browser fingerprint **per outbound connection** breaks
this correlation: each connection looks like a different real browser, so the
JA3/JA4 channel carries no cross-connection signal.

## Pool composition

`ripdpi-tls-profiles` ships one authentic ClientHello template per mimicked
browser family, each verified against reference captures by the parity tests
(`packet_parity_tests.rs`) and structural invariants (`invariants.rs`). The
default rotation pool (`RotatingProfileSelector::with_default_pool`) draws
uniformly from:

| Family  | Profile name     |
| ------- | ---------------- |
| Chrome  | `chrome_stable`  |
| Firefox | `firefox_stable` |
| Safari  | `safari_stable`  |
| Edge    | `edge_stable`    |

Additional catalog profiles (`chrome_desktop_stable`, `firefox_ech_stable`) are
available for custom pools via `RotatingProfileSelector::new`.

### Pending: iOS 18 Safari

An **iOS 18 Safari** family is intentionally *not* in the pool yet. A mobile
fingerprint must be transcribed from an authentic capture (e.g.
[refraction-networking/utls] reference data) and pass the parity tests before it
is added — a fabricated or guessed mobile ClientHello is *worse* than its
absence, because a JA3/JA4 that no real iOS Safari emits is itself a unique,
trackable signal. Adding it is gated on sourcing that reference data; see the
upstream-spec-watch cadence in `upstream-spec-watch-runbook.md`.

## How rotation works

`RotatingProfileSelector::select(authority, session_seed)` returns a profile
name. Selection is:

- **Deterministic** for a given `(authority, session_seed)` — a reconnect within
  the same logical session keeps a stable fingerprint (so it does not flap
  mid-session), derived from a SHA-256 of `authority|session_seed|profile_set_id`.
- **Uniformly distributed** across the pool as `session_seed` varies — callers
  pass a *fresh* `session_seed` per outbound connection to rotate. The
  distribution is asserted roughly uniform over 1000 trials in
  `rotation::selector_tests`.

The resolved profile name is then passed to `configure_builder` /
`build_connector` exactly like a static profile.

## Enabling per-connection rotation

Rotation is opt-in per outbound, activated by setting the outbound's
`tls_fingerprint_profile` to the reserved marker value `"rotating"`
(`ripdpi_tls_profiles::ROTATING_PROFILE_MARKER`) instead of a concrete profile
name. At connect time the transport calls

```rust
let profile_name = ripdpi_tls_profiles::resolve_connection_profile(
    &config.tls_fingerprint_profile, // "rotating" → rotate; else pass-through
    &config.server_name,             // authority, keys the deterministic hash
);
```

`resolve_connection_profile` draws a fresh fingerprint from the default pool for
each connection (using a process-global selector and a monotonic per-connection
seed) when the marker is set, and otherwise canonicalises the configured profile
name unchanged. A single resolved name then feeds **both** the connector build
and any downstream profile-dependent decision (e.g. the REALITY ECH-parity
choice), so a rotated connection's ClientHello stays internally consistent.

Transports that honour the marker:

| Outbound        | Path                                               |
| --------------- | -------------------------------------------------- |
| VLESS + REALITY | `ripdpi-vless` `reality::connect_reality_tls_inner` |
| xHTTP (TLS)     | `ripdpi-xhttp` `connect::create_connection`        |
| xHTTP (REALITY) | inherits rotation via the VLESS path above          |

ShadowTLS and AnyTLS do **not** consume `ripdpi-tls-profiles` (they carry their
own TLS layer), so the marker is a no-op there; rotation for those transports is
out of scope for this contract.

## Telemetry

Each rotated selection increments the `tls.fingerprint_rotation_active`
counter, readable via `fingerprint_rotation_count()`, and emits a
`tracing::debug!` event tagged `tls.fingerprint_rotation_active` carrying the
chosen profile name (never any credential or destination identifier).

## Integration status

- **Done:** `RotatingProfileSelector` + default pool, deterministic
  per-connection selection, uniformity/freshness/counter tests, the
  `tls.fingerprint_rotation_active` counter, and the `"rotating"` marker wired
  through the VLESS+REALITY and xHTTP outbound call sites via
  `resolve_connection_profile` (xHTTP-over-REALITY inherits it).
- **Pending:** an authentic iOS 18 Safari profile (above); and surfacing the
  `tls.fingerprint_rotation_active` counter through the Android telemetry ring
  (the counter exists and increments; only the ring export is outstanding).

[JA3]: https://github.com/salesforce/ja3
[JA4]: https://github.com/FoxIO-LLC/ja4
[refraction-networking/utls]: https://github.com/refraction-networking/utls
