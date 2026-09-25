# Change: Keep navigation reachable and announce DNS selection

Task ID: `UIX-1790339952138380`

## Why

On short landscape screens with large text, navigation rail destinations can fall outside the visible area. DNS cards mark selection visually but do not announce it to assistive technology.

## What Changes

- Allow scrolling to every rail destination.
- Expose selected state on DNS option cards.
- No breaking contract or schema change.

## Capabilities

### New Capabilities

- `navigation-and-dns-accessibility`: rail reachability and DNS selection semantics.

### Modified Capabilities

- None.

## Impact

- `:app` Compose navigation and DNS UI plus tests. No service, storage, or native changes.
