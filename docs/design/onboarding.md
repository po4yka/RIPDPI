# Onboarding design brief: One continuous setup

This brief covers the onboarding flow, from the first frame after the splash screen to the first frame of the main screen. `DESIGN.md` stays the contract; this file gives the reasons. The main-screen brief is [main-screen.md](main-screen.md).

## How onboarding extends the design system

Onboarding keeps the neutral ink-on-paper base, the Geist type roles, the intro layout metrics, and the existing option cards, banners, buttons, and checklist. It reuses the `signal` color role and `RipDpiSignalField` from the main screen without a change. It adds no color, no type role, and no shape.

It adds two shared pieces:

- A `signature` motion bucket of 560ms in `RipDpiMotion`, for a one-off moment that carries the user from one part of the product to the next.
- `RipDpiHandOffReveal`, a layer above the navigation host. In the frame of a content change it draws a veil in the page color, and then opens the veil as a circle from a named anchor.

`RipDpiPageIndicators` gains an optional page position, so the indicator widths follow the pager during a drag.

## Flow

The step order, controls, and logic do not change.

| Step | Content | Controls |
|---|---|---|
| 1. Local first | title, body, two lists of guarantees | Skip setup, Continue |
| 2. Persona | Guided or Advanced | Back, Continue |
| 3. Scope | Whole device or Proxy only | Back, Continue |
| 4. Connection test | permission note, three-row checklist, result | Back, Skip test, Start test, then Try again, Grant permission, Change DNS, use the suggested mode, Finish without testing, Done, Disconnect |

States that the design covers:

- The connection test is idle, asks for the notification permission, asks for VPN consent, starts the mode, checks DNS, checks traffic, succeeds, or fails. A failure names its step and offers the matching recovery.
- A permission dialog appears only after the user presses Start test. For the VPN mode, the idle step shows the note that explains the local VPN permission before the system dialog can appear.
- Back returns to the previous step and keeps the selections. Back is disabled during the test and hidden after a successful test, because the tunnel is running.
- Skip setup on step 1 and Skip test on step 4 leave the flow at once.
- After process death the flow starts at step 1. The persona and the scope are saved when the user selects them, so the selections return.

## Transition between steps

The flow is one screen, not four.

- One step badge stays pinned above the pages. It does not slide with a page. Its icon changes from one step to the next, and the change follows the pager position, so a drag scrubs it.
- Every page puts its title at the same height. During a drag the old page fades and blurs out and the new page comes into focus, and each page moves less than the finger. The titles cross over in place.
- The indicator widths follow the pager position.
- A swipe that settles on a new step gives one selection haptic. A button gives its own haptic, so it does not give a second one.
- Every step works with buttons only. The swipe is an extra path.

On the first launch the page content rises and fades in once. The badge is visible from the first frame, because it stands where the splash icon stood.

## Hand-off to the main screen

The app replaces its whole navigation host when onboarding completes, so a route transition or a `SharedTransitionLayout` inside the host cannot join the two screens. `RipDpiHandOffReveal` sits above the host and survives the replacement. The signal line is the shared element, and the layer draws it on its way.

- The badge reports its center and radius to `LocalRipDpiHandOff` as the anchor. The onboarding line can report its bounds and look as `sourceLine`.
- The hero line in `RipDpiConnectionActuator` reports its bounds and look as `targetLine` through `Modifier.ripDpiHandOffTarget`, and it is hidden while the layer draws the line. The hero has no other change.
- `OnboardingHandOffTrigger` starts the reveal in the composition that replaces the content, so the veil and the main screen reach the screen in one frame. No frame shows the main screen without the veil.

In that first frame the veil covers the main screen, the line stands where the onboarding line stood, and a ring stands where the badge stood. Over the 560ms signature duration the line moves to the hero line and takes its width, and the ring travels with it and closes on it. At the same time the main screen opens as a circle that grows from the place of the badge. When the reveal ends the layer draws nothing and the hero shows its own line in the same place.

| Case | Hand-off |
|---|---|
| Done, Disconnect, Skip setup, Skip test, Finish without testing, from any step | the same reveal; it depends only on the start destination that leaves onboarding |
| Portrait and landscape | the same reveal; the anchor is the badge, in the page or in the top bar |
| No `sourceLine` reported | the line grows out of the ring on its way to the hero |
| Right-to-left layout | the same reveal; all positions are root coordinates and are not mirrored |
| The hero line is not on screen (app lock sends the user to the biometric prompt, or the hero is scrolled away) | the circle only: the ring grows with the opening and fades, and a reported source line fades in its place |
| Animations off | a cut: nothing is drawn |

The destination reports its line one or more frames after the replacement. The travel starts from the frame of that report, so the line does not jump.

One `Confirm` haptic marks the moment the line arrives, also for the circle and for the cut. The button that ended onboarding gives its own haptic at the press, about half a second earlier. If the connection comes up after the hand-off starts and before the line arrives, its success haptic replaces the arrival haptic.

The layer takes no input. A reveal that is cancelled sets its progress to the end, so nothing stays drawn over the app, and it does not play again.

## Signature moment

One line runs through the whole first launch. It is `RipDpiSignalField`, the line of the main-screen hero, and the pinned badge sits on it.

| Step or state | Line |
|---|---|
| Steps 1 to 3, and step 4 before the test | flat, `outline` |
| A drag between two steps | the flat line dips at a point that travels with the pager position |
| The test runs, including a permission request | flat with a travelling pulse, `signal` |
| The test passed | steady flowing wave, `signal` |
| The test failed | wave up to the failed stage, then a gap and a dim flat line, `destructive` |

The three test stages sit at the quarter points of the line, in the order of the checklist under it. A failed test breaks the line only on step 4: the persona and the scope steps have no path to break.

When the test passes, the tunnel runs, so the line is live before the user leaves onboarding. The hand-off then opens the main screen, where the hero shows the same live line. The line never carries state alone: the title, the checklist, and the banner name each state.

The line is not drawn in the landscape layout, where the badge sits in the top bar between the controls.

## Reduced motion and older devices

- With animations off, the pages change with no fade, the badge and the indicators change only when a step settles, the entrance does not play, and the hand-off is a cut, as it was.
- The badge, the page fade and parallax, the indicators, and the hand-off use `graphicsLayer`, `Canvas`, and layout-phase reads. They work on every supported API level. The signal line is an AGSL `RuntimeShader` on API 33 and later; on API 27 to 32, and for the still frame with animations off, `RipDpiSignalField` draws the same curve as a `Canvas` path.
- The page blur uses `RenderEffect` and needs API 31. On API 27 to 30 a leaving page fades and moves with no blur. A page at rest has no blur on any API level.

## Layout

- A landscape window shorter than 480dp moves the badge into the top bar, so the content keeps its height.
- At a font scale of 150% and above the pinned badge uses its smaller size.
- The form column keeps its maximum width on tablets and foldables.
- At large font scales each page scrolls under the pinned badge, and the footer stays in place.
