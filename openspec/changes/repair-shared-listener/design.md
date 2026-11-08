## Context

Mixed and dedicated listeners already select the right parser; the bugs occur in downstream admission, errors and operator information.

## Goals / Non-Goals

Keep the current mode precedence and token policy. Preserve early payload and do not advertise an unowned port. Redirect configuration and encrypted relay protocols are out of scope.

## Decisions

Use the existing cancellable resolver and the absolute handshake deadline. Separate structural address framing from one-time resolution. Use consistent HTTP field boundaries before auth or routing; map header size and policy denials to client errors. Keep persisted ports intact and correct the advertised behavior.

## Contracts and ownership

The runtime owns I/O, cancellation and admission. The session parser owns HTTP syntax. Android owns operator-visible settings, and CLI owns local help. The portfolio issue records worktree ownership.

## Risks / Trade-offs

Strict parsing rejects malformed clients. Retain valid fragmented input and test before/after with independent clients. Correct copy instead of changing a user's port.

## Migration Plan

No stored-data change. Validate each fix and the combined tree before integration. Each fix can be reverted separately with its regression assessment.
