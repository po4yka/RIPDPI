# TLS Certificates

Caddy is configured with `tls internal`, which creates a local CA inside this
directory at runtime. RIPDPI debug probes trust the lab endpoint only from
debug-only source code; production builds do not include this trust behavior.

For browser/manual tests, import Caddy's generated root certificate from the
container data if you need a trusted desktop session. Automated debug probes do
not require a system trust-store change.
