# `amneziawg://` share-URI scheme

RIPDPI defines a local `amneziawg://` URI scheme for ergonomic single-profile sharing of AmneziaWG profiles (link, QR code, clipboard import).

## Rationale

There is **no standardized AmneziaWG share-URI scheme upstream**. Neither `amneziawg-android` nor `amneziawg-go` defines one — both share profiles as `.conf` files or as a QR code of a `.conf`. A `.conf` file is awkward to paste into a chat or embed in a link, so RIPDPI invents `amneziawg://` for the same ergonomics the proxy schemes (`vless://`, `hysteria2://`, …) already provide.

The layout follows the Hysteria2 URI shape: the secret in userinfo, the endpoint as `host:port`, auxiliary fields as query params, the display name as the fragment. All AWG-specific query-param names are defined by this document.

## Layout

```
amneziawg://<private-key>@<host>:<port>
  ?wire_profile=awg-3.1-ranges-v1&public_key=<key>
  &preshared_key=<key>
  &address=<cidr,cidr>
  &allowed_ips=<cidr,cidr>
  &dns=<ip,ip>
  &mtu=<n>
  &persistent_keepalive=<range>
  &jc=<n>&jmin=<n>&jmax=<n>&s1=<n>&s2=<n>&s3=0&s4=0
  &h1=<range>&h2=<range>&h3=<range>&h4=<range>
  &i1=<program>&i2=<program>&i3=<program>&i4=<program>&i5=<program>
  &header_protection_key=<key>&content_padding_addition=<range>
  &rekey_after_time=<range>&rekey_timeout=<range>&reject_after_time=<range>
  &keepalive_timeout=<range>&max_handshake_attempts=<range>
  &random_trailers=on&disable_cookies=on
  #<name>
```

- **userinfo** — the interface private key (base64). It contains `+` `/` `=`, so it is percent-encoded; the URI is always structurally valid.
- **host:port** — the peer endpoint. Both are mandatory.
- **`wire_profile`** — required exact value `awg-3.1-ranges-v1`. Unknown or absent markers are rejected.
- **`public_key`** — the peer public key (base64). **Mandatory.**
- **`preshared_key`** — optional peer preshared key (base64).
- **`address`** — optional comma-separated `[Interface] Address` CIDR list.
- **`allowed_ips`** — comma-separated CIDR list (e.g. `0.0.0.0/0,::/0`).
- **`dns`** — comma-separated DNS server list.
- **`mtu`** — interface MTU.
- **`persistent_keepalive`** — optional `[Peer] PersistentKeepalive` seconds, `lo` or an AWG 3.x `lo-hi` range.
- **`jc` `jmin` `jmax` `s1` `s2`** — AmneziaWG junk-packet parameters: `jc` is 0–128; sizes are 0–1280. `jmin <= jmax` applies even when `jc` is zero. Initiation and response sizes (`148+s1`, `92+s2`) must differ.
- **`s3` `s4`** — retained for configuration compatibility but must be absent or zero. RIPDPI rejects non-zero values because amneziawg-go#110 can silently drop all transport data on Android arm64 after a successful handshake.
- **`h1`..`h4`** — canonical unsigned decimal strings, either a singleton (`100`) or inclusive range (`100-199`). Ranges are disjoint; custom values exceed 4. Each standard singleton may retain its own message type (1, 2, 3, or 4). Each outbound packet samples its header afresh; receive accepts the full range.
- **`i1`..`i5`** — optional nonempty programs made from `<b 0x...>`, `<r N>`, `<rd N>`, `<rc N>`, and `<t>`. Hex literals are lowercase and even-length. Counts are 1–1280; each program has at most 64 tags, 4096 characters, and 1280 output bytes. Programs generate fresh random bytes, digits, letters, and a four-byte big-endian Unix timestamp before every initiation, including retries. Slots run before `Jc` packets.
- **AWG 3.x keys** — optional; each is omitted when unset. `header_protection_key` is a base64 32-byte key and a secret. The range keys (`content_padding_addition`, `rekey_after_time`, `rekey_timeout`, `reject_after_time`, `keepalive_timeout`, `max_handshake_attempts`) are `lo` or `lo-hi` with `lo <= hi <= 65535`. `random_trailers` and `disable_cookies` are `on` or `off`. Header protection needs `s1`..`s4` of at least 12, so the `s3`/`s4` rule above rejects it until the arm64 floor changes. The wire rules are in [AmneziaWG 3.x wire semantics](native/amneziawg-3x-wire-semantics.md).
- **fragment** — the profile display name (percent-encoded). When absent, the host is used as the name.

## Example

```
amneziawg://cHJpdmF0ZS1rZXk%3D@awg.example.com:51820?wire_profile=awg-3.1-ranges-v1&public_key=cHVibGljLWtleQ%3D%3D&allowed_ips=0.0.0.0%2F0%2C%3A%3A%2F0&mtu=1280&jc=4&jmin=40&jmax=70#Example%20peer
```

## Robustness

The codec (`AmneziaWgUriCodec`) never throws on `decode`:

- An unrecognised scheme, a structurally broken URI, or a missing mandatory field (private key, public key, host, port) yields `null`.
- Unknown options, malformed obfuscation values, overlapping header ranges, and empty signature programs yield `null`. An invalid optional `mtu` is omitted.
- Non-zero `s3` or `s4` yields `null`; `encode` rejects an unsafe in-memory profile instead of silently changing it.

`AmneziaWgProfile.toWgQuickConf()` renders a decoded profile as `.conf` text that `WireGuardConfParser` reads back to the same fields.

The bundle contract is version 2. Producers and consumers must roll out together. Old numeric-header bundles and persisted profiles require refresh or reimport. Incompatible saved rows remain in Room and the keystore but cannot activate or terminate profile observation.

For supported profiles, `encode` followed by `decode` round-trips an `AmneziaWgProfile` losslessly.

WARP settings also store header ranges and I1–I5 programs. Their protobuf contract reserves the old numeric header fields. Old active WARP settings require an explicit preset selection or import of the new settings contract; the stored numeric bytes are not reinterpreted as ranges.
