# Relay Profile Examples

This guide shows how RIPDPI relay profiles are split between non-secret profile fields and secure credential records.

## Data Model Split

Non-secret transport settings live in `RelayProfileRecord`. Secrets live in `RelayCredentialRecord`.

Common non-secret profile fields:

- `id`
- `kind`
- `presetId`
- `outboundBindIp`
- `jurisdiction`
- `operatorName`
- `server`
- `serverPort`
- `serverName`
- `securityLayer`
- `realityPublicKey`
- `realityShortId`
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
- `appsScriptScriptIds`
- `appsScriptGoogleIp`
- `appsScriptFrontDomain`
- `appsScriptSniHosts`
- `appsScriptVerifySsl`
- `appsScriptParallelRelay`
- `appsScriptDirectHosts`
- `ptBridgeLine`
- `ptWebTunnelUrl`
- `ptSnowflakeBrokerUrl`
- `ptSnowflakeFrontDomain`
- `localSocksHost`
- `localSocksPort`
- `udpEnabled`
- `tcpFallbackEnabled`
- `finalmask*`

Common secret fields:

- `vlessUuid`
- `hysteriaPassword`
- `hysteriaSalamanderKey`
- `tuicUuid`
- `tuicPassword`
- `anyTlsPassword`
- `shadowTlsPassword`
- `trojanPassword`
- `shadowsocksMethod`
- `shadowsocksPassword`
- `naiveUsername`
- `naivePassword`
- `masqueAuthMode`
- `masqueAuthToken`
- `masqueClientCertificateChainPem`
- `masqueClientPrivateKeyPem`
- `cloudflareTunnelToken`
- `cloudflareTunnelCredentialsJson`
- `appsScriptAuthKey`

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

## Trojan

Profile:

```json
{
  "id": "trojan-main",
  "kind": "trojan",
  "server": "trojan.example.com",
  "serverPort": 443,
  "serverName": "trojan.example.com",
  "udpEnabled": true
}
```

Credentials:

```json
{
  "profileId": "trojan-main",
  "trojanPassword": "<password>"
}
```

## AnyTLS

Profile:

```json
{
  "id": "anytls-main",
  "kind": "anytls",
  "server": "anytls.example.com",
  "serverPort": 443,
  "serverName": "anytls.example.com",
  "udpEnabled": true
}
```

Credentials:

```json
{
  "profileId": "anytls-main",
  "anyTlsPassword": "<password>"
}
```

## Shadowsocks

Profile:

```json
{
  "id": "ss-main",
  "kind": "shadowsocks",
  "server": "ss.example.com",
  "serverPort": 8388,
  "udpEnabled": true
}
```

Credentials:

```json
{
  "profileId": "ss-main",
  "shadowsocksMethod": "2022-blake3-aes-128-gcm",
  "shadowsocksPassword": "<password>"
}
```

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

## Tor With Bridge Line

Profile:

```json
{
  "id": "tor-bridge",
  "kind": "tor",
  "ptBridgeLine": "obfs4 203.0.113.10:443 FINGERPRINT cert=... iat-mode=0",
  "udpEnabled": false
}
```

Notes:

- Tor is an opt-in Arti-backed relay backend with a different anonymity and latency model from ordinary proxy relays.
- The service resolver derives `torStateDir`, `torCacheDir`, `torBridgeLines`, and pluggable-transport entries for the native config from `ptBridgeLine` and app-private storage paths.
- UDP is disabled for Tor profiles.

## Pluggable Transports

WebTunnel is the in-repository Rust `ripdpi-webtunnel` PT helper binary managed by `PluggableTransportManager`; Snowflake and obfs4 are external PT binary paths. None of these PT helpers are native relay-core backends. Snowflake remains the Go `ripdpi-snowflake` binary by decision; see [the Snowflake native Rust no-go ADR](architecture/snowflake-native-rust-decision.md).

Profile fields:

- `kind`: `snowflake`, `webtunnel`, or `obfs4`
- `ptBridgeLine` for bridge-line based PTs and Tor bootstrap
- `ptWebTunnelUrl` for WebTunnel URL input
- `ptSnowflakeBrokerUrl` and `ptSnowflakeFrontDomain` for Snowflake broker/front configuration

## Google Apps Script

Profile:

```json
{
  "id": "apps-script-main",
  "kind": "google_apps_script",
  "appsScriptScriptIds": ["script-id-a", "script-id-b"],
  "appsScriptGoogleIp": "142.250.185.142",
  "appsScriptFrontDomain": "script.google.com",
  "appsScriptSniHosts": ["script.google.com", "www.google.com"],
  "appsScriptVerifySsl": true,
  "appsScriptParallelRelay": true,
  "appsScriptDirectHosts": ["youtube.com", "ytimg.com"]
}
```

Credentials:

```json
{
  "profileId": "apps-script-main",
  "appsScriptAuthKey": "<auth-key>"
}
```

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
- Owner-operated relay promotion must satisfy the deployment-plane controls in [Relay Deployment Operations](relay-deployment-operations.md).
