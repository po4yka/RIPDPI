# Native acceptance peers

`run.py` executes exact native tests and writes `result.json`. Exit zero requires
the named test to run once per session, with no ignored result, and requires all scenario checks.
The parent acceptance runner validates the source SHA, run ID, and artifact hashes.

AWG requires all eight ordered sessions: MTU 1280 and 1420, with sessions 0 through
3 for each MTU. Each session must contain exactly one successful named Rust test.
Missing, duplicate, failed, ignored or extra sessions cannot pass. The peer banner
must match the catalog's version and revision and contain one binary SHA-256.
All other adapters still require exactly one test execution for the whole scenario.

```sh
python3 test-lab/acceptance/peers/run.py --list
python3 test-lab/acceptance/peers/run.py --scenario independent-hysteria2-tcp-udp --prepare
python3 test-lab/acceptance/peers/run.py --scenario independent-hysteria2-tcp-udp \
  --run-id local-001 --out-dir /tmp/ripdpi-local-001
python3 -m unittest discover -s test-lab/acceptance/peers -p 'test_*.py'
```

Preparation can access the network. It downloads the pinned upstream source or
binary and build dependencies. It does not create an acceptance result. Runtime
disables Cargo and Go dependency downloads. This restriction does not prove
network egress isolation; use the VM lane for that evidence. On macOS, use the
machine build gate when installed. Existing outbound runners require the gate;
the adapter keeps that requirement. Cargo uses the repository `cargo-guarded.sh`.

The default peer cache is `~/.cache/ripdpi-acceptance/peers`. `--cache-dir` selects
another cache. `--source-dir` can select an existing clean checkout for the
SSH/Mieru/AnyTLS peer. Its revision must match the catalog. Runtime never clones
a missing checkout or downloads a missing binary. Each result directory must be
empty. Result directories are private (mode 0700); logs can contain ephemeral
test values and must not be published directly.

For Go peers, preparation records the exact compiler version, resolved toolchain
directory, and binary SHA-256. Runtime verifies that identity and puts that
toolchain first in `PATH`. It keeps `GOPROXY=off` and `GOSUMDB=off`. This avoids
Go's bootstrap toolchain switcher, which requires checksum database verification
even for a previously downloaded toolchain. A missing or changed prepared
compiler fails closed; run preparation again to rebuild its identity record.

## Evidence levels

- `independent-peer`: the production native client exchanges data with upstream
  Hysteria, AmneziaWG, Mieru, AnyTLS, or Go SSH code. Peer source and binary identity
  are recorded. SSH uses `golang.org/x/crypto/ssh` from the pinned Mieru dependency
  manifest; the Mieru protocol does not implement the SSH peer.
- `native-contract`: repository fixtures or protocol tests execute. These tests
  do not establish interoperability with another implementation.
- Neither level establishes Android TUN behavior, routed fault placement, or
  behavior of a public provider. Xray Android evidence belongs to the Android
  adapter. Provider boundaries appear in the catalog.

## Hysteria

The peer is the unchanged Hysteria `app/v2.13.0` release binary. The catalog contains
GitHub release asset SHA-256 values for Linux ARM64, Linux AMD64, and macOS ARM64.
The adapter checks the digest on preparation and on every run. OpenSSL creates
a one-day local certificate in a temporary private directory. The peer binds
only loopback and reaches loopback TCP and UDP destinations. The destinations
record the SHA-256 and length of each run-specific payload before returning it.

Three exact client tests check TCP/UDP payloads, wrong authentication, and default
TLS rejection. Negative cases first connect with correct authentication so a
missing server cannot pass as an expected rejection. The payload and auth cases
explicitly disable certificate verification for the temporary peer. The client
has no test-root injection API, so these cases do not claim trusted-CA, hostname,
or expiration acceptance. No production TLS behavior is changed.

SIGTERM raises an exception and unwinds adapter cleanup. Owned process groups,
socket workers, peer sockets, and private temporary files are closed before the
report is written. A timeout, missing prerequisite, missing test, ignored test,
identity mismatch, receipt mismatch, or cleanup failure cannot produce a pass.

## NaiveProxy

The peer is klzgrad/forwardproxy `v2.11.7-naive2` (commit `e747854e7874`) on
Caddy `v2.11.7`, built the way the fork's release workflow builds it: the
pinned Caddy tag with the fork's two Caddy patches, then the forwardproxy
module. Preparation checks the source revisions and patch digests, builds with
the pinned Go toolchain, and records the binary SHA-256; every run checks that
record and digest before it starts the server. A generic `xcaddy` build is not
an equivalent artifact.

OpenSSL creates a one-day private test CA and a `naive.test` leaf in a private
temporary directory. Only the test client config trusts that CA; production
trust is unchanged. The server binds loopback with basic auth and an ACL that
allows only loopback destinations. Four exact helper tests cover payload
exchange by domain and by IP authority, wrong-password rejection (407), default
public-root TLS rejection, and a refused target. The server answers CONNECT 200
before it dials, so a refused target is an accepted tunnel that ends without
destination bytes. Each negative case first exchanges one payload, so a missing
server cannot pass as an expected rejection. Destinations record the SHA-256 of
every payload they echo.

`independent-naiveproxy-plain-tcp` runs the same payload test against the stock
caddyserver/forwardproxy (commit `0aab84dad4fc`) on unpatched Caddy `v2.11.7`.
That server never offers padding, so the helper must use plain HTTP/2 CONNECT;
the destination receipts prove the origin received unframed bytes. (Caddy
`v2.8.4`, the module's go.mod minimum, panics loading TLS under the pinned Go.)
