# ripdpi-android-fetch-adapter

**Layer:** L8 -- Android / JNI adapters.

`ripdpi-android-fetch-adapter` exposes the owned-TLS fetch path to Kotlin through JNI. It composes encrypted DNS resolution, platform socket protection, and TLS profile handling for the Android bridge.

## Dependencies

- **Upstream:** `ripdpi-dns-resolver`, `ripdpi-runtime-platform`, `ripdpi-tls-profiles`, `android-support`.
- **Downstream:** loaded through the Android native bridge, not as a standalone runtime.

## Boundaries

- JNI and Android error mapping belong here.
- DNS implementation stays in `ripdpi-dns-resolver`; TLS profile/ECH policy stays in `ripdpi-tls-profiles`; platform protection stays behind `ripdpi-runtime-platform`.

## Checks

Run focused checks with `cargo test -p ripdpi-android-fetch-adapter`.
