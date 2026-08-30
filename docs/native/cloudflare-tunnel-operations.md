# Cloudflare Tunnel Operations

This guide covers the operational model for RIPDPI's `cloudflare_tunnel` relay kind.

## Optional Workers WebSocket Edge

The separate optional Worker edge for the Telegram WebSocket tunnel is
configured through `wsTunnel`, not through the `cloudflare_tunnel` relay kind.
Deploy the repository-owned
[`cloudflare-workers/relay.js`](cloudflare-workers/relay.js), configure its
`RIPDPI_WORKER_BEARER` secret, then provision the public Worker URL, opaque
credential reference, and matching Keystore-backed bearer in RIPDPI. The
complete deployment and rotation procedure is in
[`cloudflare-workers-ws-edge.md`](cloudflare-workers-ws-edge.md).

Cloudflare counts the initial WebSocket upgrade as a Worker request and meters
CPU plus plan-specific request and resource limits. Operators must review the
current [Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/)
and [Workers limits](https://developers.cloudflare.com/workers/platform/limits/)
before rollout; RIPDPI does not assume that the free tier is sufficient.

The reference Worker is already closed to the exact Telegram WebSocket
allowlist and rejects unauthenticated, non-WebSocket, text-frame, and oversized
traffic. For abuse control, configure a
[Workers Rate Limiting binding](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/)
and enforce it before the upstream `fetch()`. Rate-limit keys must not contain
the bearer or other credentials.

## Modes

RIPDPI supports two Cloudflare Tunnel modes:

| Mode | Purpose | Runtime path |
| --- | --- | --- |
| `consume_existing` | Connect to an already published Cloudflare Tunnel hostname | Native xHTTP relay client |
| `publish_local_origin` | Publish a local loopback origin through Cloudflare Tunnel from Android | `ripdpi-cloudflare-origin` (in-repo) + external `cloudflared` binary |

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
- The origin URL must: - use `http://` - target loopback only: `127.0.0.1`, `localhost`, or `::1` - include an explicit port - not include a path, query, or fragment
- Credentials must include either: - `cloudflareTunnelToken`, or - `cloudflareTunnelCredentialsJson`

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

- `ripdpi-cloudflare-origin` (in-repo Rust origin helper crate)
- `cloudflared` (external Cloudflare-supplied binary — there is no `ripdpi-cloudflared` crate)

Runtime behavior:

- binaries are extracted under `filesDir/cloudflare-runtime/<abi>/`
- per-session state lives under `cacheDir/cloudflare-publish/cloudflare-publish-session-<profileId>/` and is deleted on stop or startup rollback
- `ripdpi-cloudflare-origin` launches with only `--config-stdin`; the listener, xHTTP path, and VLESS UUID are transferred in the bounded schema-1 standard-input frame documented by the helper crate
- named-tunnel mode writes `.cloudflared/cloudflared-credentials.json` and `.cloudflared/config.yml`, sets the session directory as `HOME`, and relies on cloudflared's default configuration discovery
- token mode writes `.cloudflared/tunnel-token`, passes its path through `TUNNEL_TOKEN_FILE`, and passes the loopback metrics address through `TUNNEL_METRICS`
- cloudflared launches with the fixed arguments `tunnel --no-autoupdate run`; token, UUID, xHTTP path, credential/config paths, and session paths are absent from both helper argument vectors
- inherited `TUNNEL_*`, `HOME`, and pinned cloudflared non-prefixed behavior variables are removed from origin, cloudflared, and version-probe environments before the explicit per-session allowlist is applied
- generated named-tunnel YAML accepts only a canonical tunnel UUID and valid hostname, double-quotes every scalar, and stores its directory/files with owner-only permissions
- helper output is redacted before telemetry projection using credential, identity, origin, xHTTP, and session-path values
- helper version probes use a separate absolute 2-second deadline covering process exit and concurrent bounded output capture, cap captured output at 4 KiB, close inherited stdout pipes, and fail closed if a timed-out probe or reader cannot be reaped
- every post-spawn setup failure rolls back that child; later origin/cloudflared readiness, coroutine cancellation, relay factory, or relay startup failure stops all started helpers and deletes per-session files
- unexpected exit of either helper is detected without waiting for the surviving child; shutdown attempts both helpers, retains manager/session state when a child cannot be reaped, and permits an explicit cleanup retry

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

Operational interpretation:

- `origin`: local xHTTP origin helper failed before or during publish setup.
- `cloudflared`: `cloudflared` emitted an operator-actionable error line.
- `helper_exit`: one of the publish-mode helpers exited unexpectedly after launch.

## Common Failure Cases

- Non-loopback publish origin URL: rejected before helper launch.
- Missing explicit origin port: rejected before helper launch.
- Missing token and missing named-tunnel credentials JSON: rejected before helper launch.
- Non-`chrome_stable` TLS fingerprint profile: rejected before runtime start.
- Tunnel hostname or `serverName` missing: rejected before runtime start.
- Helper readiness timeout: surfaced as startup failure.
- Helper version probe timeout: the probe child is forcibly terminated and startup continues without a version string; it does not consume the longer publish-readiness budget.

## Operator Checklist

Before enabling Cloudflare Tunnel for a user-facing profile:

1. Confirm the profile uses `chrome_stable`.
2. Confirm the tunnel hostname is correct in both `server` and `serverName`.
3. Keep secrets in `RelayCredentialStore`, not in the profile payload.
4. For publish mode, validate the loopback origin URL and confirm the local origin process is listening.
5. Use strategy-pack feature flags to widen rollout instead of changing app defaults directly.
6. When telemetry reports `ptRuntimeState=failed`, use `lastFailureClass` to separate origin misconfiguration from `cloudflared` tunnel failures before retrying the profile.
