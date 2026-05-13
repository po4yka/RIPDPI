# TLS Certificates

Caddy is configured to use generated `lab.crt` / `lab.key` files. The files are
created by `test-lab/scripts/start-lab.sh` when missing and are ignored by git.
RIPDPI debug probes trust the lab endpoint only from debug-only source code;
production builds do not include this trust behavior.

For browser/manual tests, temporarily trust `lab.crt` if you need a clean
desktop session. Automated debug probes do not require a system trust-store
change.
