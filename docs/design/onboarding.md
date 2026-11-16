# Onboarding design brief: One continuous setup

This brief covers the onboarding flow, from the first frame after the splash screen to the first frame of the main screen. `DESIGN.md` stays the contract; this file gives the reasons. The main-screen brief is [main-screen.md](main-screen.md).

## How onboarding extends the design system

Onboarding keeps the neutral ink-on-paper base, the Geist type roles, the intro layout metrics, and the existing option cards, banners, buttons, and checklist. It reuses the `signal` color role and `RipDpiSignalField` from the main screen without a change. It adds no color, no type role, and no shape.

It adds two shared pieces:

- A `signature` motion bucket of 560ms in `RipDpiMotion`, for a one-off moment that carries the user from one part of the product to the next.
- `RipDpiHandOffReveal`, a layer above the navigation host. In the frame of a content change it draws a veil in the page color, and then opens the veil as a circle from a named anchor.

`RipDpiPageIndicators` gains an optional page position, so the indicator widths follow the pager during a drag.

## Flow

Eight steps. Every step is complete with buttons only.

| Step | Content | Controls |
|---|---|---|
| 1. Welcome | title, what the app does, language choice | Skip setup, Language, Continue |
| 2. Local first | title, body, two lists of guarantees | Back, Continue |
| 3. Persona | Guided or Advanced | Back, Continue |
| 4. Scope | Whole device or Proxy only | Back, Continue |
| 5. DNS | the built-in resolvers, the default one marked | Back, Continue |
| 6. Background | battery optimization, Always-on VPN, Block without VPN | Back, Open settings, Open VPN settings, Continue |
| 7. Connection test | notes, three-row checklist, result | Back, Skip test, Start test, then Try again, Grant permission, Change DNS, use the suggested mode, Finish without testing |
| 8. Completion | what was set up: interface, traffic mode, DNS resolver, test result | Back or Disconnect, Done |

States that the design covers:

- The language choice opens the language sheet of the Settings screen. The flow continues in the new language on the same step.
- The DNS step selects one built-in resolver. The default resolver keeps the default DNS settings. A new selection resets a finished test.
- The background step is guidance. Its two actions open the battery optimization list and the system VPN settings. It requests no permission. Always-on VPN and Block without VPN show only for Whole device.
- The connection test is idle, asks for the notification permission, asks for VPN consent, starts the mode, checks DNS, checks traffic, succeeds, or fails. A failure names its step and offers the matching recovery.
- A permission dialog appears only after the user presses Start test. The idle step explains each dialog first: a notification note when Android will ask for that permission, the local VPN permission note for Whole device, and a local proxy note for Proxy only.
- Back returns to the previous step and keeps the selections. Back is disabled during the test and hidden after a passed test, because the tunnel is running.
- Every exit shows the completion step first. Skip setup, Skip test, and Finish without testing go to it at once, and a passed test moves to it. Done then completes onboarding: it keeps the tunnel of a passed test, and otherwise stops the test runtime. After a passed test, Disconnect stops the tunnel and completes onboarding.
- Back on the completion step returns to the step that the user left. The completion step does not swipe.
- After process death the flow returns to the saved step. The persona, the scope, and the DNS resolver are saved when the user selects them, so the selections return. The test starts idle again.
- After the hand-off, the Status screen shows one card under the connect control. It explains the signal line and the control, and it stays until the user dismisses it. Onboarding sets its flag at completion, so a user who completed onboarding earlier does not see it.

## Transition between steps

The flow is one screen, not eight.

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
