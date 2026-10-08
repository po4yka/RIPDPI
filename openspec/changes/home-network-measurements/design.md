## Context

Current observations substitute address lookup for reachability and resolver identity.

## Goals / Non-Goals

- Goal: report actual bounded connection and platform DNS configuration.
- Non-goal: infer provider ownership, ASN, or introduce network services.

## Decisions

- Create the IPv6 socket from the captured Android network socket factory, with a numeric control address and Socket.connect timeout.
- Reject the result as unknown when the captured network is absent or no longer active before or after the probe.
- Read LinkProperties dnsServers from the same active network snapshot.
- Test helpers with fake sockets and metadata, without external requests.

## Contracts and ownership

- Home evidence agent owns app augmentation and related helper/tests. No serialized shared-file changes.

## Risks / Trade-offs

- A single control endpoint reports only tested path reachability.

## Migration Plan

No migration. Revert to roll back. Targeted JVM tests and staticAnalysis are required.
