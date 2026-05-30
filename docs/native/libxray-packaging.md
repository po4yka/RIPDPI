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

> **Versioning note (verified upstream 2026-05-30).** libXray uses **CalVer**
> git tags (`vYY.M.D`, e.g. `v26.3.27`) that track xray-core — there is no
> semver `1.x` line. The `xray-core` pin is the **go.mod module version** that
> libXray vendors, which differs from the git tag: libXray `v26.3.27` vendors
> `github.com/xtls/xray-core v1.260327.0` (tag `v26.3.27` ↔ module
> `v1.260327.0`). The build script's drift gate compares the pin to the go.mod
> value, so `xray-core` is pinned as `1.260327.0`. These pins were validated in
> the container lane below (libXray clone + xray-core drift gate pass on
> Go 1.26.3 / NDK 29).

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

## Host architecture (x86_64 only)

`gomobile bind` invokes the NDK **host** clang, and the Android NDK ships host
toolchains for x86_64 only (`linux-x86_64` / `darwin-x86_64`) — there is no
`linux-aarch64` prebuilt. On an arm64 Linux host gomobile aborts with
`panic: unsupported GOARCH: arm64`. The build script guards this and exits
non-zero on a non-x86_64 host before reaching gomobile.

Consequences:

- **x86_64 Linux / Intel mac / amd64 CI:** runs natively.
- **Apple Silicon (arm64):** run the container lane below under amd64 emulation
  (`--platform linux/amd64`, requires a working `binfmt`/`qemu-user`), or use an
  x86_64 CI runner. The verify script (pure shell) runs on any arch.

## Container build lane

`scripts/native/libxray-build.Dockerfile` is the reproducible toolchain image
(Go 1.26 + Android SDK 36 + NDK 29 + pinned gomobile). It was used to verify the
pins above end-to-end (toolchain + libXray `v26.3.27` clone + xray-core drift
gate) on 2026-05-30.

```sh
# Build the toolchain image (x86_64; add --platform linux/amd64 on Apple Silicon)
docker build --platform linux/amd64 \
  -f scripts/native/libxray-build.Dockerfile -t ripdpi/libxray-build .

# Build + verify the AAR (worktree mounted read-only; artifacts in the container)
docker run --rm --platform linux/amd64 -v "$PWD":/work:ro \
  -e RIPDPI_XRAY_AAR_DIR=/artifacts ripdpi/libxray-build \
  -c 'cd /work && bash scripts/native/build-libxray.sh \
      && bash scripts/native/verify-libxray-artifacts.sh'
```

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

The summed per-ABI `.so` payload must stay under **160 MiB** (`167772160` bytes),
overridable for a documented bump via `RIPDPI_XRAY_PAYLOAD_BUDGET_BYTES`. The
build strips debug symbols (`gomobile bind -ldflags="-s -w"`); measured for
libXray `v26.3.27` the stripped payload is **~126 MiB** across the 4 ABIs
(~32 MiB each), so the budget leaves ~27% headroom for xray-core growth while
still failing an unstripped build (~178 MiB) or an accidental geo-asset bundle.
ABI splits ship one `.so` per device (~32 MiB), not all four. Geo assets (`geoip.dat` / `geosite.dat`) are
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
