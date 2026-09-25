## Context

`SessionRow` and `EventRow` always pass `onClick` to `RipDpiCard`. Four call sites pass empty lambdas, while other call sites open detail sheets.

## Goals / Non-Goals

- Goal: align click semantics and visual interaction with actual actions.
- Non-goal: add new diagnostic detail routes or change underlying evidence.

## Decisions

- Make row callbacks nullable and pass `null` at inert call sites. `RipDpiCard` already accepts nullable callbacks and renders informational cards without click semantics. Retain working callback call sites.

## Contracts and ownership

- `:app` diagnostic Compose files and tests only; no Rust crates, public wire contracts, or persisted schema.
- No locale or generated files. The task board is serialized at integration.

## Risks / Trade-offs

- An informational row no longer receives tap feedback, as intended. Existing detail callbacks retain their action.

## Migration Plan

No migration. Revert the app change if needed. Verify focused semantics tests and app lint before integration.
