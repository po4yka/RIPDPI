## Context

See proposal.md. Kotlin has independent DNS codecs in diagnostics-data, diagnostics, service and app debug probes.

## Goals / Non-Goals

- Goal: use dnsjava wire types and one shared validation boundary.
- Non-goal: change Rust DNS, SOCKS framing, stored settings, or active scan routing.

## Decisions

- Put DnsMessages in diagnostics-data, an existing dependency of all consumers. Expose dnsjava types with an API dependency rather than mirror every record.
- Pin dnsjava 3.7.1. Its BSD-3-Clause license permits distribution; keep the library notice and existing dependency reporting. SLF4J is its logging API. Do not enable resolver defaults or payload logging.
- Keep Android-owned sockets. The library encodes and decodes messages; it does not select network paths.
- Validate the complete expected question and message envelope centrally. Domain outcome projection remains in diagnostics.
- Treat TC as incomplete evidence; only the normal direct resolver retries with TCP. UDP health probes retain UDP-only evidence.
- Use library OPT records and padding options for sized queries.
- Supply the SLF4J no-op binding at runtime and Lombok annotations only on the app compile classpath. Exclude both desktop resolver SPI resources from Android packaging. Suppress only the unavailable Java 8 SPI interface and require R8 to discard its provider.
- Replace AGP's default service-descriptor merge wildcard with the six Android service paths used by gRPC and coroutines. Merge rules take priority over exclusions. Other singleton services remain included; a new duplicate service requires an explicit merge rule.

## Contracts and ownership

- Root: dependency catalog, diagnostics-data, planning, gates and integration.
- Diagnostics worker: diagnostics DNS and HTTPS consumers/tests in a separate worktree.
- Probe worker: service DNS builders/matching and app debug DNS consumers/tests in a separate worktree.
- No Rust, JNI, protobuf or persistence schema change. Root serializes catalog and generated outputs.

## Risks / Trade-offs

- Library parsing can be more permissive on TC: retain strict question matching and never use partial answers.
- A stricter parser can reject invalid old fixtures: correct fixtures against independently encoded DNS packets; do not weaken checks.
- Network fallback must retain binding and deadlines; test TCP with a local real UDP/TCP fixture.
- Preserve library-free domain semantics and avoid creating a second wire parser.
- dnsjava 3.7.1 clamps high-bit TTL values, including OPT control bits. Registered record subclasses retain the original A/AAAA/SOA TTL and OPT control word before delegating RDATA parsing. Strict HTTPS records reject compressed targets and unordered or duplicate parameters, which the library accepts.

## Migration Plan

Replace all Kotlin message codecs together and remove old implementations. Add regression cases before replacement. Run affected unit suites through build-gate, then staticAnalysis and just ci-gates. Review independently, commit, rebase, integrate main and push. Revert the commit for rollback; no data migration is needed. Device routing and live external networks are not changed or claimed by host tests.
