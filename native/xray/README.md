# native/xray

Packaging surface for the gomobile-built [libXray](https://github.com/XTLS/libXray)
Android artifact (Xray provider mode).

- `artifacts/` — **gitignored** output of `scripts/native/build-libxray.sh`
  (`libxray.aar` + `libxray-artifact.json`). Never commit binaries here.

The version pins, build script, verification gate, Gradle wiring, and the full
license/NOTICE obligations are documented in
[`docs/native/libxray-packaging.md`](../../docs/native/libxray-packaging.md).
Pins live in `gradle/libs.versions.toml` (`libxray`, `xray-core`, `gomobile`).
