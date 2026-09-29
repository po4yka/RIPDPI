# Packet Strategy Runtime

This document describes the packet-strategy runtime added around the Android VPN path. It covers how saved strategy configs, Lua scripts, TUN-egress packet actions, and the optional root helper cooperate during a running VPN session.

The runtime is local to the device. Strategy configs are parsed by in-repository Rust crates, carried through the Kotlin service layer, and applied by the native proxy or tunnel before sessions leave the app process.

## Implemented Surface

The runtime now supports these packet actions in strategy configs:

| Action | Where it runs | Root helper use | Notes |
| --- | --- | --- | --- |
| `fake` | Proxy runtime and VPN TUN egress | Optional for raw packet emission | Can emit a low-TTL TCP copy while the original flow continues through the normal path. |
| `udplen` | VPN TUN egress | Required for raw packet emission on Android | Builds a UDP packet whose length field is larger than the carried payload, then sends the crafted IPv4 packet through the raw-packet path. |
| `ipv6Ext` | VPN TUN egress | Required for raw packet emission on Android | Inserts IPv6 extension headers before the transport header, reparses the extension chain, and sends the crafted IPv6 packet through the raw-packet path. |
| Lua payload Write, MODIFY, ordered Split | Existing proxy TCP/UDP sockets | None | Runs in non-root proxy and VPN sessions after complete plan validation. |
| Lua `rawsend` | VPN TUN egress | Required for raw packet emission on Android | Lets a parsed Lua strategy request an explicit raw IPv4 or IPv6 packet send. |

The same registry IDs are accepted by YAML strategy configs and by the app's saved strategy-config workflow. Saved configs can be imported, validated, stored, exported, and applied to the next active service start.

## Runtime Flow

```mermaid
flowchart TD
    A["Strategy config\nYAML or bundled Lua"] --> B["Strategy parser\nand registry IDs"]
    B --> C["Materialized\nstrategy chain"]
    C --> D["Android settings\nand saved config"]
    D --> E["ConnectionPolicyResolver"]
    E --> LP["Non-root Lua payload registry"]
    LP --> G
    E --> F{"VPN mode?"}
    F -- No --> G["libripdpi.so\nproxy runtime"]
    F -- Yes --> H["RipDpiVpnService"]
    H --> I{"Root mode enabled?"}
    I -- Yes --> J["RootHelperManager\nstart + socket readiness"]
    I -- No --> K["No privileged\nraw packet socket"]
    J --> L["Tun2SocksConfig\nrootHelperSocketPath"]
    K --> L
    L --> M["libripdpi-tunnel.so"]
    M --> N["TUN egress\npacket interceptor"]
    N --> O{"Action selected"}
    O -- fake --> P["Low-TTL TCP copy"]
    O -- udplen --> Q["UDP length-field\nvariation"]
    O -- ipv6Ext --> R["IPv6 extension\nheader insertion"]
    O -- rawsend --> S["Lua-requested\nraw packet"]
    P & Q & R & S --> T["ripdpi-runtime-platform\nsend_raw_ip_packet"]
    T --> U{"Root helper socket\nregistered?"}
    U -- Yes --> V["ripdpi-root-helper\nsend_raw_ip_packet"]
    U -- No --> W["Local platform\nraw socket attempt"]
    V & W --> X["Network interface"]
    N --> Y{"Original packet verdict"}
    Y -- "pass / forward_original: true" --> Z["Continue through SOCKS5"]
    Y -- "successful replacement or pure DROP" --> AA["Consume original"]
```

## Non-root Lua payload execution

The service extracts bundled Lua files into `<filesDir>/lua` before it creates
proxy preferences. Assets belong to `core/engine`; the installer belongs to `core/service`. Both UI
and command-line preferences carry `strategyChainYaml` and `luaScriptBaseDir` in
the optional runtime context. In non-root VPN mode, `luaSocketOwned` tells TUN
to skip Lua steps; other TUN rules remain active. Root mode retains TUN Lua.

