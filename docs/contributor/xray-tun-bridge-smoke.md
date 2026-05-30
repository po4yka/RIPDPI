# Xray TUN-bridge device/emulator smoke lane

> **Status: UNVERIFIED IN CI** — this lane requires a real device or emulator
> plus a reachable Xray server. It cannot run on the offline CI toolchain
> (gomobile/libXray, NDK 29 native engine, and a server are all absent). The
> orchestration logic (`XrayProviderOrchestrator`, `XrayTunnelHandoff`) is
> covered by offline unit tests in `:core:engine-api`; this document is the
> manual lane that proves *real traffic exits via the Xray outbound*.

## What this proves

The unit tests prove the **orchestration contract**: provider selection points
the tunnel at the Xray loopback inbound, protect is registered before any
outbound, a route change restarts both halves, and DNS is owned by the tunnel
(never re-entering the TUN). They cannot prove that *packets actually leave the
device through Xray's outbound* — that needs the native stack and a server.

This lane closes that gap:

1. The VPN comes up with `VpnProviderKind.Xray`.
2. The TUN tunnel is bridged to `127.0.0.1:<localInboundPort>` (the Xray SOCKS
   inbound).
3. App traffic egresses through the Xray outbound to the configured server.
4. Xray's outbound sockets are protected, so none of that traffic loops back
   into the TUN fd.

## Preconditions

- A debug build installed on a device or emulator with the libXray AAR linked
  (`XrayNativeBridgeLibXrayImpl` active, not the fake).
- A reachable Xray server profile (VLESS/REALITY or similar) imported and
  validated by `XrayConfigValidator` (no `VLESS_FLOW_MISSING`,
  `ALLOW_INSECURE_DISABLED`, or `REALITY_XHTTP_BROKEN_AT_TAG`).
- `adb` on PATH; the device authorized.
- A server-side vantage point you can read (the Xray server's access log, or a
  packet capture at the server) to confirm the connection's *source* is the
  server's outbound, not the client's ISP.

## Procedure

### 1. Select the Xray provider and start the VPN

Import/select the Xray profile, choose Xray as the provider, and start the VPN.
Confirm the provider state machine reaches `Running`:

```sh
adb logcat -s ripdpi:* | grep -iE 'xray|provider' | grep -iE 'running|inbound'
```

You should see the local inbound become ready on `127.0.0.1:<port>` (default
`10808`) and the tunnel start AFTER that — never before.

### 2. Confirm the loopback inbound is the tunnel's upstream

```sh
adb shell 'su 0 ss -ltnp 2>/dev/null | grep 127.0.0.1:10808 || \
           su 0 netstat -ltnp 2>/dev/null | grep 127.0.0.1:10808'
```

Exactly one listener, bound to loopback only (never `0.0.0.0`). If the inbound
is bound to a routable address, STOP — that violates the loopback-hardening
invariant in `XrayTunnelHandoff` / `TunnelUpstream.Xray`.

### 3. Prove egress is via the Xray outbound (the load-bearing check)

From an app on the device (or `adb shell`), hit an echo-IP endpoint:

```sh
adb shell 'curl -s https://api.ipify.org ; echo'
```

The returned IP MUST be the **Xray server's egress IP**, not the device's ISP
IP. Cross-check against the same query with the VPN off:

```sh
# VPN off — baseline ISP IP
adb shell 'curl -s https://api.ipify.org ; echo'
```

The two IPs must differ, and the VPN-on IP must match the server's known egress.
Corroborate on the server side: the Xray access log should show the connection,
and a `tcpdump` at the server should show the outbound originating from the
server, confirming the full path `app -> TUN -> loopback SOCKS inbound -> Xray
outbound -> server -> internet`.

### 4. Prove no TUN loop (protect is working)

Watch interface counters while generating sustained traffic (e.g. a 30s
download). The TUN device's RX/TX must track the *application* traffic, not grow
without bound:

```sh
adb shell 'su 0 cat /proc/net/dev | grep -E "tun|ripdpi"'
# ... generate traffic ...
adb shell 'su 0 cat /proc/net/dev | grep -E "tun|ripdpi"'
```

If TUN counters explode far beyond the application payload, an Xray outbound
socket is being routed back into the TUN — the protect seam is mis-wired. See
`.claude/rules/vpnservice-protect-invariant.md`.

### 5. Prove DNS does not re-enter the TUN

DNS resolution is owned by the RIPDPI tunnel (see "DNS-loop ownership" below),
NOT by Xray's DNS outbound. Resolve a name and confirm the query is answered by
the tunnel's resolver path, and that no DNS packet re-enters the TUN as an
unresolved query bound for Xray:

```sh
adb shell 'nslookup example.com'   # resolves
# In a capture at the server, you should NOT see the client's plaintext DNS
# query arriving as Xray-outbound DNS for the tunnelled path.
```

### 6. Prove handover restarts both halves

Trigger a network handover (toggle Wi-Fi <-> cellular, or change the configured
inbound port) and confirm BOTH Xray and the tunnel restart:

```sh
adb logcat -s ripdpi:* | grep -iE 'handover|restart|xray.*stop|tunnel.*stop'
```

Expected order on a route change: **tunnel stops -> Xray stops -> Xray starts ->
inbound ready -> tunnel starts**. After the handover, re-run step 3; egress must
still be via the Xray server.

## DNS-loop ownership — single source of truth

For the `TunToLocalInbound` topology (the only one this lane bridges):

- **The RIPDPI tunnel owns DNS resolution.** It terminates DNS locally (its
  mapdns / encrypted-DNS machinery) and hands Xray only resolved TCP/UDP connect
  targets through the SOCKS inbound.
- **Xray's own DNS outbound is NOT used for the tunnelled path.** This avoids a
  second, uncoordinated DNS path with different caching/leak/strategy behaviour
  — the "split model" called out as a risk in the task.
- **The protect seam owns socket routing.** Whatever sockets Xray *does* open
  (the relay outbound to the server) are protected before connect, so provider
  traffic never re-enters the TUN.

This split is enforced in code by `XrayTunnelHandoff`, which fixes
`TunnelUpstream.Xray.dnsOwner` to `DnsLoopOwner.Tunnel` and rejects the
`DnsLoopOwner.XrayDns` (split) model. The `DnsLoopOwner.XrayDns` value is
reserved for the future `LibXraySetTunFd` topology, where Xray owns the packet
loop and must own DNS too.

## Out of scope (explicit follow-ups)

- `libXray.SetTunFd` direct fd hand-off (`XrayTunnelTopology.LibXraySetTunFd`)
  stays a declared-but-unimplemented branch. `XrayTunnelHandoff` rejects it and
  `XrayProviderOrchestrator.start` fails fast with `HandoffOutcome.Failed`
  rather than silently doing nothing.
- Per-package routing via `tun routeonly` is tracked separately.

## Related

- `core/engine-api/.../XrayTunnelHandoff.kt` — upstream + DNS-owner decision.
- `core/engine-api/.../XrayProviderOrchestrator.kt` — dual-restart orchestration.
- `core/engine-api/.../RipDpiXrayRuntime.kt` — protect-first Xray lifecycle.
- `.claude/rules/vpnservice-protect-invariant.md` — why protect-first matters.
- `docs/tasks/issues/bridge-tun-traffic-through-xray-local-inbound.md` — task.
