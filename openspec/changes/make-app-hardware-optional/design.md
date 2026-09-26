# Design: Optional app hardware

Task ID: `AND-1790433824419175`

## Decision

Declare these Android features with `android:required="false"`:

- `android.hardware.camera` and `android.hardware.camera.autofocus`, implied
  by CAMERA even when `camera.any` is optional.
- `android.hardware.location`, `android.hardware.location.gps`, and
  `android.hardware.location.network`, corresponding to optional precise and
  coarse location capabilities.

Keep the existing `camera.any` optional declaration. Android's documented
permission-to-feature inference and parent location-feature implication make
these explicit declarations necessary for devices without the hardware.

## Verification

Run `:app:lintGithubFullDebug` and inspect the merged manifest. No behavior
test is needed for this manifest-only distribution fix.
