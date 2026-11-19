# Change: Add bounded TLS interference hop comparisons

Task ID: `DGN-1795014015067016`

## Why

`platform_ttl::get_local_socket_ttl` only reads the local outbound TTL of a probe socket. It does not locate interference. A TTL-limited ClientHello experiment with a control and a test SNI can bound the hop distance of a middlebox that reacts to the test SNI. The signal is the injected response itself, so the experiment works on non-root Android, where ICMP Time Exceeded for TCP is not observable.

## What Changes

- Add a manual, opt-in TTL ladder probe that sends a control and a test ClientHello to one user-authorized endpoint at increasing TTL values.
- Classify each attempt by what returns (injected RST or FIN, TLS alert, ServerHello, or silence) and report a divergence hop interval with its limits.
- Add a TTL setter to the diagnostics transport; the existing TTL getter keeps its current meaning.

## Capabilities

### New Capabilities

- `tls-interference-hop-comparison`: TTL-bounded control/test ClientHello comparison.

### Modified Capabilities

- None.

## Impact

`ripdpi-diagnostics-transport` (TTL setter), `ripdpi-diagnostics-tls` (ladder probe), the diagnostics wire contract and `EngineContract.kt`, `core/diagnostics` projection and redacted export, diagnostics UI and its strings in all locales. No new backend.

## Sources

https://ooni.org/post/2026-ooni-probe-tls-middlebox-experiment/

OONI pairs control and test names with TTL-limited requests. This change keeps the results on the device instead of publishing them.
