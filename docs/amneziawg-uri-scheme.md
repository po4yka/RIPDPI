# `amneziawg://` share-URI scheme

RIPDPI defines a local `amneziawg://` URI scheme for ergonomic single-profile sharing of AmneziaWG profiles (link, QR code, clipboard import).

## Rationale

There is **no standardized AmneziaWG share-URI scheme upstream**. Neither `amneziawg-android` nor `amneziawg-go` defines one — both share profiles as `.conf` files or as a QR code of a `.conf`. A `.conf` file is awkward to paste into a chat or embed in a link, so RIPDPI invents `amneziawg://` for the same ergonomics the proxy schemes (`vless://`, `hysteria2://`, …) already provide.

The layout follows the Hysteria2 URI shape: the secret in userinfo, the endpoint as `host:port`, auxiliary fields as query params, the display name as the fragment. All AWG-specific query-param names are defined by this document.

## Layout

```
amneziawg://<private-key>@<host>:<port>
  ?public_key=<key>
  &preshared_key=<key>
  &allowed_ips=<cidr,cidr>
  &dns=<ip,ip>
  &mtu=<n>
  &jc=<n>&jmin=<n>&jmax=<n>&s1=<n>&s2=<n>
  &h1=<n>&h2=<n>&h3=<n>&h4=<n>
  &i1=<hex>&i2=<hex>&i3=<hex>&i4=<hex>&i5=<hex>
  #<name>
```

- **userinfo** — the interface private key (base64). It contains `+` `/` `=`, so it is percent-encoded; the URI is always structurally valid.
- **host:port** — the peer endpoint. Both are mandatory.
- **`public_key`** — the peer public key (base64). **Mandatory.**
- **`preshared_key`** — optional peer preshared key (base64).
- **`allowed_ips`** — comma-separated CIDR list (e.g. `0.0.0.0/0,::/0`).
- **`dns`** — comma-separated DNS server list.
- **`mtu`** — interface MTU.
- **`jc` `jmin` `jmax` `s1` `s2`** — AmneziaWG junk-packet obfuscation parameters (non-negative integers).
- **`h1`..`h4`** — AmneziaWG magic-header values (4-byte unsigned).
- **`i1`..`i5`** — AmneziaWG special-junk payloads (lowercase hex strings).
- **fragment** — the profile display name (percent-encoded). When absent, the host is used as the name.

## Example

```
amneziawg://cHJpdmF0ZS1rZXk%3D@awg.example.com:51820?public_key=cHVibGljLWtleQ%3D%3D&allowed_ips=0.0.0.0%2F0%2C%3A%3A%2F0&mtu=1280&jc=4&jmin=40&jmax=70#Tokyo%20edge
```

## Robustness

The codec (`AmneziaWgUriCodec`) never throws on `decode`:

- An unrecognised scheme, a structurally broken URI, or a missing mandatory field (private key, public key, host, port) yields `null`.
- A malformed *optional* numeric param (e.g. `mtu=not-a-number`) is silently dropped — the rest of the profile still decodes.

`encode` followed by `decode` round-trips an `AmneziaWgProfile` losslessly.
