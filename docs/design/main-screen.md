# Main screen design brief: Live wire

This brief covers the Status screen. The tokens and components it adds are shared, so other screens can adopt them later. `DESIGN.md` stays the contract; this file gives the reasons.

## Direction

The screen is an instrument that shows the traffic path. One signal line runs through the hero card and takes its shape from the real connection state. The base stays neutral ink on paper with Geist Sans and Geist Mono. Identity comes from one signal color that appears only where the connection is live, from large mono numerals, and from motion that reports state.

## Palette

- The neutral roles and the four status tones do not change.
- `signal` is the one new role: cyan `#0E7490` in light and `#22D3EE` in dark. Medium and High contrast darken the light value. The fixed values hold at least 4.5:1 against the page at every contrast level.
- With dynamic color on, `signal` is the wallpaper `primary` role, and the wallpaper scheme decides its contrast.
- `signal` marks a live or connecting path. It is not a status tone. A warning uses `warning` and a failure uses `destructive`, as before.
- The signal line never carries state alone. The headline icon and text always name the state.

## Type scale

- `heroValue`: Geist Mono Medium 32sp/40sp, for the session timer. It shrinks to fit one line at large font scales.
- `readoutValue`: Geist Mono Medium 20sp/28sp, for measured values.
- All other roles are unchanged.

## Shape

The hero is the elevated card with the standard card radius. The signal line is a 2dp stroke with a soft glow. No new radii.

## Motion

Each motion has one purpose.

| Motion | Purpose | Spec |
|---|---|---|
| Signal line changes shape and color with the connection state | continuity | emphasized bucket, 320ms |
| Signal line flows while connected, and a pulse travels while connecting | live state | frame clock, stops with reduced motion |
| Signal line dips under the finger on press | feedback | press spring |
| Measured value cross-fades when it changes | feedback | quick bucket, 120ms |

With animations off, or reduced motion on, the line shows one still frame for each state and values change with no fade.

## Signature element

`RipDpiSignalField` draws the line.

| State | Line |
|---|---|
| Not connected | flat, `outline` color |
| Connecting | flat with a travelling pulse, `signal` |
| Connected | steady flowing wave, `signal` |
| Warning | irregular wave, `warning` |
| Failed | wave up to the failed stage, then a gap and a dim flat line, `destructive` |

`RipDpiSignalField` also takes an optional standing ripple: a still sine with an amplitude, a number of periods, and a start and an end on the width. Onboarding uses it to show the persona and the scope on a line that has no path yet. It is not a connection state and has no color of its own. The hero passes no ripple, and its line is the same in the shader and in the path as before.

On API 33 and later an AGSL `RuntimeShader` draws the moving line and its glow. On API 27 to 32 a `Canvas` path draws the same curve with a wide, faint stroke for the glow. The path also draws every still frame, on every API level. Time and state reach the drawing code as values read in the draw phase, so a frame does not recompose.

## Not in this pass

- No sparkline: the screen state has no history series, and the brief forbids a data-layer change.
- No blur: a glow in the line itself gives the depth, at every API level.
- No change to other screens, to the home widget, or to shared buttons and cards.