The socket planner accepts string-return writes, payload-only `VERDICT_MODIFY`,
ordered TCP splits, and a single UDP payload replacement. It accepts a
`rawsend_dissect` TCP replacement only when all segments preserve the original
bytes, cover contiguous sequence ranges in order, have unchanged headers and
empty send options, and replace the original with `VERDICT_DROP`. This permits
bundled `multisplit` and a complete `tcpseg` replacement. It does not promise
kernel packet boundaries.

The complete plan is checked before the first write. Unsupported actions follow
`on_fail`; a partial TCP write is terminal and reports committed bytes. UDP
replacement counts as real application payload. Flow owners keep Lua state from
the first write through relay teardown, retain the logical target behind an
upstream SOCKS server, and close state on TCP or UDP flow removal. No new
outbound socket or per-packet JNI call is used.

## Root Helper Lifecycle

The root helper is opt-in and only starts when root mode is enabled. The service waits for a connectable Unix socket before publishing the path to native code, so the tunnel does not start with a stale helper path.

```mermaid
sequenceDiagram
    participant Service as RipDpiVpnService
    participant Manager as RootHelperManager
    participant Su as su shell
    participant Helper as ripdpi-root-helper
    participant Tunnel as libripdpi-tunnel.so
    participant Platform as ripdpi-runtime-platform

    Service->>Manager: ensureStarted(rootModeEnabled)
    Manager->>Su: start helper process
    Su->>Helper: exec helper with socket path
    Manager->>Helper: poll Unix socket readiness
    Helper-->>Manager: socket accepts connection
    Manager-->>Service: rootHelperSocketPath
    Service->>Tunnel: start(config with socket path)
    Tunnel->>Platform: register root helper socket
    Platform->>Helper: send_raw_ip_packet(request)
    Helper-->>Platform: result
```

Shutdown is bounded. The manager asks the helper to stop, waits briefly, and force-kills only when the process does not exit. On AOSP/userdebug devices, startup tries the normal `su -c` form first and then the `su 0 sh -c` form used by `adb root` style shells.

## TUN Egress Actions

TUN-egress actions run before the original packet is bridged to the local SOCKS5
session. Lua `rawsend` consumes the original packet by default. Set
`forward_original: true` to treat the injected packet as a sidecar; an explicit
`VERDICT_DROP` without actions consumes the original. A replacement plan consumes
the original only after every injection succeeds. Injection failure forwards the
original packet.

```mermaid
flowchart LR
    A["Packet from Android TUN"] --> B["Parse IP + transport headers"]
    B --> C["Resolve strategy chain\nfrom current config"]
    C --> D{"Action applies?"}
    D -- No --> E["Forward original\nthrough SOCKS5 bridge"]
    D -- Yes --> F["Build crafted\npacket copy"]
    F --> G{"IPv4 or IPv6?"}
    G -- IPv4 --> H["Normalize IPv4\nheader/checksum"]
    G -- IPv6 --> I["Walk extension chain\nand locate transport header"]
    H & I --> J["Raw packet sender"]
    J --> E
```

For IPv6 extension-header actions, the tunnel reparses the resulting extension chain after insertion. This keeps destination-port extraction aligned with the packet that is actually emitted.

## Verification

The non-root socket path passed native TCP/UDP tests on an ARM64 Android API 35
emulator under shell UID 2000. Tests cover split writes, per-flow state and
release, unsupported-plan plain fallback, UDP replacement, and partial TCP write
accounting. This checks native socket execution; it does not verify an APK-level
`VpnService.protect` callback.


The implemented path was verified on a rooted Android API 34 emulator with rebuilt native artifacts and a pushed `ripdpi-root-helper` binary. Captured egress showed:

- low-TTL TCP fake copy with marker payload
- UDP packet with a larger UDP length field than carried payload
- IPv6 packet with destination-options extension header
- Lua-style raw packet emission with marker payload

Relevant local checks for future changes:

```bash
./gradlew :core:engine:testDebugUnitTest :core:service:testDebugUnitTest -Pripdpi.skipNativeBuild=true
cargo test --manifest-path native/rust/Cargo.toml --locked -p ripdpi-tunnel-core -p ripdpi-root-helper
```

Use the full native build and emulator proof path when changing raw packet construction, root-helper IPC, or TUN packet parsing.
