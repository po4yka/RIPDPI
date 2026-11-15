## Context

Kotlin runs these local probes. The bypass checker and proc inventory
supply independent sources to an offline verdict.

## Goals / Non-Goals

Fix the twelve confirmed findings. Keep SOCKS5 greeting, HTTP/1 CONNECT,
plaintext loopback HandlerService, and optional proc inventory as the scope.

## Decisions

- Share the validated port range between preview and runner. Apply exclusions
  before starting workers. Gate active checks with the main network switch.
- Use a cancellable unary RPC and direct loopback transport. Limit retries
  to temporary errors and record completed probes and outcomes.
- Keep local observation distinct from route evidence. Validate outbound
  endpoint fields. Use typed data to apply the same privacy rule to outputs.

## Risks / Trade-offs

Host fixtures cover protocol framing and observations. Android package
visibility, real-path behavior, and release compatibility need separate checks.
