# libXray Android packaging

Reproducible, pinned, auditable packaging of the [libXray](https://github.com/XTLS/libXray)
gomobile wrapper around [Xray-core](https://github.com/XTLS/Xray-core) for the
Xray provider mode. The build produces an Android `.aar` (per-ABI `.so`
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

The build script reads these pins and fails if libXray's `go.mod` vendors a
different xray-core than `xray-core` declares (stable channel). The verify
script fails if a produced artifact's manifest drifts from any pin.

## Stable vs canary update policy

**Stable** (default; what ships):

- Bump `libxray` + `xray-core` **together** to a tagged upstream release in its
  own PR. libXray is pinned to the xray-core release it vendored — never bump
  one without the other.
- Link the upstream changelog in the PR.
- Re-build the full ABI set, run `scripts/native/verify-libxray-artifacts.sh`,
  and re-run the REALITY / XHTTP ground-truth tests before merge.

**Canary** (opt-in, never shipped):

- `libxray-canary` / `xray-core-canary` may point at a commit SHA or
  pre-release tag for the recurring upstream REALITY / ECH / XHTTP watch.
- `scripts/native/build-libxray.sh --channel canary` builds them.
- A canary artifact is **rejected** by `verify-libxray-artifacts.sh --release`
  and by the `:core:engine:verifyLibXrayArtifacts` task when a
  `release`/`bundle`/`publish` task is in the Gradle graph.

## Build

```sh
# Full release ABI set (reads ripdpi.nativeAbis / minSdk / NDK from gradle.properties)
scripts/native/build-libxray.sh

# Local iteration, single ABI
scripts/native/build-libxray.sh --abis arm64-v8a

# Upstream-watch canary (never ship)
scripts/native/build-libxray.sh --channel canary

# Toolchain preflight only
scripts/native/build-libxray.sh --check-toolchain
```

Requires Go + gomobile + NDK (pinned by `ripdpi.nativeNdkVersion`). The script
is fail-closed: a missing toolchain exits non-zero with install instructions
and never produces a partial/stub artifact. ABI/SDK/NDK values come only from
`gradle.properties` — they are not hardcoded in the script.

Output (gitignored) lands in `native/xray/artifacts/`:

- `libxray.aar` — gomobile AAR with `jni/<abi>/*.so`
- `libxray-artifact.json` — manifest the verify gate diffs against the pins

Override the output dir with `RIPDPI_XRAY_AAR_DIR=...`.

## Verify (runs anywhere — pure shell, no Go needed)

```sh
scripts/native/verify-libxray-artifacts.sh            # local/CI gate
scripts/native/verify-libxray-artifacts.sh --release  # also reject canary channel
./gradlew :core:engine:verifyLibXrayArtifacts         # Gradle wiring
```

Fails on: missing artifact dir / AAR / manifest, a missing `.so` for any ABI in
`ripdpi.nativeAbis`, version drift vs the pins, a native payload over the
budget, or a canary manifest in a release-like build.

### Native payload byte budget

The summed per-ABI `.so` payload must stay under **90 MiB** (`94371840` bytes),
overridable for a documented bump via `RIPDPI_XRAY_PAYLOAD_BUDGET_BYTES`. The
budget is generous (xray-core + libXray + BoringSSL across 4 ABIs is large) but
bounded so an accidental geo-asset bundle or debug-symbol leak fails CI rather
than bloating the APK silently. Geo assets (`geoip.dat` / `geosite.dat`) are
**not** bundled into the AAR — they are delivered separately to keep the native
payload bounded (see size note below).

## Gradle wiring (no binary churn)

`:core:engine` registers `verifyLibXrayArtifacts` (an `Exec` task over the
verify script) with the artifact directory and pins as Gradle inputs. The
artifact dir is `native/xray/artifacts` by default, overridable with
`-Pripdpi.prebuiltXrayAarDir=...`. The task is **not** wired into `assemble` so
offline / native-less builds (no NDK 29, no gomobile) keep working; CI and
release packaging invoke it explicitly.

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
