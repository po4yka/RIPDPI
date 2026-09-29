# zapret2 verification examples

The packet vectors use fixed variants of `test_dissect` and `test_csum` from
zapret2 `ca950d838a0ee7dc32bb6ae5e65e54488c1bfe3b`. The upstream C helpers
produce the expected bytes. Rust tests compare complete output packets,
including IPv4 and TCP options, IPv6 Hop-by-Hop headers, odd payload lengths,
checksums and TCP sequence wrap.

The examples cover Lua split and payload replacement plus fake TTL output.
IPv4 UDP output keeps the existing zero-checksum policy; its explicit vector
uses upstream `keepsum=true`. IPv6 UDP retains its computed checksum.
Unsupported IPv6 AH packets pass through without injection. Existing golden
fixtures and production assets do not change.

## Regenerate the vectors

Use the pinned nfqws2 executable and its matching `zapret-lib.lua`:

```sh
nfqws2 --intercept=0 --lua-init=@path/to/zapret-lib.lua \
  --lua-init=@native/zapret2/tests/packet_vectors.lua > /tmp/packet-vectors.log
python3 - <<'PY'
from pathlib import Path
lines = Path('/tmp/packet-vectors.log').read_text().splitlines()
constants = [line for line in lines if line.startswith('pub(super) const')]
assert len(constants) == 20
headers = [line for line in lines if line.startswith('//')]
Path('native/rust/crates/ripdpi-tunnel-intercept/src/egress/packet_vectors.rs').write_text(
    '\n'.join(headers + constants) + '\n')
PY
```

The upstream CLI bootstrap can require capabilities even with interception
disabled. The oracle calls only reconstruction and checksum helpers. It does
not call rawsend or change firewall rules. The maintained Rust tests need no
root, upstream executable, external service or live network target.

## Complete diagnostic audit

`full_matrix_v1` without `max_candidates` runs every applicable candidate.
Confirmed QUIC and failed pilot targets do not remove the TCP matrix. Existing
capability checks, failure-based ordering, scan and stage deadlines, and
cancellation remain active. Quick scans and explicitly capped scans keep their
current selection policy. No new request or wire fields are needed.

An unfinished uncapped full audit reports `PartialResults`, including when
it uses DNS fallback. A complete run retains its normal recommendation.
The existing Android incomplete-state rendering uses this report field.

## Checks

From `native/rust`:

```sh
cargo test --locked -p ripdpi-tunnel-intercept -p ripdpi-monitor-engine
cargo clippy --locked --no-deps --all-targets \
  -p ripdpi-tunnel-intercept -p ripdpi-monitor-engine -- -D warnings
cargo fmt --all --check
```

Run the same complete native library test binaries on Android with the owning
NDK environment and `android-jni-dev` profile. For the monitor binary, copy the
unchanged `native/rust/crates/ripdpi-monitor-engine/tests/golden` files under
the same relative path in a temporary fixture root. Set `RIPDPI_REPO_ROOT` to
that root when running the binary. Check each copied file hash against its
repository source. This checks native computation
and bounded local diagnostic execution. It does not prove DPI evasion, raw
packet delivery, APK root activation or privileged VPN socket transfer.
