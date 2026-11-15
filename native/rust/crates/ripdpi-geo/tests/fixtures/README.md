# MMDB fixtures

`sing-geoip-record-{24,28,32}.mmdb` contain synthetic records. They were generated with `github.com/maxmind/mmdbwriter v1.0.0` and checked with `github.com/oschwald/maxminddb-golang v1.13.1`.

The writer options are `DatabaseType: "sing-geoip"`, `IPVersion: 6`, the named record size, `DisableIPv4Aliasing: true`, and `IncludeReservedNetworks: true`. No description is supplied. This covers the optional metadata that the dependency's stricter `verify()` does not accept.

| Network | String value | Checked address |
|---|---|---|
| 89.160.20.0/24 | se | 89.160.20.112 |
| 149.154.167.0/24 | telegram | 149.154.167.50 |
| 2001:db8::/32 | se | 2001:db8::1 |

Each record is inserted with `writer.Insert(network, mmdbtype.String(value))`, then written with `writer.WriteTo(file)`. These are local format fixtures, not an upstream release database or a claim about current address ownership.

The layout follows the [sing-geoip producer](https://github.com/SagerNet/sing-geoip/blob/ecd02c178af5efbac38d427a8d178f940327de1f/main.go#L94-L150) and its pinned writer version.

## Map key validation

`country-string-key.mmdb` and `country-integer-key.mmdb` are hand-encoded MMDB 2.0 IPv4 fixtures. Each has one search node, a country record for every address, and an empty optional description. Both records contain `country.iso_code: "SE"` and an unknown `x` map with one entry whose value is `"payload"`.

The control uses UTF-8 string key `"k"`. The invalid fixture uses uint16 key `1`. Only bytes 47 and 48 differ: `41 6b` in the control and `a1 01` in the invalid file. The [MMDB map contract](https://maxmind.github.io/MaxMind-DB/#maps) requires UTF-8 string keys. `github.com/oschwald/maxminddb-golang v1.13.1` decodes the control and rejects the invalid fixture with `unexpected type when decoding string: 5`. These fixtures check admission and last-good reload behavior for malformed data inside an unknown field.
