## Context

`proposal.md` gives the motivation. Today one subprocess, `ripdpi-naiveproxy`, serves every NaïveProxy profile:

- `NaiveProxyManager` builds a `SubprocessSocksRelayLaunchSpec`;
- credentials travel on stdin;
- the helper prints `RIPDPI-PROBE`, `RIPDPI-READY` and `RIPDPI-UPSTREAM-OK`;
- under a protect path it resolves the proxy host through protected DoH (`ripdpi_vless::endpoint_resolver::resolve_server_addrs`).

The upstream release v154.0.8037.49-5 ships `naiveproxy-plugin-v154.0.8037.49-5-<abi>.apk`. Each APK holds `lib/<abi>/libnaive.so`, a stripped PIE executable whose interpreter is `/system/bin/linker64`. It needs only libc, libdl, libm, liblog and libandroid. The arm64 copy is 7.2 MB and the APK sha256 is `3a532e68…41bd0`.

The upstream client:
- accepts `naive /path/config.json`, with JSON keys that mirror its flags;
- supports `--listen=socks://USER:PASS@127.0.0.1:PORT`, `--proxy=https://USER:PASS@HOST:PORT`, `--extra-headers` and `--host-resolver-rules="MAP host ip"`;
- trusts only the Chrome Root Store plus `/system/etc/security/cacerts`;
- has no certificate pin and does not emit the RIPDPI protocol lines.

`VpnAppExclusionPolicy` always excludes the app's own package from the TUN. Subprocess sockets therefore never loop, even though the upstream client cannot call `protect()`.

## Goals / Non-Goals

- Goal: pin-free NaïveProxy profiles use Chromium's own network stack (TLS, HTTP/2, preamble, Fast Open and padded close) with no RIPDPI-side reimplementation.
- Goal: keep the credential, loopback-auth, DNS-poisoning and fail-closed guarantees that the Rust helper has.
- Goal: pinned profiles keep using the Rust helper unchanged.
- Non-goal: building Chromium or naive from source. A source build can replace the release pin later behind the same manifest.
- Non-goal: proxy chains and redir mode.
- Follow-up scope (2026-10-30): `quic://` (HTTP/3) and `insecure-concurrency` run through the upstream client and are refused on pinned profiles; the Rust helper sends Chrome's key-share, cipher, signature and ALPN lists; the editor gains a server check (`--check`); upstream credential rejections map to the `auth` class; a bump tool and a weekly check keep the pin current.

## Decisions

- **Selection by pin.** `naiveServerPinSha256` set means the Rust helper; empty means the upstream client. The pin is the only feature the upstream client lacks. No user-facing toggle is added.
  - Rejected: a per-profile "engine" field. It needs a schema change, and nothing needs it yet.
- **Artifact pin manifest.** `native/naiveproxy-upstream/sources.json` records the release tag, the commit, and for every ABI the asset name, the APK sha256 and the extracted `libnaive.so` sha256.
  - `scripts/ci/fetch-naive-upstream.py` downloads the four APKs into a gitignored `native/naiveproxy-upstream/artifacts/<abi>/`. It verifies both digests, extracts only `lib/<abi>/libnaive.so`, and refuses unsafe zip paths.
  - Gradle never downloads. `HelperExecutableJniLibsTask` stages `artifacts/<abi>/libnaive.so` as `libripdpi-naive-chromium.so`, re-verifying the ELF digest. It fails the build when a file is missing or mismatched, and the error names the fetch command.
  - This follows the existing gitignored-artifact pattern (libXray provenance check). Builds stay offline after one fetch.
  - Rejected: a Gradle download task. It would put network access into ordinary builds.
- **Credentials through a private config file.** The launcher writes `noBackupFilesDir/naive/<random>.json` with mode 0600, inside the app-private, UID-only directory. The file holds `listen`, `proxy` (user and password percent-encoded), `extra-headers`, `host-resolver-rules` and `log` (empty, which means stderr).
  - argv is `[exe, path]`.
  - The file is deleted once readiness succeeds, because upstream reads its config only at startup. It is also deleted on any start failure and on stop.
  - The redaction list covers every credential, the same as now.
  - Rejected: `/proc/self/fd/0`. Upstream's file read is not guaranteed to accept pipes, and this would add an untested dependency on its behavior.
