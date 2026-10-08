# Native acceptance peers

`run.py` executes one exact test and writes `result.json`. Exit zero requires the
named test to run once, with no ignored result, and requires all scenario checks.
The parent acceptance runner validates the source SHA, run ID, and artifact hashes.

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

The peer is the unchanged Hysteria `app/v2.9.0` release binary. The catalog contains
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
