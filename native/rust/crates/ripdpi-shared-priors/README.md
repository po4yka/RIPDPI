# ripdpi-shared-priors

**Layer:** L3 -- domain logic.

`ripdpi-shared-priors` verifies signed shared-priors bundles and manages the process-wide priors registry consumed by strategy evolution.

## Boundaries

- Bundle verification and registry mechanics belong here.
- Strategy scoring and selection belong in `ripdpi-runtime-strategy`; network fetching or Android asset policy belongs outside this crate.

## Checks

Run focused checks with `cargo test -p ripdpi-shared-priors`.

## Provisioning (owner)

The channel is off until the owner supplies a public key and two release URLs at build time. No key or URL is committed.

| Setting | `local.properties` / Gradle property | Environment variable |
| --- | --- | --- |
| ed25519 public key, 64 hex chars | `sharedPriors.publicKey` | `RIPDPI_SHARED_PRIORS_PUB_KEY` |
| Manifest URL | `sharedPriors.manifestUrl` | `RIPDPI_SHARED_PRIORS_MANIFEST_URL` |
| Priors URL | `sharedPriors.priorsUrl` | `RIPDPI_SHARED_PRIORS_PRIORS_URL` |

- The Gradle `buildRustNativeLibs` task passes the key to cargo as `RIPDPI_SHARED_PRIORS_PUB_KEY`; this crate reads it with `option_env!` at compile time. A changed key reruns the task and cargo rebuilds the crate. Unset or empty keeps the all-zero key (channel disabled); a set but malformed value fails the build.
- `:core:service` sets `BuildConfig.SHARED_PRIORS_PUBLIC_KEY_CONFIGURED`. The refresh worker downloads nothing unless the key and both URLs are set.
- Selection uses verified priors in `ripdpi-runtime-strategy`: a prior is worth at most 8 pseudo-observations, so local field data takes over as it accumulates.

## Signing a bundle

Publish `manifest.json` next to the NDJSON priors file (one `{"combo_hash", "alpha", "beta"}` record per line):

```json
{"version":1,"priors_url":"<url>","priors_sha256":"<64 hex, SHA-256 of the priors file>","issued_at_unix":<int>,"signature_ed25519":"<base64, 64 bytes>"}
```

The signature is ed25519 over exactly these bytes:

```text
b"ripdpi-shared-priors-v1\0" (24 bytes) || SHA-256(priors file) (32 bytes) || issued_at_unix as i64 little-endian (8 bytes)
```

`issued_at_unix` must increase with each release (rollback watermark). Keep the private key offline; only the public half goes into the build settings above.
