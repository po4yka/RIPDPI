## Context

DIA-02 covers diagnostic TLS 1.2, TLS 1.3, ECH and the offline JA3 parser. Keep the existing failure-stage and successful-retry safeguards in preserve-tls-probe-evidence. Keep the bounded handshake contract from the STD-01 repair and the retry safety contract from the STD-03 repair.

## Goals / Non-Goals

- Preserve distinct measurements for each connection and give policy readers the evidence needed to interpret partial results.
- JA4 computation, browser-template parity, relay TLS and device or external interoperability are out of scope.

## Decisions

- Capture only the bytes accepted by the local stream. Reassemble the first ClientHello within bounded TLS record and message lengths. Store the hash and capture state. Keep raw bytes transient. This capture point does not measure packets after a proxy rewrite or the ECH inner hello.
- Keep reported addresses, timings, hashes, negotiated ALPN, certificate policy and outcomes on the owning attempts. Continue report projection through the web and HTTPS strategy consumers. Derive any summary of a successful attempt from one source.
- Separate actual ServerHello, any received TLS response, certificate-policy failure, incoming alert, and administrative interruption. Require measured timing before assigning a timing bucket. Keep ECH-only and unauthenticated outcomes descriptive.
- Use the connected endpoint for ECH fallback. For hostname SOCKS with an unknown upstream address, omit peer-specific fallback and retry. Retry once, under the remaining time budget, only after the verifier authenticates the outer public name. Custom or skipped trust is explicit. Never send application data on rejected ECH.
- Carry cancellation through the diagnostic phases. Preserve completed measurements, including earlier failed attempts and observations before an interrupted phase.

## Contracts and ownership

- The diagnostics TLS crate owns the typed measured result and JA3 parser. The diagnostics runner and monitor engine project it. Kotlin diagnostics and app reports consume the resulting fields.
- The integrator owns changes to serialized report fields and API snapshots. Keep the existing wire representation unless the owning contract requires a coordinated change. Keep saved reports with absent fields legible as unknown. Metadata templates and measured values retain distinct meanings.
- Validate wrong-SAN, expired, untrusted, blocked, and override trust behavior on host fixtures where applicable. Use a known-answer JA3 oracle, loopback peers with different results, and cancellation boundary tests.

## Risks / Trade-offs

- A handshake may end before a complete ClientHello. The fingerprint remains absent for that attempt. A peer may decline ALPN. The negotiated value remains absent.
- An ECH-only result and a peer alert can be genuine observations with an unknown cause. Direct Mode must not infer path interference from them alone.

## Migration Plan

Update in-repository producers and consumers together. No device data migration is planned. A reader of a stored report treats absent measurements as unknown. Run locked tests for affected crates and targeted Android JVM tests for any affected policy and report consumers. Before integration run the strict OpenSpec and task gates, cargo metadata with --locked, architecture health, and just ci-gates. Roll back the coupled producer and consumer changes together.
