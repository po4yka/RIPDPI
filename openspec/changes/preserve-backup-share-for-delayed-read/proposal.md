# Change: Preserve redacted backup shares for delayed reads

Task ID: `AND-1790432927304098`

## Why

The share chooser returns before a recipient necessarily opens its FileProvider
URI. The app deletes the backing file on chooser return or settings route
closure, so a delayed recipient read fails.

## What Changes

- A successfully launched share keeps its redacted backup in private cache for
  delayed recipient reads.
- Failed and unlaunched shares still delete their temporary files immediately.
- Old shared files are removed on the next backup screen visit or share attempt.
- Each share uses a unique cache filename so a new share cannot overwrite an
  earlier URI target.

## Capabilities

### New Capabilities

- `backup-share-file-lifetime`: A redacted backup URI remains readable after
  chooser return while cache retention has not expired.

### Modified Capabilities

- None.

## Impact

- `:app` backup share controller, private cache ownership, and direct tests.
  No permission, FileProvider scope, dependency, wire, or schema changes.
