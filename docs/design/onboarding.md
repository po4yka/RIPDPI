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

The app replaces its whole navigation host when onboarding completes, so a route transition cannot join the two screens. `RipDpiHandOffReveal` sits above the host and survives the replacement.

In the frame in which the main screen replaces onboarding, the veil covers it and a ring stands where the badge stood. The main screen then opens as a circle that grows from the center of that ring. The same reveal runs for Done, Disconnect, Skip setup, Skip test, and Finish without testing.

## Signature moment

One line runs through the whole first launch. It is `RipDpiSignalField`, the line of the main-screen hero, and the pinned badge sits on it.

| Step or state | Line |
|---|---|
| The first step | flat, `outline` |
| The persona step and every later step before the test | a standing ripple, `outline`: Guided is a few wide ripples, Advanced is many fine ones |
| The scope step and every later step before the test | Whole device keeps the ripple on the whole line; Proxy only keeps it on the half after the badge |
| A drag between two steps | the line dips at a point that travels with the pager position; the ripple grows on the drag to the persona step, takes the scope on the drag to the scope step, and goes flat on the drag to the test step |
| The test step before the test | flat, `outline` |
| The test runs, including a permission request | flat with a travelling pulse, `signal` |
| The test passed | steady flowing wave, `signal` |
| The test failed | wave up to the failed stage, then a gap and a dim flat line, `destructive` |

The ripple states the two choices, not a path. It does not move with time and keeps the `outline` color, because `signal` marks a live or connecting path only. A new selection reshapes the ripple over the emphasized bucket. The steps are found by their kind, so a step added to the flow does not move the ripple to another step.

The three test stages sit at the quarter points of the line, in the order of the checklist under it. A failed test breaks the line only on the test step: the persona and the scope steps have no path to break. On a step after the test step the line stays live for a passed test and is flat for every other state.

When the test passes, the tunnel runs, so the line is live before the user leaves onboarding. The hand-off then opens the main screen, where the hero shows the same live line. The line never carries state alone: the title, the option cards, the checklist, and the banner name each state.

The result of the test gives one haptic: success when the test passes and error when it fails. A result that is already on the screen gives none, so a rotation or a restored process is silent.

In the landscape layout the badge sits in the top bar between Back and the text action, and the line runs through the bar. The line is drawn only in the space that the two controls leave, so it does not cross a control or a label in either layout direction or at a large font scale. A long label leaves a short line or none.

The splash screen joins the line. When the app starts on onboarding, the splash screen stays until the badge is placed, for 500ms at most, so the startup gate does not show between the two. The splash icon then moves to the place of the badge and shrinks to its size while the splash screen fades, over the signature bucket. With animations off the splash screen is removed at once. For every other start destination the splash screen fades out on API 31 and later and is removed at once below API 31. Android 12 and later must know about a splash exit before the start destination is known, so the app owns the exit for every start destination and the short fade stands in for the system exit animation.

## Reduced motion and older devices

- With animations off, the pages change with no fade, the badge and the indicators change only when a step settles, the entrance does not play, and the hand-off is a cut, as it was.
- The badge, the page fade and parallax, the indicators, and the hand-off use `graphicsLayer`, `Canvas`, and layout-phase reads. They work on every supported API level. The signal line is an AGSL `RuntimeShader` on API 33 and later; on API 27 to 32, and for the still frame with animations off, `RipDpiSignalField` draws the same curve as a `Canvas` path.
- The page blur uses `RenderEffect` and needs API 31. On API 27 to 30 a leaving page fades and moves with no blur. A page at rest has no blur on any API level.

## Layout

- A landscape window shorter than 480dp moves the badge into the top bar, so the content keeps its height.
- At a font scale of 150% and above the pinned badge uses its smaller size.
- The form column keeps its maximum width on tablets and foldables.
- At large font scales each page scrolls under the pinned badge, and the footer stays in place.
