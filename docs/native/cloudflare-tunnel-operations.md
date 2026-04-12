# Cloudflare Tunnel Operations

This guide covers the operational model for RIPDPI's `cloudflare_tunnel` relay kind.

## Modes

RIPDPI supports two Cloudflare Tunnel modes:

| Mode | Purpose | Runtime path |
| --- | --- | --- |
| `consume_existing` | Connect to an already published Cloudflare Tunnel hostname | Native xHTTP relay client |
| `publish_local_origin` | Publish a local loopback origin through Cloudflare Tunnel from Android | `ripdpi-cloudflare-origin` + `ripdpi-cloudflared` helper runtime |

Both modes use the `cloudflare_tunnel` relay kind. The selected mode is stored in `cloudflareTunnelMode`.

## Profile vs Secrets

Non-secret profile fields live in `RelayProfileRecord`. Secret material lives in `RelayCredentialStore` as `RelayCredentialRecord`.

Common profile fields:

- `kind = "cloudflare_tunnel"`
- `server`
- `serverName`
- `vlessTransport = "xhttp"`
- `xhttpPath`
- `xhttpHost`
- `cloudflareTunnelMode`
- `cloudflarePublishLocalOriginUrl`
- `cloudflareCredentialsRef`
- `finalmask*` fields when Finalmask is enabled

Secret material:

- `vlessUuid`
- `cloudflareTunnelToken`
- `cloudflareTunnelCredentialsJson`

The profile can keep a non-secret `cloudflareCredentialsRef`, but the actual token or named-tunnel credentials JSON stays in the secure credential store.

## Global Requirements

The supervisor enforces these requirements for all Cloudflare Tunnel profiles:

- `server` must be populated with the tunnel hostname.
- `serverName` must be populated for TLS SNI.
- TLS fingerprint policy must resolve to `chrome_stable`.
- `vlessTransport` is normalized to `xhttp`.
- UDP stays disabled on this transport family.

## `consume_existing`

Use this mode when the Cloudflare Tunnel endpoint already exists and RIPDPI only needs to connect through it.

Typical profile shape:

```json
{
  "id": "cf-consume",
  "kind": "cloudflare_tunnel",
  "server": "edge.example.com",
  "serverName": "edge.example.com",
  "vlessTransport": "xhttp",
  "xhttpPath": "/xhttp",
  "xhttpHost": "origin.example.com",
  "cloudflareTunnelMode": "consume_existing",
  "udpEnabled": false
}
```

Notes:

- This path does not launch `cloudflared`.
- It stays on the native xHTTP relay path.
- Validation and stricter preflight behavior can be rollout-controlled through the `cloudflare_consume_validation` strategy-pack feature flag.

## `publish_local_origin`

Use this mode when the Android device should publish a local loopback origin through Cloudflare Tunnel.

Additional requirements:

- Strategy-pack feature flag `cloudflare_publish` must be enabled.
- `cloudflarePublishLocalOriginUrl` must be present.
- The origin URL must:
  - use `http://`
  - target loopback only: `127.0.0.1`, `localhost`, or `::1`
  - include an explicit port
  - not include a path, query, or fragment
- Credentials must include either:
  - `cloudflareTunnelToken`, or
  - `cloudflareTunnelCredentialsJson`

Typical profile shape:

```json
{
  "id": "cf-publish",
  "kind": "cloudflare_tunnel",
  "server": "edge.example.com",
  "serverName": "edge.example.com",
  "vlessTransport": "xhttp",
  "xhttpPath": "/xhttp",
  "xhttpHost": "origin.example.com",
  "cloudflareTunnelMode": "publish_local_origin",
  "cloudflarePublishLocalOriginUrl": "http://127.0.0.1:43128",
  "udpEnabled": false
}
```

Matching credential record examples:

Token mode:

```json
{
  "profileId": "cf-publish",
  "vlessUuid": "00000000-0000-0000-0000-000000000000",
  "cloudflareTunnelToken": "<token>"
}
```

Named-tunnel credentials mode:

```json
{
  "profileId": "cf-publish",
  "vlessUuid": "00000000-0000-0000-0000-000000000000",
  "cloudflareTunnelCredentialsJson": "{\"TunnelID\":\"550e8400-e29b-41d4-a716-446655440000\"}"
}
```

## Helper Runtime

Publish mode is managed by `CloudflarePublishRuntime`.

Helpers:

- `ripdpi-cloudflare-origin`
- `ripdpi-cloudflared`

Runtime behavior:

- binaries are extracted under `filesDir/cloudflare-runtime/<abi>/`
- per-profile state lives under `filesDir/cloudflare-publish/<profileId>/`
- named-tunnel mode writes:
  - `cloudflared-credentials.json`
  - `cloudflared-config.yml`
- token mode launches `cloudflared tunnel run --token ...`

Readiness:

- the local origin helper emits `RIPDPI-READY|cloudflare-origin|...`
- the `cloudflared` metrics endpoint is polled at `http://127.0.0.1:<port>/ready`

## Telemetry

When publish mode is active, relay telemetry exposes helper state through the pluggable-transport runtime fields:

- `ptRuntimeKind = "cloudflared"`
- `ptRuntimeState = starting | running | failed`
- `ptRuntimeVersion` includes helper versions when available
- `listenerAddress` can be filled from the local origin helper
- `lastError`
- `lastFailureClass`

Common failure classes:

- `origin`
- `cloudflared`
- `helper_exit`

## Common Failure Cases

- Non-loopback publish origin URL: rejected before helper launch.
- Missing explicit origin port: rejected before helper launch.
- Missing token and missing named-tunnel credentials JSON: rejected before helper launch.
- Non-`chrome_stable` TLS fingerprint profile: rejected before runtime start.
- Tunnel hostname or `serverName` missing: rejected before runtime start.
- Helper readiness timeout: surfaced as startup failure.

## Operator Checklist

Before enabling Cloudflare Tunnel for a user-facing profile:

1. Confirm the profile uses `chrome_stable`.
2. Confirm the tunnel hostname is correct in both `server` and `serverName`.
3. Keep secrets in `RelayCredentialStore`, not in the profile payload.
4. For publish mode, validate the loopback origin URL and confirm the local origin process is listening.
5. Use strategy-pack feature flags to widen rollout instead of changing app defaults directly.
