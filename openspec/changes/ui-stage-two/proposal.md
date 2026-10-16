# Change: Simplify connection, diagnostic results and the log stream

Task ID: `UIX-1792130566603442`

## Why

The connection screen presents settings navigation and traffic mode as two
competing choices. Its profile guidance delays the main action. Diagnostics
stacks several conclusions from different evidence sources. Logs places a
short inner list below a large scrollable header and offers a false refresh.
These structures make the next action hard to predict on small screens.

## What Changes

- Explain saved traffic scope and path in one connection summary. Keep the
  main action consistent with that saved mode and show a compact profile.
- Show one diagnostic outcome with its own provenance and a clear next action.
  Keep technical details and historical evidence available below it.
- Give live log entries the remaining screen height. Move optional filters
  behind a compact control and remove refresh without a data operation.
- Preserve failure recovery, accessibility, narrow layouts and large text.

## Capabilities

### New Capabilities

- `ui/stage-two-workflows`: clear connection, diagnostic and log screen flows.

### Modified Capabilities

- None. There are no breaking protocol, configuration or storage changes.

## Impact

- App module UI, app presentation models, tests and all ten locale resource sets.
- No required dependencies, backend, JNI, protobuf, native or service changes.
- Existing Mode, relay selection, consent and recommendation-review contracts stay
  authoritative. Local validation, hosted CI and device proof remain separate.
