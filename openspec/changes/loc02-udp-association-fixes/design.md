## Context

See proposal.md and task PRX-1794076309764211. The audit uses independent loopback wire bytes on source SHA 039f5fc0d3a572dc618183d47162fb519940282d. Implementation starts from main 0e9c9128ce12298dcfdbc28b08787713e14b0591, which adds TCP SOCKS handshake shutdown helpers that must be reused where they fit.

## Goals / Non-Goals

- Goal: fix the six reproduced defects with bounded ownership and regression coverage.
- Non-goal: UDP fragmentation, authenticated mobility, new relay kinds, device/root activation, or changes based only on audit hypotheses.

## Decisions

- Keep the synchronous proxy worker model. Reuse active-socket registration and checked handshake I/O. Add a narrow cancellable nonblocking connector on the existing protected socket path so a pending connect cannot outlive stop. Pass the same caller cancellation through UDP name resolution into the existing blocking DNS runtime; cancellation drops the owned exchange future. Do not introduce detached DNS threads or a second async runtime.
- Validate the actual upstream peer against the selected target before applying the existing logical preferred-edge mapping. Drop mismatched peers. Decode replies with standard address types independent of inbound listener flags; match only known or numeric domain authority without DNS, and reject private ATYP5.
- Apply the common profile UDP flag at admission and capability projection. Keep cipher/transport/flow gates. The UDP payload gate does not disable a shared QUIC carrier used for TCP.
- Treat carrier size errors as per-datagram loss, and account for SS2022 overhead where the owning API exposes it.
- Preserve the UDP endpoint when an upstream SOCKS control owns a pinned binding. Direct-path rebind remains available.

## Contracts and ownership

The portfolio task records three isolated writer lanes and the serialized shared files. No Kotlin, JNI, protobuf, stored schema, locale or baseline change is planned. The integrator serializes one existing libc workspace dependency edge for portable EMSGSIZE classification; no package or version changes. The integrator resolves shared tests and runs combined gates.

## Risks / Trade-offs

- Control probing can race closure; include FIN/RST, stalled setup and subsequent datagram tests.
- Source validation must preserve preferred-edge mapping and IPv6/domain reply handling.
- OS UDP ceilings differ; use local host ceiling tests and report Android/external coverage separately.

## Migration Plan

No data migration. Each fix is a separate reversible commit. Run owning regression tests and Clippy, independent review, ci-gates, architecture health and locked metadata before main integration. Push only the checked main history requested by the user.
