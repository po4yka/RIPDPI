# libXray Android packaging

Reproducible, pinned, auditable packaging of the [libXray](https://github.com/XTLS/libXray)
gomobile wrapper around [Xray-core](https://github.com/XTLS/Xray-core), with the
repository-owned managed-runtime and protection patches, for the Xray provider
mode. The build produces an Android `.aar` (per-ABI `.so`
payloads). **No binary is committed** — only the build path, the verification
gate, the version pins, and the license/notice obligations live in the repo.

## Pins (single source of truth)

`gradle/libs.versions.toml`, `[versions]` block:

| Pin | Meaning | Upstream | License |
| --- | --- | --- | --- |
| `libxray` | gomobile wrapper release tag | https://github.com/XTLS/libXray | Apache-2.0 |
| `xray-core` | xray-core release vendored by libXray | https://github.com/XTLS/Xray-core | MPL-2.0 |
| `gomobile` | `golang.org/x/mobile` pseudo-version | https://github.com/golang/mobile | BSD-3-Clause |
| `libxray-canary` / `xray-core-canary` | opt-in upstream-watch refs (never shipped) | — | — |

The current build accepts only the stable source and patch policy in
`native/xray/patches/manifest.json`. It checks the upstream libXray commit,
Xray-core and gRPC module checksums, and all three patch digests before applying
the patches in isolated source copies. The artifact verifier binds the AAR,
build recipe, patch manifest, `build-go.mod`, and `build-go.sum` by digest.
A matching version label alone is insufficient.

> **Versioning note (verified upstream 2026-05-30).** libXray uses **CalVer**
> git tags (`vYY.M.D`, e.g. `v26.3.27`) that track xray-core — there is no
> semver `1.x` line. The `xray-core` pin is the **go.mod module version** that
> libXray vendors, which differs from the git tag: libXray `v26.3.27` vendors
> `github.com/xtls/xray-core v1.260327.0` (tag `v26.3.27` ↔ module
> `v1.260327.0`). The build script's drift gate compares the pin to the go.mod
> value. The 2026-05-30 container check established that historical source
> relationship; it does not verify today's patched AAR. Read current pins
> from the version catalog and patch manifest.

## Stable vs canary update policy

**Stable** (default; what ships):

- Bump `libxray` + `xray-core` **together** to a tagged upstream release in its
  own PR. libXray is pinned to the xray-core release it vendored — never bump
  one without the other.
- Link the upstream changelog in the PR.
- Re-build the full ABI set, run `scripts/native/verify-libxray-artifacts.sh`,
  and re-run the REALITY / XHTTP ground-truth tests before merge.

**Canary** (opt-in, never shipped):

- `libxray-canary` / `xray-core-canary` remain watch refs in the version
  catalog. The current production builder rejects `--channel canary`.
- A future canary build needs its own reviewed source/patch contract.
  Both local and release artifact verification currently require `stable`.

## Build

Run from the repository root. Install the SDK/NDK declared in
`gradle.properties`, JDK tools including `javap`, Python 3.12+, Git, and an
**amd64 Go host toolchain**. Set `ANDROID_SDK_ROOT` to that SDK installation.
The current CI producer is `.github/actions/build-xray/action.yml`; it selects
Go `go1.27.2` and installs the exact catalog versions of `gomobile` and `gobind`.
The following bootstrap reads these values from their owners:

```sh
export GOTOOLCHAIN="$(sed -n 's/^        GOTOOLCHAIN: //p' .github/actions/build-xray/action.yml)"
export GOSUMDB=sum.golang.org
export GOPROXY=https://proxy.golang.org,direct
export GOMAXPROCS=1 GOFLAGS=-p=1
export GOBIN="$PWD/build/libxray-toolchain/bin"
gomobile_version="$(python3 -c 'import tomllib; print(tomllib.load(open("gradle/libs.versions.toml", "rb"))["versions"]["gomobile"])')"
ndk_version="$(sed -n 's/^ripdpi.nativeNdkVersion=//p' gradle.properties)"
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/$ndk_version"
mkdir -p "$GOBIN"
go install "golang.org/x/mobile/cmd/gomobile@v$gomobile_version"
go install "golang.org/x/mobile/cmd/gobind@v$gomobile_version"
export PATH="$GOBIN:$PATH"
# Do not run gomobile init: it can replace the pinned gobind with latest.
bash scripts/native/build-libxray.sh --check-toolchain
# Output must be new or empty; the default is native/xray/artifacts/.
bash scripts/native/build-libxray.sh
bash scripts/native/verify-libxray-artifacts.sh --release
```

For local single-ABI iteration, select and verify the same ABI:

```sh
RIPDPI_XRAY_AAR_DIR="$PWD/build/libxray-arm64" \
  bash scripts/native/build-libxray.sh --abis arm64-v8a
RIPDPI_XRAY_AAR_DIR="$PWD/build/libxray-arm64" \
  bash scripts/native/verify-libxray-artifacts.sh --abis arm64-v8a
```

The producer runs native protection regressions before publishing the AAR.
Preparation downloads pinned sources and dependencies; it is not an offline
bootstrap. Reuse a verified artifact set for subsequent offline builds.

Requires Go + gomobile + NDK (pinned by `ripdpi.nativeNdkVersion`). The script
is fail-closed: a missing or mismatched toolchain exits non-zero with a reason
and never produces a partial/stub artifact. ABI/SDK/NDK values come only from
`gradle.properties` — they are not hardcoded in the script.

Output (gitignored) lands in `native/xray/artifacts/`:

- `libxray.aar` — gomobile AAR with `jni/<abi>/*.so`
- `libxray-artifact.json` — schema-2 provenance and content digests
- `build-go.mod` and `build-go.sum` — verified build dependency records

Override the producer/standalone verifier output directory with
`RIPDPI_XRAY_AAR_DIR=...`. It must be new or empty for each build; an existing
set is not overwritten. Keep all four files together when reusing an artifact.

## Host architecture

The builder checks `go env GOHOSTARCH` and requires `amd64` before invoking
`gomobile bind`. On Apple Silicon, run the bootstrap with an actual amd64 Go
installation under Rosetta, or use an amd64 Linux runner. A native arm64 Go
installation remains unsupported for this producer. Verification runs on any
host architecture with Python and JDK `javap`.

## Historical container lane

`scripts/native/libxray-build.Dockerfile` records the older Go 1.26 / SDK 36
container procedure from 2026-05-30. It also runs `gomobile init`, which can
replace the pinned `gobind`. It is not the current bootstrap procedure.
Use the producer action and commands above; do not treat the historical
container check as proof of the current patched artifact.

## Verify (Python and JDK, no Go needed)

```sh
scripts/native/verify-libxray-artifacts.sh            # local/CI gate
scripts/native/verify-libxray-artifacts.sh --release  # require the full ABI set
./gradlew :core:engine:verifyLibXrayArtifacts         # Gradle wiring
```

Fails on missing files, source/patch/recipe or content digest drift, missing
required gomobile Java API signatures, invalid ELF machine/class, LOAD
segments without 16 KiB alignment, ABI coverage mismatch, and byte-budget
violations. A non-stable manifest is rejected for every build. The standalone
verifier defaults to the full ABI set from `ripdpi.nativeAbis`; use `--abis`
for a local subset and `--release` for full release verification.

### Native payload byte budget

The fixed verifier budget in `scripts/native/libxray_artifacts.py` is
**160 MiB** (`167772160` bytes), applied to both the AAR archive and the summed
native `.so` payload. There is no environment-variable budget override.
The build strips symbols and sets 16 KiB ELF alignment. Historical upstream
size measurements do not replace measurement of the current patched AAR.
Geo assets (`geoip.dat` / `geosite.dat`) are delivered separately.

## Gradle wiring (no binary churn)

`:core:engine` registers `verifyLibXrayArtifacts` and
`verifyLibXrayReleaseArtifacts`. Real APK/AAB builds link the AAR and attach
`verifyLibXrayArtifacts` to `preBuild`, so a fresh checkout without a verified
artifact set fails before packaging. Gradle verifies existing artifacts; it
does not produce the Go AAR automatically.

The artifact directory defaults to `native/xray/artifacts` and can be selected
with `-Pripdpi.prebuiltXrayAarDir=/absolute/verified-artifacts`. That directory
must contain the complete four-file set. The local task verifies the selected
packaged ABIs; release verification requires all four. CI produces the real
runtime through `.github/actions/build-xray/action.yml` and verifies it with
`--release` before consumers use it.

Only explicitly native-less validation with `ripdpi.skipNativeBuild=true`
can compile the stub when no AAR is present. A stub build does not prove Xray
runtime or APK/AAB acceptance; do not use that setting to bypass the packaging
gate.

## License / NOTICE obligations

These obligations attach to any release that ships the libXray artifact and to
its geo assets. Carry these notices in the app's open-source-licenses surface.

| Component | License | Obligation |
| --- | --- | --- |
| libXray (`XTLS/libXray`) | Apache-2.0 | Reproduce the Apache-2.0 license text + copyright notice; state changes if modified. |
| Xray-core (`XTLS/Xray-core`) | MPL-2.0 | MPL-2.0 source-availability for the covered files; preserve license headers; offer source of any modified MPL files. |
| gomobile (`golang.org/x/mobile`) | BSD-3-Clause | Reproduce the BSD-3-Clause text + copyright; no endorsement claim from the Go authors. |
| BoringSSL (transitive, via xray-core build) | ISC / OpenSSL-style | Reproduce the applicable BoringSSL/OpenSSL notices if statically linked into the produced `.so`. |
| `geoip.dat` (Loyalsoldier/v2ray-rules-dat) | CC-BY-SA-4.0 (MaxMind GeoLite2 derivative) | Attribute the dataset + MaxMind GeoLite2; share-alike on redistribution; include the MaxMind GeoLite2 EULA attribution string. |
| `geosite.dat` (Loyalsoldier/v2ray-rules-dat) | CC-BY-SA-4.0 | Attribute the dataset; share-alike on redistribution. |

Audit these notices before each release: confirm the produced `.so` did not
statically pull in an unlisted GPL/AGPL dependency, and that the geo-asset
attribution strings are present in the app's licenses screen.
