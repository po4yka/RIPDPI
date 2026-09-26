# Change: Keep app available without optional hardware

Task ID: `AND-1790433824419175`

## Why

The app requests CAMERA and location permissions for optional features. Its
manifest only marks `camera.any` optional. Android lint warns that Google Play
can infer required back-camera and GPS features from the permissions and filter
devices that lack that hardware.

## What Changes

- Explicitly mark camera, autofocus, location, GPS, and network location
  features optional in the app manifest.
- Keep existing permissions and optional camera.any declaration.
- No breaking changes.

## Capabilities

### New Capabilities

- `optional-app-hardware`: Permission declarations do not require camera or
  location hardware for app availability.

### Modified Capabilities

- None.

## Impact

- `:app` manifest and Google Play device filtering. No runtime API, module,
  dependency, resource string, or data contract changes.
