# TLS ClientHello fingerprint rotation

## Scope and evidence

`ripdpi-tls-profiles` provides browser-labelled BoringSSL presets. A preset
controls parts of the ClientHello, such as cipher preferences, supported
groups, ALPN and extension permutation. The catalog does not bind each preset
to a browser release, operating system or independent capture.

The packet tests capture this crate's own builder output and compare it with
an implementation snapshot. The static configuration hash detects changes to
preset fields. Neither test proves browser parity, calculates an actual [JA3]
or [JA4], or proves compatibility with an external server. The phase11 fixture
test checks that stored metadata is internally consistent; its acceptance
labels are not runtime receipts.

`ja3_parity_target` and `ja4_parity_target` are target labels, not measured
fingerprints. Exact browser parity requires an independent capture with a
browser version, platform and provenance. That evidence must cover the
ClientHello fields and the effective consumer settings.

TLS fingerprints can contribute to connection classification. Rotation changes
the selected preset, but does not prove that a connection looks like a real
browser or prevents correlation. JA3 includes ordered ClientHello fields;
JA4 sorts some fields. Neither represents every byte or every network signal.

## Pool composition

The default rotation pool (`RotatingProfileSelector::with_default_pool`)
selects uniformly from these preset names:

| Family  | Profile name     |
| ------- | ---------------- |
| Chrome  | `chrome_stable`  |
| Firefox | `firefox_stable` |
| Safari  | `safari_stable`  |
| Edge    | `edge_stable`    |

Additional catalog presets (`chrome_desktop_stable`, `firefox_ech_stable`) are
available for custom pools through `RotatingProfileSelector::new`.

Uniform selection of names does not imply uniform selection of distinct wire
fingerprints. Chrome, Chrome desktop and Edge currently apply the same builder
settings. Other presets can also produce the same JA4 after consumer overrides.
The Safari preset is an approximation; it does not reproduce Safari GREASE or
its full extension layout.

### Pending: iOS 18 Safari

An iOS 18 Safari preset is not in the pool. Adding one requires an independent
capture with a version and platform, then comparison of the generated output
with that capture. [refraction-networking/utls] can provide reference data, but
a template or implementation snapshot alone is not a browser capture receipt.
See the upstream-spec-watch cadence in `upstream-spec-watch-runbook.md`.

## Effective consumer settings

Consumers can change the configured preset. For example, xHTTP uses h2-only
ALPN and can replace supported groups; REALITY adds signature and group
requirements; owned HTTP fetch uses http/1.1-only ALPN. These changes affect the
ClientHello and can change its fingerprint.

Record choreography metadata describes a separate packet transformation. The
BoringSSL builder does not apply that transformation to its connection.
Likewise, ECH capability metadata does not enable ECH or perform DNS discovery.
An ECH consumer must configure the backend separately.

## TLS 1.3 cipher-order limit

The `ciphersuites_tls13` field records a target order; the connector does not
apply it. The pinned BoringSSL C API provides `SSL_CTX_set1_tls13_ciphers` and
`SSL_set1_tls13_ciphers`, but `boring` 5.3.0 has no safe wrapper for these APIs.
This is a limit of the Rust integration, not of the C backend.

The backend default depends on AES hardware support. With AES hardware, its
client order is AES-128-GCM, AES-256-GCM, then ChaCha20-Poly1305. Without AES
hardware, it is ChaCha20-Poly1305, AES-128-GCM, then AES-256-GCM. The current
connector therefore does not implement the declared Firefox TLS 1.3 order.
An exact browser-order claim must be checked against the effective output and
hardware policy.

## How rotation works

`RotatingProfileSelector::select(authority, session_seed)` returns a preset
name. Selection is:

- Deterministic for a given `(authority, session_seed)`, using a SHA-256 of
  `authority|session_seed|profile_set_id`. Reusing that input selects the same
  name; it does not freeze randomized ClientHello fields.
- Uniform across pool entries as `session_seed` varies. Callers pass a fresh
  seed per outbound connection to rotate. The distribution test checks a
  sample of 1000 selections in `rotation::selector_tests`.

The resolved name is then passed to `configure_builder` or `build_connector`.

## Enabling per-connection rotation

Rotation is opt-in per native outbound. Set `tls_fingerprint_profile` to the
reserved marker `"rotating"` (`ripdpi_tls_profiles::ROTATING_PROFILE_MARKER`)
instead of a fixed preset name. At connect time the transport calls:

```rust
let profile_name = ripdpi_tls_profiles::resolve_connection_profile(
    &config.tls_fingerprint_profile,
    &config.server_name,
);
```

For the marker, `resolve_connection_profile` selects a name from the default
pool with a monotonic per-connection seed. For fixed names, it returns the
canonical preset name. The same result feeds both the connector and later
profile-dependent decisions, such as the REALITY ECH policy.

Transports that support the marker:

| Outbound        | Path                                               |
| --------------- | -------------------------------------------------- |
| VLESS + REALITY | `ripdpi-vless` `reality::connect_reality_tls_inner` |
| xHTTP (TLS)     | `ripdpi-xhttp` `connect::create_connection`        |
| xHTTP (REALITY) | inherits selection through the VLESS path above    |

The ordinary Android profile normalizer accepts the six fixed presets and the
default alias; it does not expose the native rotation marker. The owned-client
factory has a separate selection policy that is stable per authority and
session. Do not treat it as the native per-connection selector.

ShadowTLS uses its own TLS layer. AnyTLS applies its configured preset through
`configure_builder`, but has no marker resolution path. Rotation for these
transports is outside this contract.

## Telemetry

Each rotating resolution increments `tls.fingerprint_rotation_active`, which
is available through `fingerprint_rotation_count()`. This counts profile
resolutions, not successful TLS handshakes. A debug event carries the chosen
preset name without a credential or destination identifier.

## Integration status

- Implemented: default pool, deterministic selection, distribution and counter
  tests, and native marker resolution in VLESS REALITY and xHTTP.
- Pending: independent browser parity evidence for the published presets, an
  iOS 18 Safari preset, and Android telemetry-ring export of the rotation
  counter.

[JA3]: https://github.com/salesforce/ja3
[JA4]: https://github.com/FoxIO-LLC/ja4
[refraction-networking/utls]: https://github.com/refraction-networking/utls
