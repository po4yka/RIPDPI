# Change: Keep physical handovers separate from transient lease state

Task ID: `SVC-1791566464861507`

## Why

The clean c25730eca Android Xray acceptance run failed its first encrypted DNS baseline before peer loss. The service received a link refresh and restarted Xray, then established a second tunnel with equal builder inputs. No owned DoH request reached the fixture. The physical network disconnected later. Raw callbacks suggest that default callback feedback can remove a transient underlay lease token while the app's excluded UID still uses the physical network. This is a candidate cause. The exact original event field must be captured before changing the monitor; equal stored policy hashes alone do not prove equal event fingerprints.

## What Changes

- Observe physical network changes without treating transient lease acquisition or loss alone as a physical handover.
- Keep genuine physical network and underlay recovery events.
- Verify initial DNS traffic and peer recovery through the real provider.
- No breaking interface, storage, or wire changes are planned.

## Capabilities

### New Capabilities

- `physical-handover-observation`: Distinguish physical path changes from VPN feedback and initial observation state.

### Modified Capabilities

- None.

## Impact

- Service handover observation, fingerprint capture integration, and service regression tests.
- Real Android acceptance and combined source validation.
- No fingerprint key recipe, persistence schema, native ABI, dependency, locale, or quality baseline change.
