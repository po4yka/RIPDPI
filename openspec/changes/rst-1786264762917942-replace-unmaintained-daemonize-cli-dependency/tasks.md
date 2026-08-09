# RST-1786264762917942: Replace unmaintained daemonize CLI dependency

## Objective

Replace daemonize 0.5.0 before the waiver deadline while keeping it absent from Android graphs.

## Ownership

- serialized native dependency graph, CLI process mode, lockfile, and advisory waiver

## Execution

- [ ] RST-1786264762919066 Replace daemonize 0.5.0, preserve CLI process-mode behavior, and remove the waiver #chore @item:RST-1786264762917942

## Verification

- Android reverse-dependency assertion, CLI tests, cargo-deny advisories, and waiver validator
