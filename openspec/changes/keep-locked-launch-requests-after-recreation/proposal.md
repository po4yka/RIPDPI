# Change: Keep locked launch requests after Activity recreation

Task ID: `AND-1790433472437083`

## Why

The Activity removes inbound links from its Intent to prevent Navigation Compose from bypassing the app lock. Deferred requests then exist only in its local shell. Android recreation loses those requests and can also lose a pending relock signal.

## What Changes

- Keep deferred deep link, shared diagnostics, and import navigation through Activity recreation.
- Keep a pending relock signal through Activity recreation.

## Capabilities

### New Capabilities

- `locked-launch-restoration`: resume deferred launch and relock requests after Activity recreation.

### Modified Capabilities

- None.

## Impact

- Android `:app` Activity shell and unit tests. No native, wire, storage schema, or external service changes.
