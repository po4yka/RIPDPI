## Context

The suite schedules distinct network clients. The ECH client uses a private JSON response through JNI.

## Goals / Non-Goals

- Preserve the stage, cause, scope, and cancellation of measurements.
- Preserve cleartext and TLS security policy.
- Keep authenticating Telegram MTProto, HTTP/3, and external qualification outside this repair.

## Decisions

- Admit TCP targets explicitly. Retain HTTP status and count only completed padding. Hold the TCP peer and HTTP authority constant for an SNI comparison.
- Keep the Telegram counts independent of UI progress. Keep monotonic timing and per-leg outcomes. Cancel each active call or socket.
- Use typed ECH outcomes. Preserve authenticated retry and endpoint controls. Keep ECH results independent of ordinary HTTPS.
- Use distinct suite skip reasons to retain missing prerequisites and inapplicable probes. Display scope and cause with the measurement.

## Risks / Trade-offs

- Android and external network evidence require separate checks.
- Update Kotlin and native callers together when the internal result shapes change.
