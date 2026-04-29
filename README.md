<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="120" alt="RIPDPI Logo"/>
</p>

<h1 align="center">RIPDPI</h1>

<p align="center">
  <a href="README-ru.md">Русский</a>
</p>

RIPDPI is an Android app for local network routing, diagnostics, and path optimization. It can run as a local SOCKS5 proxy or as an Android `VpnService` that redirects device traffic through RIPDPI's local engine.

The app targets Android 8.1+.

## Features

- **Proxy mode**: starts a local SOCKS5 proxy on the configured localhost port.
- **VPN mode**: routes Android app traffic through a local TUN-to-SOCKS bridge.
- **Local path optimization**: applies configurable TCP, TLS, QUIC/UDP, and DNS strategy controls before traffic leaves the device.
- **Relay profiles**: supports configured outbound relay paths including VLESS Reality, VLESS xHTTP, Cloudflare Tunnel, MASQUE, Hysteria2, TUIC v5, ShadowTLS v3, NaiveProxy, Google Apps Script, WebTunnel, obfs4, and Snowflake.
- **WARP path**: supports provisioned WARP profiles, endpoint selection, scanner settings, and WARP runtime telemetry.
- **Encrypted DNS**: supports DoH, DoT, DNSCrypt, and DoQ in VPN-related resolver paths.
- **Diagnostics**: runs active checks for connectivity, DNS behavior, TLS/HTTP failures, strategy candidates, and automatic audit workflows.
- **Home analysis**: can run a staged diagnostic workflow and surface recommended actions.
- **Policy memory**: stores validated direct-path recommendations per network fingerprint and can replay them on matching networks.
- **RIPDPI Browser**: opens selected HTTPS targets through the app-owned request path for owned-stack-only cases.
- **Runtime telemetry and logs**: records service state, route decisions, DNS failover events, diagnostics progress, and native runtime events for in-app history and support exports.
- **Optional root helper**: on rooted devices, an opt-in helper can unlock privileged packet operations such as fake RST, multi-disorder, IP fragmentation, and sequence overlap.

## Runtime Modes

### Proxy

Proxy mode is for apps that can be configured to use a SOCKS5 proxy. RIPDPI binds the configured localhost port and applies the selected strategy and relay configuration to traffic that enters through that proxy.

### VPN

VPN mode uses Android's VPN permission to route device traffic through RIPDPI. The app starts an internal local proxy endpoint, protects upstream sockets from being routed back into the VPN, and forwards traffic through the selected local strategy or relay path.

When no relay profile is configured, VPN mode does not change the public egress IP. It only redirects traffic through the on-device engine and uses encrypted DNS when configured.

When a relay profile is configured, app traffic is forwarded to the configured remote endpoint for that relay profile.

## Diagnostics

RIPDPI's diagnostics screen provides:

- raw-path and in-path scan modes
- connectivity checks
- DNS tampering and resolver comparison checks
- strategy probing for TCP and QUIC candidates
- automatic audit reports with coverage and confidence information
- workflow restrictions when the current settings prevent isolated strategy trials
- exportable diagnostic archives with summaries, structured reports, telemetry, app logs, and a manifest

Diagnostics exports are designed for troubleshooting. They do not include full packet captures, traffic payloads, or TLS secrets.

## Strategy Controls

The app exposes advanced strategy settings for:

- split and disorder behavior
- fake packet families and fake payload profiles
- TTL-related controls
- TCP flags and IPv4 ID behavior
- TLS record fragmentation and TLS fake profiles
- QUIC fake profiles and UDP behavior
- activation windows and per-network policy replay

These controls are applied by native Rust modules in the repository.

## RIPDPI Browser

The RIPDPI Browser and the app's shared owned-stack HTTP path are used for app-originated HTTPS requests.

- The browser can use the platform HTTP engine when it is available.
- If the platform path is unavailable or unsuitable, the browser falls back to RIPDPI's native owned TLS fetcher.

## Privacy Boundaries

RIPDPI records operational metadata needed for diagnostics and troubleshooting, such as network snapshots, resolver status, route decisions, scan results, service state, and native runtime events.

RIPDPI does not record:

- full packet captures
- traffic payloads
- TLS secrets

Relay traffic privacy depends on the relay endpoint and profile you configure.

## Screenshots

<p align="center">
  <img src="docs/screenshots/01-hero.png" width="200" alt="RIPDPI home screen"/>
  &nbsp;
  <img src="docs/screenshots/02-no-root.png" width="200" alt="RIPDPI without root"/>
  &nbsp;
  <img src="docs/screenshots/03-privacy.png" width="200" alt="RIPDPI privacy screen"/>
  &nbsp;
  <img src="docs/screenshots/04-controls.png" width="200" alt="RIPDPI controls"/>
</p>
<p align="center">
  <img src="docs/screenshots/05-diagnostics.png" width="200" alt="RIPDPI diagnostics"/>
  &nbsp;
  <img src="docs/screenshots/06-more-features.png" width="200" alt="RIPDPI feature overview"/>
</p>
