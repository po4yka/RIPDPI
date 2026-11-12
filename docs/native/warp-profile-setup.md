# WARP profile setup

Open **Advanced settings → Cloudflare WARP**.

**Create WARP profile** generates a new keypair on the device. After you confirm,
RIPDPI sends the public key to Cloudflare and saves the returned profile. Each
registration uses a new local profile ID. Existing profiles are kept.

Cancelling a request does not remove a registration that Cloudflare has already
accepted. A saved profile does not prove that a tunnel is connected or that a
WARP+ subscription is active.

## Import a ready Zero Trust profile

**Import Zero Trust profile** reads a UTF-8 JSON file of at most 64 KiB. The
profile must already be registered with its provider. Import does not perform
browser enrollment, verify team access, or refresh a team token.

Schema version 1 uses this shape. Replace every angle-bracket value with the
actual profile material. Do not share the file: it contains private credentials.

```json
{
  "schemaVersion": 1,
  "displayName": "Work WARP",
  "organization": "<team-name>",
  "deviceId": "<registered-device-id>",
  "accessToken": "<device-access-token>",
  "privateKey": "<base64-32-byte-private-key>",
  "publicKey": "<base64-32-byte-public-key>",
  "peerPublicKey": "<base64-32-byte-peer-public-key>",
  "interfaceAddressV4": "<assigned-IPv4-address-or-CIDR>",
  "endpoint": {
    "host": "<WireGuard-endpoint-host>",
    "port": 2408
  }
}
```

Optional top-level fields are `profileId`, `clientId`, `interfaceAddressV6`, and
`refreshToken`. An endpoint may also contain numeric `ipv4` and `ipv6` fields.
The endpoint port must be between 1 and 65535. Unknown fields and unsupported
schema versions are rejected.

If `profileId` is absent, import creates a new ID. If it matches a saved profile,
the complete validated import replaces that profile and its endpoint cache.
Invalid documents do not replace saved data. The import confirmation states
this consequence before the system file picker opens.

## WARP shaping presets

Built-in presets add separate junk packets before the WireGuard handshake.
They keep the real WireGuard headers, packet lengths, and WARP reserved bytes.
Their names do not select QUIC, TLS, or DNS sessions. Off and Random add no
shaping packets. Operator hints recommend a preset but do not apply it.

Custom keeps the supported raw AmneziaWG parameters. Nonstandard headers or
prefix lengths need a peer with matching settings. A WARP account alone does
not establish that peer support. Local loopback tests do not prove compatibility
with a specific Cloudflare endpoint.
