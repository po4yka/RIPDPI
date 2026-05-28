# ripdpi-geo

**Layer:** L1 -- protocol / core.

`ripdpi-geo` owns GeoIP/geosite lookup helpers used by `ripdpi-proxy-runtime` for runtime geosite matching and native geo database metadata reporting.

## Boundaries

- Keep database parsing and lookup concerns here.
- Routing decisions belong in runtime policy or the separate `ripdpi-routing` rule-engine crate; Android asset lifecycle belongs in Kotlin/service code.

## Checks

Run focused checks with `cargo test -p ripdpi-geo`.
