# Change: Correct AmneziaWG URI import failures

Task ID: `DAT-1794720880096281`

## Why

The IMP-02 audit found an uncaught conversion error, loss of empty route policy, an unreadable encoder output, a private-key prefix in QR errors, and stale S3/S4 documentation.

## What Changes

- Return a normal import failure for values that cannot be written to a conf file.
- Reject an explicitly empty URI route policy.
- Omit empty I slots when sharing and define semantic normalization.
- Display a safe format label for invalid QR input.
- Correct the S3/S4 contract text.

Valid canonical links remain compatible. Invalid links with an empty route policy will now fail.

## Capabilities

### New Capabilities

- `awg-uri-import-safety`: Safe conversion, route policy, readable exports, and private import errors.

### Modified Capabilities

- None.

## Impact

App import classification, runtime-state URI serialization, unit tests, and the internal URI document. No dependency, JNI, protobuf, storage schema, or native wire change.
