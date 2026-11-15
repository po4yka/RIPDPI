# Change: Correct relay clock trust and replay handling

Task ID: `RLY-1794757174777852`

## Why

An observation from one outbound relay can move the replay clock of another. The existing recovery vote relies on endpoint strings and does not receive samples when the active wire freshness check rejects them.

## What Changes

- Give each Mieru or Shadowsocks relay factory its own network time.
- Confirm time after a valid Mieru or Shadowsocks response and allow a new relay runtime to start from corrected device time.
- Give an active Shadowsocks UDP association a stable time source for both packet freshness and replay state.
- Handle reordered observations, operating-system clock errors, and extreme time values safely.

## Capabilities

- relay-network-time

## Impact

The change applies to the native relay clock and its TCP and UDP callers. The profile schema, wire windows, and socket protection policy stay in force.
