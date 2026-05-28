# ripdpi-geo

**Layer:** L1 -- protocol / core.

`ripdpi-geo` owns GeoIP/geosite lookup helpers used by routing and runtime metadata paths.

## Boundaries

- Keep database parsing and lookup concerns here.
- Routing decisions belong in `ripdpi-routing` or runtime policy; Android asset lifecycle belongs in Kotlin/service code.

## Checks

Run focused checks with `cargo test -p ripdpi-geo`.
