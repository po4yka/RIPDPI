# Onboarding design brief: One continuous setup

This brief covers the onboarding flow, from the first frame after the splash screen to the first frame of the main screen. `DESIGN.md` stays the contract; this file gives the reasons. The main-screen brief is `docs/design/main-screen.md` on the main-screen branch.

## Scope of this pass

The main-screen redesign (the `signal` color role and `RipDpiSignalField`) is not on `main` yet. This pass builds every part of the onboarding design that does not need those two pieces. The signal line is the last part, and its slot is named in [Signature moment](#signature-moment).

## How onboarding extends the design system

Onboarding keeps the neutral ink-on-paper base, the Geist type roles, the intro layout metrics, and the existing option cards, banners, buttons, and checklist. It adds no color, no type role, and no shape.

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
- Every page puts its title at the same height. During a drag the old page fades out and the new page fades in, and each page moves less than the finger. The titles cross over in place.
- The indicator widths follow the pager position.
- A swipe that settles on a new step gives one selection haptic. A button gives its own haptic, so it does not give a second one.
- Every step works with buttons only. The swipe is an extra path.

On the first launch the page content rises and fades in once. The badge is visible from the first frame, because it stands where the splash icon stood.

## Hand-off to the main screen

The app replaces its whole navigation host when onboarding completes, so a route transition cannot join the two screens. `RipDpiHandOffReveal` sits above the host and survives the replacement.

In the frame in which the main screen replaces onboarding, the veil covers it and a ring stands where the badge stood. The main screen then opens as a circle that grows from the center of that ring. The same reveal runs for Done, Disconnect, Skip setup, Skip test, and Finish without testing.

## Signature moment

The signature moment is the hand-off: the badge that stayed with the user through every step becomes the opening through which the main screen appears.

Planned next, when the main-screen branch lands: `RipDpiSignalField` draws one line through the badge. The line is flat on step 1, takes its shape from the persona and the scope, carries the three test stages on step 4, and turns to the `signal` color when the test passes. At the hand-off the line continues into the hero card of the main screen. The badge is the slot for that line; no other layout change is needed.

## Reduced motion and older devices

- With animations off, the pages change with no fade, the badge and the indicators change only when a step settles, the entrance does not play, and the hand-off is a cut, as it was.
- This pass uses `graphicsLayer`, `Canvas`, and layout-phase reads only. They work on every supported API level, so no fallback is needed. The AGSL shader arrives with `RipDpiSignalField`, which has its own `Canvas` fallback below API 33.
- No blur: the design system uses no decorative depth.

## Layout

- A landscape window shorter than 480dp moves the badge into the top bar, so the content keeps its height.
- At a font scale of 150% and above the pinned badge uses its smaller size.
- The form column keeps its maximum width on tablets and foldables.
- At large font scales each page scrolls under the pinned badge, and the footer stays in place.
