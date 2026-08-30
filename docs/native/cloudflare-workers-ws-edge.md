# Cloudflare Workers WSS Edge for Telegram WS Tunnel

This guide covers the optional Cloudflare Workers WSS edge for RIPDPI's Telegram WebSocket tunnel.

The feature is intentionally scoped to the existing `wsTunnel` path. It does not create a new relay kind, does not use `cloudflared`, and does not change the direct Telegram WebSocket mode.

## Runtime model

When Worker mode is unset, RIPDPI connects directly to Telegram's official WebSocket gateways:

```text
kws{dc}.web.telegram.org:443 /apiws
```

When Worker mode is configured, RIPDPI connects to the configured Worker URL instead:

```text
<worker-host>:443 <worker-path>
```

The native client keeps TLS verification enabled and uses the Worker hostname for DNS, TCP, TLS SNI, WebSocket `Host`, and the WebSocket request URI. The canonical Telegram gateway is carried only in the internal `X-Ripdpi-Upstream` request header:

```text
X-Ripdpi-Upstream: wss://kws{dc}.web.telegram.org/apiws
```

The Worker must validate that header against the Telegram WebSocket gateway allow-list before dialing upstream.

## Settings and secrets

Non-secret settings live in `AppSettings`:

- `ws_tunnel_worker_url`
- `ws_tunnel_worker_credential_ref`

Bearer material is session-only from Android's Keystore-backed `WsTunnelWorkerCredentialStore`. It is not stored in `AppSettings`, profile backup JSON, remembered policy JSON, or task board metadata.

Both fields are required together. A Worker URL without a credential reference, or a credential reference without a Worker URL, is rejected before native runtime configuration is accepted.

## Compatibility

Worker mode is incompatible with fake-SNI tunnel mode. When `wsTunnel.fakeSni` and a Worker route are both configured, the native bootstrap fails closed before opening the connection.

## Reference Worker

The reference Worker is stored at:

```text
docs/native/cloudflare-workers/relay.js
```

It is intentionally small:

- requires `Authorization: Bearer <token>`
- accepts only `GET` requests with `Upgrade: websocket`
- validates `X-Ripdpi-Upstream` against `wss://kws{dc}.web.telegram.org/apiws`
- rejects fragments, userinfo, non-443 upstream ports, and non-`wss` upstream schemes
- fails closed with a short status code when validation fails

Set the expected token through the Worker secret named `RIPDPI_WORKER_BEARER`.

## Deployment

Create a dedicated Worker for this transport and deploy the repository script as
an ES module. With Wrangler installed and authenticated:

```bash
npx wrangler deploy docs/native/cloudflare-workers/relay.js --name ripdpi-telegram-ws-edge
npx wrangler secret put RIPDPI_WORKER_BEARER --name ripdpi-telegram-ws-edge
```

Use a long, randomly generated bearer. Rotate it by writing the new Worker
secret and then replacing the matching Android-Keystore entry before starting a
new RIPDPI session. Never put the bearer in `wrangler.toml`, AppSettings, logs,
backup exports, or remembered-policy JSON.

After deployment, configure the public `https://` or `wss://` Worker route in
`ws_tunnel_worker_url`, configure its opaque credential reference in
`ws_tunnel_worker_credential_ref`, and save the bearer under that reference via
`WsTunnelWorkerCredentialStore`. Missing, corrupt, or mismatched credential
material fails closed instead of falling back to direct Telegram traffic.

Run the repository validation before deployment:

```bash
node --check docs/native/cloudflare-workers/relay.js
node --test docs/native/cloudflare-workers/relay.test.mjs
```

## Cost and platform limits

Cloudflare meters the initial WebSocket upgrade as a Worker request; individual
messages forwarded through the established Worker WebSocket do not count as
additional Worker requests. CPU use is still metered, and plan-specific request,
CPU, memory, subrequest, and simultaneous-connection limits apply. Do not copy
numeric limits into operator automation: consult the current
[Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/)
and [Workers limits](https://developers.cloudflare.com/workers/platform/limits/)
before sizing or enabling this optional route.

Set an explicit per-invocation CPU ceiling in the Worker settings or Wrangler
configuration, monitor request and CPU usage, and configure billing alerts. The
mode remains opt-in and must never become a mandatory bootstrap or fallback hop.

## Rate limiting and abuse controls

The bearer and exact Telegram allowlist prevent an anonymous open relay, but a
leaked bearer can still cause request cost and connection pressure. Rotate a
suspected bearer immediately. Apply a Cloudflare
[Workers Rate Limiting binding](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/)
or an equivalent account policy keyed by a stable operator identifier. Avoid a
strict source-IP-only policy because mobile carrier NAT can collapse many devices
onto one address.

The reference script rejects non-WebSocket methods, non-binary frames, frames
larger than 1 MiB, and every upstream outside the exact Telegram WebSocket
gateway allowlist. Keep these checks when customizing the script.
