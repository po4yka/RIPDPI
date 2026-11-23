# Change: Allow PCAP capture and viewing without root

Task ID: `DGN-1795412913098712`

## Why

The app hides PCAP controls and blocks recording when root mode is off. The PCAP writer reads from the VPN tunnel.

## What Changes

- Allow users to request a capture with root mode off.
- Show completed captures in Diagnostics when the VPN is disconnected.

## Capabilities

### New Capabilities

- `diagnostics/pcap-access` defines capture and viewing availability.

### Modified Capabilities

None.

## Impact

Android diagnostics, the VPN capture consent check, and capture text in all supported locales.
