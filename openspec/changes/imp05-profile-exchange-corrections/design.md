## Context

Clipboard, Android intents and the QR scanner deliver profiles to a confirmation screen. The share screen reconstructs saved profiles.

## Goals / Non-Goals

- Keep durable profiles and selection consistent.
- Keep the effective connection parameters in supported representations.
- Reuse the existing file and profile admission rules.
- New relay protocols and file export formats are outside this change.

## Decisions

1. Save a unique profile and its credentials with the existing recovered mutation coordinator. Use Connection for explicit selection.
2. Parse and admit before offering an action. Share handling uses an opaque token. Error results contain fixed categories.
3. Read metadata and credentials in one recovered scope. Refuse links that omit active transport or trust options.
4. Use strict text decoding and the standard QR quiet zone. Bound stored QR series data by the chunk index.
