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

- One Gradle resolver (`SharedPriorsKey` in build-logic) serves both the cargo build and `:core:service`. It takes the first non-blank value from the Gradle property, `local.properties`, then the environment variable. The key must be 64 hex characters and not all zero, otherwise the Gradle build fails.
- The `buildRustNativeLibs` task passes the key to cargo as `RIPDPI_SHARED_PRIORS_PUB_KEY`, and a changed key reruns the task. `build.rs` writes the key into `OUT_DIR`, and `manifest.rs` includes it, so compiler caches such as sccache hash the key as source content. Without a key the build embeds the all-zero key (channel disabled); a malformed or all-zero value fails the build.
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

`issued_at_unix` must be strictly greater than the stored watermark. A lower value is rejected as a rollback. An equal value is accepted only when the priors SHA-256 is identical (an idempotent re-apply); an equal value with different content is rejected as a conflicting release. Keep the private key offline; only the public half goes into the build settings above.
