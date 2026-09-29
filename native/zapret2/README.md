# nfqws2 root backend

This directory contains the native upstream backend at commit
`ca950d838a0ee7dc32bb6ae5e65e54488c1bfe3b`. The Android service owns root
activation, process supervision, protection-server lifetime, and NFQUEUE rules.
The existing in-process Lua sandbox has separate assets and dependencies.

`sources.json` locks the upstream C headers and sources, all six Lua libraries,
payload files, dependency archives, integration patches, bridge, and license
notices with SHA256. The upstream source is unchanged. The build applies the
upstream Android netfilter patch, the NDK 29 IPv4 helper patch, and the Android
VPN protection patch only in its private output directory.

Dependencies are static PUC Lua 5.4.8, zlib 1.3.2, libmnl 1.0.5,
libnfnetlink 1.0.2, and libnetfilter_queue 1.0.5. The checked archives contain
their source and license notices. The zlib source archive omits only three
binary manuals to meet the repository file-size limit. All 251 retained files
match the official archive byte for byte. `sources.json` records the official
URL and checksum, each retained file checksum, and the exact exclusions.
`licenses/` contains the notices that are also packaged with the backend.
Builds need no network access.

To regenerate the zlib source archive from the verified official download:

```sh
python3 scripts/native/build-nfqws2.py --repack-zlib /tmp/zlib-1.3.2.tar.gz
```

## Android build

Requirements: Python 3.12+, make, patch, and Android NDK `29.0.14206865`.
The minimum Android API is 27. Both 64-bit binaries use 16 KiB ELF page alignment.

```sh
./gradlew :core:engine:buildNfqws2 \
  -Pripdpi.nativeAbisOverride=armeabi-v7a,arm64-v8a,x86,x86_64
```

The task uses the shared native ABI policy. CI and release builds include all
four ABIs. Local debug builds use the configured native ABI selection.
It writes `core/engine/build/generated/nfqws2Assets/`:

- `bin/<abi>/nfqws2` and its source/toolchain/binary hash record.
- `zapret2/lua/`, `zapret2/files/`, and `zapret2/licenses/`.
- `zapret2/sources.json` with source and dependency provenance.

The convention plugin attaches this directory to engine assets and adds the
task to native-enabled asset merge and `preBuild` tasks.

The standalone equivalent is:

```sh
python3 scripts/native/build-nfqws2.py --platform android \
  --ndk "$ANDROID_NDK_HOME" \
  --abis armeabi-v7a,arm64-v8a,x86,x86_64 \
  --work /tmp/nfqws2-build --output /tmp/nfqws2-assets
```

Use a dedicated disposable `--work` directory. The builder replaces its ABI
subdirectories. Each output records the NDK version, Android API, Lua version,
source-manifest hash, and executable hash. Android dynamic dependencies are
platform libraries; no Lua or netfilter shared library must be extracted.

## Offline validation

The Linux host build needs a C compiler, make, patch, pkg-config, Python 3.12+,
and the `sys/capability.h` development header. On macOS, use an isolated Linux
container. Host tool versions belong to that container's build configuration.

```sh
python3 scripts/native/build-nfqws2.py --platform host \
  --work /tmp/nfqws2-host-build --output /tmp/nfqws2-host-assets
python3 native/zapret2/tests/test_protect_bridge.py
python3 native/zapret2/tests/smoke_backend.py \
  --binary /tmp/nfqws2-host-assets/bin/host/nfqws2 \
  --assets /tmp/nfqws2-host-assets
```

The bridge test uses real descriptor passing through local Unix sockets. It
checks successful protection, rejected or missing ACK, timeout, and invalid
paths. It needs no root access. The backend smoke needs `CAP_NET_RAW` and
`CAP_NET_ADMIN` because upstream initializes raw sockets even with
`--intercept=0`. It checks all 101 active Linux C functions, 125 typed constants, and 235
named Lua functions. It also runs ten offline upstream helper groups, loads
all six Lua libraries, and verifies native argv. It does not add NFQUEUE rules or call the
upstream raw-send and external-routing test groups.

## Android socket protection

When `RIPDPI_PROTECT_PATH` is present, the C bridge must receive a successful
ACK before use of an outbound socket. It sends one byte and one descriptor
with `SCM_RIGHTS`, then requires ACK byte `0`. A monotonic two-second deadline
bounds the complete handshake. A missing, rejected, or timed-out ACK fails
closed. The absence of the environment variable preserves upstream behavior.

The integration patch protects IPv4/IPv6 route probes before `connect`, checks
raw sockets at initialization, and protects each raw send after `SO_MARK`.
The latter ordering is required because `SO_MARK` replaces the Android socket
protection mark. On failure, the raw-socket cache entry is closed and reset.
The Android service provides the existing protection server; the bridge does
not use Android netd APIs or hard-coded mark bits.

The root target must also permit socket descriptor transfer from the backend
domain to the app domain. Equal Linux UIDs do not change SELinux socket labels.
The API 37 AOSP test target denies app access to UDP/raw sockets created in
`su` or `runas_app`; Android reports truncated ancillary data and rejects the
handshake. The backend fails closed. Root launch permission alone does not
prove this VPN contract; verify it on the intended root framework.

## Activation

Enable root mode and command-line settings. Start the command with `nfqws2`:

```text
nfqws2 --filter-tcp=80 --payload=http_req --lua-desync=http_domcase --new --filter-tcp=443 --payload=tls_client_hello --lua-desync=multisplit:pos=1,midsld
```

The service loads all six bundled Lua libraries before the command arguments.
Keep the separate YAML payload chain empty in this mode. nfqws2 owns packet
strategies; the proxy keeps transport, routing, and DNS settings. Stored proxy
packet strategies do not run before nfqws2.

The helper intercepts app-owned underlay sockets. It owns queue 49379, private
IPv4/IPv6 mangle chains, the reinjection mark, child UID, and pidfile. The
command cannot override those controls or daemonize the child. File arguments
must resolve within the app's private files directory. This explicit root mode
runs upstream Lua with its upstream file and module behavior. Use trusted Lua
scripts. The non-root payload planner keeps its existing sandbox and limits.

Missing root or NFQUEUE rejects explicit nfqws2 activation. Ordinary profiles
keep non-root operation, including the payload Lua fallback when a requested
root helper is unavailable. Raw TCP sequence/checksum/IP mutations require the
root backend.

Shutdown, app-owner death, child exit, and SIGTERM trigger owned-rule cleanup.
A failed deletion retains cleanup ownership and retries. Queue bypass keeps
traffic flowing if the queue consumer exits. SIGKILL of the helper cannot run
its cleanup handler; a later activation rejects an existing private-chain
collision instead of deleting state whose ownership it cannot prove.