- **Protected DNS through the Rust helper.** `ripdpi-naiveproxy --resolve HOST --server-port N` reuses `resolve_server_addrs` and prints `RIPDPI-RESOLVED|<ip>[,<ip>…]`, then exits.
  - Kotlin runs it before the upstream launch, but only for hostname servers, and builds `MAP host ip` with IPv6 in brackets.
  - With socket protection active (VPN mode) the lookup is protected DoH. In proxy-only mode there is no protect path, so the same DoH pipeline runs without protect hooks; the system resolver is never used.
  - The answers are ordered by a bounded concurrent TCP connect test (protected sockets in VPN mode), so the single `MAP` target is a reachable address. A handover restarts the runtime, so each new network resolves again.
  - A failure or empty result rejects the start with class `dns`.
  - Rejected: the system resolver in either mode. It would regress F11 and allow DNS poisoning of the proxy host.
  - Rejected: a new Kotlin DoH client. It would duplicate the protected resolver.
- **Lifecycle.** The upstream client reuses `SubprocessSocksRelayManager`:
  - the same process supervisor (with the waitForExit yield from dfa955da3);
  - the authenticated SOCKS readiness probe and telemetry projection;
  - a version probe that checks that `--version` starts with `naive 154.0.8037.49`.
  - The Rust-only `--probe` preflight is skipped for the upstream client.
  - No `UPSTREAM-OK` line exists, so health stays "running".
- **Ignored profile fields.** The upstream client drops `naivePath` (`x-naive-path`), because forwardproxy ignores that header (F12). An outbound bind IP is refused for both clients, as today.
- **Acceptance trust.** On the emulator, the lab mounts a tmpfs copy of `/system/etc/security/cacerts` that includes the lab CA, using `adb root` before installation. This lets the upstream client verify the lab server without any app or product trust change. Pinned-profile acceptance keeps using the pin.

## Contracts and ownership

- New: `native/naiveproxy-upstream/sources.json`, its schema version 1, and the gitignore entry for `artifacts/`.
- `build-logic/convention`: `HelperExecutableJniLibsTask` and `ripdpi.android.rust-native.gradle.kts`, which give the staging source and digest check. Its tests are in `HelperExecutableJniLibsTaskTest`.
- `core/service`:
  - `NaiveProxyManager`, which handles selection;
  - a new `NaiveUpstreamConfig` writer, `NaiveProxyResolveClient`, and launch spec construction;
  - `SubprocessSocksRelayManager`, unchanged except for an optional per-spec preflight skip.
- `native/rust/crates/ripdpi-naiveproxy`: the `--resolve` mode and the `RIPDPI-RESOLVED` line, which is a helper protocol addition. `SPEC_VERSION.md` documents it.
- Workflows: `ci.yml` and `release-candidate.yml` gain a fetch-and-cache step before native staging. `docs/architecture/ARCHITECTURE.md` gets a new row in the artifact table.
- Serialized high-risk files touched: none of `Cargo.lock`, `libs.versions.toml`, protobuf, `RelayKindDescriptors`, the locale sets or the baselines.

## Risks / Trade-offs

- Trusting upstream's CI build. Mitigation: dual sha256 pins, a single reviewed release, and a provenance note in `sources.json`. A source build stays possible behind the same manifest.
- Upstream behavior drifts between releases. Mitigation: the version probe pins the exact version string, and a release bump re-runs the wire comparison and the acceptance scenarios.
- Size: +7.2 MB per ABI. Mitigation: check the per-ABI release APK size gate. The helper packaging already uses uncompressed jniLibs.
- The config file exists on disk until readiness. Mitigation: mode 0600 in the UID-private no-backup directory, deletion right after readiness, and deletion on every failure path.
- The upstream client's own DNS for targets: upstream resolves CONNECT targets remotely at the server, so no local target DNS happens. That matches the Rust helper.
- Upstream log text can contain the proxy URI. Mitigation: the manager redacts every credential value from captured output before it reaches logs.

## Migration Plan

- Forward: no data migration. Existing pin-free profiles switch to the upstream client on the next start, and pinned profiles are unchanged.
- Rollback: revert the change. Profiles and storage are untouched, so they keep working with the Rust helper.
- Compatibility: servers that require `x-naive-path` were never supported by forwardproxy. QUIC-only servers stay unsupported.
- Rollout gates:
  - the fetch script verified on all four ABIs;
  - build-logic tests;
  - core:service unit tests;
  - the Rust `--resolve` tests;
  - the host lab scenarios;
  - the `android-naiveproxy` scenario for both a pin-free and a pinned profile;
  - the wire comparison showing no HRR and the upstream JA4;
  - the release per-ABI APK size gate.

## Upstream output and health

- The upstream client logs at INFO level, including each destination (`Connection N to host:port`). A dedicated parser maps `negotiated padding type` and `closed: OK` lines to upstream-OK evidence, maps error lines to fixed messages without destinations, URLs or credentials, and drops every other line. Raw upstream text never reaches logs, telemetry or exports.
- Redaction also covers the base64 Basic form of the proxy credentials.
