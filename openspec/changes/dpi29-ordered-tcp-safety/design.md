## Context

Task RST-1794677660326601 fixes three DPI-29 regressions without user schema changes.

## Goals / Non-Goals

- Goal: reject an invalid whole batch before emission, preserve exact dispatch and document existing layouts.
- Non-goal: new segmentation, ID allocation or control-flag placement semantics.

## Decisions

- Check complete ordered packets against negotiated MSS and actual route MTU before emission. Reject oversized shapes explicitly. Query MTU on the same protected, mark-inheriting raw socket used for sends. This preserves reserved IPv4 IDs, SYN/FIN behavior, packet boundaries and bytes. Count additional serialized fake options against the effective TCP_INFO MSS without subtracting normal timestamp options twice.
- Add configured ordering to the FakeSplit/FakeDisorder special-executor gate. Preserve default and terminal fallbacks.
- Document and test legacy hostfake geometry without runtime changes.

MSS references: [RFC 6691 section 2](https://www.rfc-editor.org/rfc/rfc6691.html#section-2), Linux v6.12 [TCP_INFO](https://github.com/torvalds/linux/blob/v6.12/net/ipv4/tcp.c#L3898) and [normal option accounting](https://github.com/torvalds/linux/blob/v6.12/net/ipv4/tcp_output.c#L1775). These are API references, not evidence for a particular Android kernel.

## Contracts and ownership

- ordered_mtu_fix owns privileged-ops raw_packet code and tests.
- dispatch_fix owns runtime decision and runtime/adapter tests.
- ordering_docs_fix owns proxy-engine.md and hostfake emission_order tests.
- Primary integrator owns task/spec metadata, combined checks and Git integration.
- No Kotlin, JNI, schemas, dependencies, Cargo.lock, locale or baseline change.

## Risks / Trade-offs

Large exact batches remain unsupported. Explicit pre-send rejection replaces possible partial emission; this is not large-payload segmentation support. Route changes can still cause terminal send errors. Privileged Android acceptance remains unverified.

## Migration Plan

No data migration or deployment. Commit each finding separately after regressions and review, run combined ci-gates, then integrate and push as authorized. Rollback is a normal revert.
