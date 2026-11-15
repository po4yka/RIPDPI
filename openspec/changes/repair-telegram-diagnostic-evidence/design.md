## Context

The Telegram stage runs download, upload, TCP connect, and WSS opening checks.
The caller can cancel while any of these operations runs.

## Goals / Non-Goals

Produce results that state what the selected route measured. Keep full MTProto
key exchange, account login, and the user Telegram relay outside this change.

## Decisions

- Use HTTPS URI validation and the shared protected transport. Reuse its abort
  control for HTTP writes and reads, DC connects, and WSS. Check matched content
  and use an endpoint-specific criterion for page and transfer measurements.
- Probe explicit DC socket addresses from the catalog. Keep address, port,
  result, and coverage. Keep the WSS opening result linked to its own peer.
- Use absence for an unavailable score and distinct states for incomplete data.
  Keep the old report details readable in saved reports.
- Publish available leg results through the existing native checkpoint while
  retaining one scheduled step and one terminal probe result.

## Contracts and ownership

The HTTP lane owns validated targets and measured transfer results. The path
lane owns Telegram TCP/WSS and the shipped DC catalog. The report lane owns
the model, score, outcome, and UI. The coordinator owns the runner checkpoint
and integration validation. Only the report lane changes the report schema and
locales. Only the path lane changes the shipped catalog.

## Risks / Trade-offs

An endpoint with an unverified service contract can fail or remain unmeasured.
A locally verified HTTP response does not establish public service availability.

## Migration Plan

Read previously stored raw details when the report lacks new fields. Preserve
process-local scan state and the single final Telegram result.
