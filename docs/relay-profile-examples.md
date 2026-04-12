# Relay Profile Examples

This guide shows how RIPDPI relay profiles are split between non-secret profile fields and secure credential records.

## Data Model Split

Non-secret transport settings live in `RelayProfileRecord`.
Secrets live in `RelayCredentialRecord`.

Common non-secret profile fields:

- `id`
- `kind`
- `server`
- `serverPort`
- `serverName`
- `vlessTransport`
- `xhttpPath`
- `xhttpHost`
- `cloudflareTunnelMode`
- `cloudflarePublishLocalOriginUrl`
- `masqueUrl`
- `masqueUseHttp2Fallback`
- `masqueCloudflareGeohashEnabled`
- `tuicZeroRtt`
- `tuicCongestionControl`
- `shadowTlsInnerProfileId`
- `naivePath`
- `udpEnabled`
- `tcpFallbackEnabled`
- `finalmask*`

Common secret fields:

- `vlessUuid`
- `hysteriaPassword`
- `hysteriaSalamanderKey`
- `tuicUuid`
- `tuicPassword`
- `shadowTlsPassword`
- `naiveUsername`
- `naivePassword`
- `masqueAuthMode`
- `masqueAuthToken`
- `masqueClientCertificateChainPem`
- `masqueClientPrivateKeyPem`
- `cloudflareTunnelToken`
- `cloudflareTunnelCredentialsJson`

## VLESS Reality Over xHTTP

Profile:

```json
{
  "id": "xhttp-main",
  "kind": "vless_reality",
  "server": "origin.example.com",
  "serverPort": 443,
  "serverName": "edge.example.com",
  "realityPublicKey": "public-key",
  "realityShortId": "abcd1234",
  "vlessTransport": "xhttp",
  "xhttpPath": "/xhttp",
  "xhttpHost": "origin.example.com",
  "udpEnabled": false
}
```

Credentials:

```json
{
  "profileId": "xhttp-main",
  "vlessUuid": "00000000-0000-0000-0000-000000000000"
}
```

Finalmask-capable example files:

- [xHTTP client profile](examples/finalmask/xhttp-client-profile.json)
- [xHTTP server example](examples/finalmask/xhttp-finalmask-server.json)

## Cloudflare Tunnel `consume_existing`

Profile:

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

Credentials:

```json
{
  "profileId": "cf-consume",
  "vlessUuid": "00000000-0000-0000-0000-000000000000"
}
```

Finalmask-capable example files:

- [Cloudflare Tunnel client profile](examples/finalmask/cloudflare-tunnel-client-profile.json)
- [Cloudflare Tunnel server example](examples/finalmask/cloudflare-tunnel-finalmask-server.json)

## Cloudflare Tunnel `publish_local_origin`

Profile:

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

Credentials can use either a token:

```json
{
  "profileId": "cf-publish",
  "vlessUuid": "00000000-0000-0000-0000-000000000000",
  "cloudflareTunnelToken": "<token>"
}
```

Or named-tunnel credentials JSON:

```json
{
  "profileId": "cf-publish",
  "vlessUuid": "00000000-0000-0000-0000-000000000000",
  "cloudflareTunnelCredentialsJson": "{\"TunnelID\":\"550e8400-e29b-41d4-a716-446655440000\"}"
}
```

See also:

- [Cloudflare Tunnel operations](native/cloudflare-tunnel-operations.md)

## MASQUE With Bearer Auth

Profile:

```json
{
  "id": "masque-bearer",
  "kind": "masque",
  "masqueUrl": "https://masque.example/.well-known/masque/ip",
  "masqueUseHttp2Fallback": true,
  "udpEnabled": true
}
```

Credentials:

```json
{
  "profileId": "masque-bearer",
  "masqueAuthMode": "bearer",
  "masqueAuthToken": "<token>"
}
```

## MASQUE With Cloudflare Direct mTLS

Profile:

```json
{
  "id": "masque-cf",
  "kind": "masque",
  "masqueUrl": "https://consumer-masque.cloudflareclient.com/.well-known/masque/ip",
  "masqueUseHttp2Fallback": true,
  "masqueCloudflareGeohashEnabled": true,
  "udpEnabled": true
}
```

Credentials:

```json
{
  "profileId": "masque-cf",
  "masqueAuthMode": "cloudflare_mtls",
  "masqueClientCertificateChainPem": "<certificate-chain-pem-redacted>",
  "masqueClientPrivateKeyPem": "<private-key-pem-redacted>"
}
```

Notes:

- `masqueUrl` must be a valid `https://` URL.
- Cloudflare-direct rollout is feature-gated through the strategy-pack catalog.

## NaiveProxy

Profile:

```json
{
  "id": "naive-main",
  "kind": "naiveproxy",
  "server": "proxy.example.com",
  "serverPort": 443,
  "serverName": "proxy.example.com",
  "naivePath": "/",
  "udpEnabled": false
}
```

Credentials:

```json
{
  "profileId": "naive-main",
  "naiveUsername": "user",
  "naivePassword": "pass"
}
```

Notes:

- NaiveProxy is a subprocess helper, not a JNI-embedded relay.
- UDP is not supported on this relay kind.

## Finalmask Fields

Finalmask is configured on the relay profile, not in the credential record.

Supported fields:

- `finalmaskType`
- `finalmaskHeaderHex`
- `finalmaskTrailerHex`
- `finalmaskRandRange`
- `finalmaskSudokuSeed`
- `finalmaskFragmentPackets`
- `finalmaskFragmentMinBytes`
- `finalmaskFragmentMaxBytes`

Fragment example:

```json
{
  "id": "xhttp-finalmask",
  "kind": "vless_reality",
  "vlessTransport": "xhttp",
  "finalmaskType": "fragment",
  "finalmaskFragmentPackets": 3,
  "finalmaskFragmentMinBytes": 32,
  "finalmaskFragmentMaxBytes": 96
}
```

## Validation Reminders

- Keep secrets out of exported profile payloads.
- Cloudflare Tunnel requires the `chrome_stable` TLS fingerprint profile.
- `publish_local_origin` requires a loopback HTTP URL with explicit port.
- MASQUE URLs must use `https://`.
- Finalmask support is transport-specific; unsupported combinations fail at validation time.
