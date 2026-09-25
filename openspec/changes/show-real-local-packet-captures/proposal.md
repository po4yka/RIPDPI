# Change: Show real local packet captures

Task ID: `DGN-1790329640901759`

## Why

The reachable packet capture list and viewer display fixed demonstration data. Users cannot inspect captures recorded by the app.

## What Changes

- List completed captures stored in the app's private capture directory.
- Open the selected capture and show its recorded packets.
- Show empty or error states when data is absent or unreadable. No breaking contract change.

## Capabilities

### New Capabilities

- `diagnostics/pcap-browser`: Browse and inspect locally recorded packet captures.

### Modified Capabilities

- None.

## Impact

- Android app PCAP routes, navigation, and presentation tests.
- Existing `:core:pcap-export` controller and reader remain the data source. Controller listing errors become visible to the app. No schema, JNI, or dependency change.
