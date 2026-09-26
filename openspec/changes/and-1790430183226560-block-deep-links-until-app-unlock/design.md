## Context

`NavController` handles the Activity intent when its graph is first set. The graph has public deep links, while a separate shell handles import and support links. Only the home shell request currently checks the onboarding and biometric routes.

## Goals / Non-Goals

- Goal: Route all inbound links through one pending request path and keep gate screens visible until completion.
- Non-goal: Change authentication, service actions, URI formats, or destination content.

## Decisions

- Copy inbound route requests into the existing Activity shell, then replace the Activity intent before Compose creates the navigation graph. This prevents Navigation's direct deep-link handling, including internal deep-link extras supplied by an exported caller.
- Parse the five existing `ripdpi://` navigation hosts into stable routes. Keep unsupported hosts inert, including public `disconnect`.
- Apply one gate predicate to every shell navigation request. Leave pending requests unconsumed until navigation reaches an authorized route.

## Contracts and ownership

- Affected module: `:app`. No Rust crates, wire or persistence contracts, migration, or serialized shared files change.
- One writer owns `MainActivity`, `MainActivityShellController`, `RipDpiNavHost`, and their direct tests.

## Risks / Trade-offs

- A pending request can be lost on Activity recreation because shell state is Activity-local. Existing shell requests have the same limit; this change does not add persistence.
- Unit tests prove route parsing and gate decisions. An Android device test is needed to prove the full Activity and Navigation interaction on hardware.

## Migration Plan

No migration. Rollback reverts this `:app` change. Run targeted `:app:testGithubFullDebugUnitTest` tests, then the affected module gate.
