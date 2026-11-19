# Rotation note: credentials that shipped in APK assets

Task: `DAT-1795085279367518`. This note lists identifiers only. It holds no key, password, or token value. Operators rotate every item below on the server side (deploy change D4); the app side only stops new builds from shipping them.

## Embedded relay bundle (Simple experience)

- Asset path: `app/src/simple/assets/embedded-relay-bundle.json` (gitignored; CI wrote it from the secret `RIPDPI_EMBEDDED_RELAY_BUNDLE_BASE64`).
- Key kinds: WireGuard/AmneziaWG `private_key`, WireGuard `preshared_key`, static proxy `password` values for the relay outbounds in the bundle. Rotate every outbound the bundle named, not only these three fields, because the whole document was readable from the APK.
- Release versions: `v0.2.0` and `v0.2.1`. At both tags `.github/workflows/ci.yml` passes the secret to the `fdroid-simple`, `github-simple`, and `play-simple` release shards (`:app:assemble<Store>SimpleRelease`). Every APK built from those shards holds the bundle. `v0.1.4` and earlier tags do not reference the secret in `.github/`.
- Also exposed: at those revisions `ci.yml` passes the same secret to Simple unit-test, screenshot, and debug lanes. Treat any APK artifact those lanes retained as holding the same credentials.
- How to confirm per release without printing values: `unzip -l <apk> | grep embedded-relay-bundle.json` shows whether an APK holds the asset.

## Packaged preset (current builds)

- Asset path: `app/src/main/assets/preset.json` (gitignored; written by `:app:preparePreset` from AND-1795015211660121).
- Key kinds: the FULL backup `privateData` section: relay credentials, AmneziaWG private keys and preshared keys, WARP credentials, and Xray secrets.
- Release versions: none from CI; no CI job receives a preset. Any operator-built APK that packaged a preset exposes the credentials of that preset to every holder of that APK. Operators record which builds they distributed and rotate those credentials.
- From this change on, `checkShippedAssetSecrets` (and `just ci-gates`) fails while such a preset is present in a checkout. A preset that must pass the guard carries a `BOOTSTRAP` subscription with no members as its token slot; `PendingBootstrapProvisioner` fetches the credentials on first run.

## Not affected

Tracked assets under `src/main/assets` in every module pass the guard at the commit that adds it.
