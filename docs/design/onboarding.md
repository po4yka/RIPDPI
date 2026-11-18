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

## Transition between steps

The flow is one screen, not eight.

- The scene stage stays pinned above the pages. It does not slide with a page. During a drag the scene of the old step fades and moves out and the scene of the new step comes in, with the pager position. [Scene stage](#scene-stage) gives the scenes.
- Every page puts its title at the same height. During a drag the old page fades and blurs out and the new page comes into focus, and each page moves less than the finger. The titles cross over in place.
- The indicator widths follow the pager position.
- A swipe that settles on a new step gives one selection haptic. A button gives its own haptic, so it does not give a second one.
- Every step works with buttons only. The swipe is an extra path.

On the first launch the page content rises and fades in once. The stage is visible from the first frame, because it stands where the splash icon stood, and its first scene opens as that icon.

## Hand-off to the main screen

The app replaces its whole navigation host when onboarding completes, so a route transition or a `SharedTransitionLayout` inside the host cannot join the two screens. `RipDpiHandOffReveal` sits above the host and survives the replacement. The signal line is the shared element: the layer draws it on its way to the cable of the hero link tile, and the tile is hidden until the line lands, then fades in in the quick bucket.

- The stage reports an anchor to `LocalRipDpiHandOff` and no source line, so the main screen opens as a circle from the stage. On the completion step the anchor is the seal of the last scene; on each other step it is the middle of the stage. The splash icon lands on the tombstone of the welcome scene. Where the badge shows, the badge reports its center and radius as the anchor. The onboarding line reports its bounds and look as `sourceLine`, and the report stays when onboarding leaves the screen.
- The link tile in `RipDpiConnectionActuator` reports its bounds and the look of the line as `targetLine` through `Modifier.ripDpiHandOffTarget`, and it is hidden while the layer draws the line. The line lands on the middle of the tile, where its cable runs. The hero has no other change.
- `OnboardingHandOffTrigger` starts the reveal in the composition that replaces the content, so the veil and the main screen reach the screen in one frame. No frame shows the main screen without the veil.

The reveal has three parts.

1. Hold. In the first frame the veil covers the main screen, the line stands where the onboarding line stood, and a ring stands where the badge stood. The veil stays closed until the hero reports its line, for at most the 220ms state duration. The main screen composes for the first time in these frames, and they are slow; the motion does not run through them.
2. Travel. In the first 60% of the 560ms signature motion, about 340ms, the line moves over the closed veil to the hero line, and the ring sits on its center and closes on it. The line does not cross any content of the main screen.
3. Open. In the rest of the motion, about 220ms, the main screen opens as a circle that grows from the line. When the reveal ends the layer draws nothing and the hero shows its own line in the same place.

The travel and the opening each use the signature easing on their own share. A frame that takes longer than 32ms counts as 32ms of the motion, or as half of its time if that is more. On a slow device the motion takes longer and does not skip ahead, and it takes at most twice its duration.

| Case | Hand-off |
|---|---|
| Done, Disconnect, Skip setup, Skip test, Finish without testing, from any step | the same reveal; it depends only on the start destination that leaves onboarding |
| Portrait and landscape | the same reveal; the anchor is the badge, in the page or in the top bar |
| No `sourceLine` reported | the line has the width of the hero line from the start and shows from the ring outward on its way to the hero |
| Right-to-left layout | the same reveal; all positions are root coordinates and are not mirrored |
| The hero line is not on screen at the end of the hold (app lock sends the user to the biometric prompt, the hero is scrolled away, or it reports late) | the circle only: the main screen opens from the place of the badge, the ring grows with the opening and fades, and a reported source line fades in its place |
| Animations off | a cut: nothing is drawn |

The kind of reveal is fixed at the end of the hold. A hero line that reports later does not change a reveal that is on its way.

One `Confirm` haptic marks the end of the reveal, also for the circle and for the cut. The button that ended onboarding gives its own haptic at the press, about half a second earlier. If the connection comes up after the hand-off starts and before the reveal ends, its success haptic replaces this one.

The layer takes no input. A reveal that is cancelled sets its progress to the end, so nothing stays drawn over the app, and it does not play again.

## Scene stage

The app icon is a ghost in a tombstone: the grave of DPI, and the traffic that passes where DPI stood. Onboarding tells that as eight drawn scenes, one for each step. The ghost is the only character. It floats, leans into its motion, and trails its hem; it does not jump and has no smile. It shows its state with its eyes only, and it turns to a dashed outline to pass through a thing.

| Step | Scene | What it shows |
|---|---|---|
| Welcome | Rest in peace, DPI | The scene opens as the app icon. The ghost leaves the stone through its top, the carving shows, the stone cracks and leans, and a crow lands on it. |
| Local first | Unseen | A camera sweeps a red light. A ghost in the light turns to an outline and loses its shadow, so the camera finds nothing. Data from the phone turns back at a dashed limit. |
| Persona | The bench | Guided is one dial and one button. Advanced opens the wings of the console and grows faders, dials, a scope, lamps, and a meter. The ghost moves a control without a touch, and the scope and the meter follow. |
| Scope | The wall | A brick wall with a scanner stands before the open internet. Whole device: every app sends a ghost through the wall. Proxy only: the loop tightens on two apps, and the plain packets of the others are seen by the scanner and stop. |
| DNS | The checkpoint | A split-flap board shows the resolver. The ghost carries a sealed name through a checkpoint; the stamp goes through the ghost and marks only the ground, and under the gantry the name reads as noise. |
| Background | The night watch | Night at the grave. The phone sleeps with its tunnel key lit. The ghost keeps its round with a lantern, and the light sends a hand back into the grave. |
| Connection test | Three barriers | One barrier for each stage of the checklist. A scanner reads the ghost; on a pass the ghost goes through the barrier and the barrier cracks; on a failure the barrier takes `destructive` and the ghost is pushed back. |
| Completion | Free | After a passed test the barriers fall to rubble, small ghosts drift to the globe, and the seal is the app icon with a new crack. Without a passed test the barriers stand and the seal has no check. |

- The scenes use the roles of the theme only: `foreground` for the dark shapes, `background` for the ghost, `signal` for the path, `destructive` for what the censor does, and mixes of these. The night scene takes its sky from the dark end of the theme. On a dark page the ghost stays light and the tombstone is a step above the page.
- A scene never carries state alone. The title, the option cards, the checklist, and the banner name each state, and the stage has no semantics.
- A scene that comes into view plays from its start. The scene of a restored step starts at its still frame; only the welcome scene plays on the first frame, because it opens as the splash icon.
- With motion off every scene is one still frame of its state, and a new selection gives a new still frame.
- The stage has no localized text. The carving on the stone is the name of the app, and the resolver on the board is data.
- The drawing code is `ui/screens/onboarding/scenes`. Its numbers are the coordinates and timings of the drawings in a 300 by 170 frame, so those files turn the magic-number rule off.
- In a window shorter than 600dp, at a font scale of 150% and above, and in the landscape layout, the content needs the height. The step badge on the signal line then takes the place of the stage, as [Signature moment](#signature-moment) describes.

## Signature moment

Where the step badge shows, one line runs through the whole first launch. It is `RipDpiSignalField`, and the pinned badge sits on it. The hand-off carries it to the cable of the link tile of the main-screen hero.

| Step or state | Line |
|---|---|
| The first step | flat, `outline` |
| The persona step and every later step before the test | a standing ripple, `outline`: Guided is a few wide ripples, Advanced is many fine ones |
| The scope step and every later step before the test | Whole device keeps the ripple on the whole line; Proxy only keeps it on the half after the badge |
| A drag between two steps | the line dips at a point that travels with the pager position; the ripple grows on the drag to the persona step, takes the scope on the drag to the scope step, and goes flat on the drag to the test step |
| The test step before the test | flat, `outline` |
| The test runs, including a permission request | one travelling packet, `signal` |
| The test passed | an even stream of packets, `signal` |
| The test failed | the line up to the failed stage, then a gap and a dim flat line, `destructive` |

The ripple states the two choices, not a path. It does not move with time and keeps the `outline` color, because `signal` marks a live or connecting path only. A new selection reshapes the ripple over the emphasized bucket. The steps are found by their kind, so a step added to the flow does not move the ripple to another step.

The three test stages sit at the quarter points of the line, in the order of the checklist under it. A failed test breaks the line only on the test step: the persona and the scope steps have no path to break. On a step after the test step the line stays live for a passed test and is flat for every other state.

When the test passes, the tunnel runs, so the line is live before the user leaves onboarding. The hand-off then opens the main screen, where the line lands on the cable of the hero link tile, which shows the same live session. The line never carries state alone: the title, the option cards, the checklist, and the banner name each state.

The result of the test gives one haptic: success when the test passes and error when it fails. A result that is already on the screen gives none, so a rotation or a restored process is silent.

In the landscape layout the badge sits in the top bar between Back and the text action, and the line runs through the bar. The line is drawn only in the space that the two controls leave, so it does not cross a control or a label in either layout direction or at a large font scale. A long label leaves a short line or none.

The splash icon enters on API 31 and later. A ground line draws out from the middle, the stone rises out of it over the emphasized bucket, the ghost appears in the stone, and the line fades, so the splash ends on the app icon that the welcome scene opens as. The platform starts the icon animation before the launch animation has opened the window, so the entry waits 400ms; without the wait it ends inside the growing window and nobody sees it. The whole entry takes 920ms of the platform's 1000ms. The exit does not wait for it. The move to the badge takes the signature bucket, so the stone has risen before it lands; a fade can start while the entry still plays. Below API 31 and with animations off the icon is the still mark.

The splash screen joins the line. When the app starts on onboarding, the splash screen stays until the badge is placed, for 500ms at most, so the startup gate does not show between the two. The splash icon then moves to the place of the badge and shrinks to its size while the splash screen fades, over the signature bucket. With animations off the splash screen is removed at once. For every other start destination the splash screen fades out on API 31 and later and is removed at once below API 31. Android 12 and later must know about a splash exit before the start destination is known, so the app owns the exit for every start destination and the short fade stands in for the system exit animation.

## Reduced motion and older devices

- With animations off, the pages change with no fade, the badge and the indicators change only when a step settles, the entrance does not play, and the hand-off is a cut, as it was.
- The badge, the page fade and parallax, the indicators, and the hand-off use `graphicsLayer`, `Canvas`, and layout-phase reads. They work on every supported API level. The signal line is a `Canvas` path and blocks on every API level.
- The page blur uses `RenderEffect` and needs API 31. On API 27 to 30 a leaving page fades and moves with no blur. A page at rest has no blur on any API level.

## Layout

- A landscape window shorter than 480dp moves the badge into the top bar, so the content keeps its height.
- At a font scale of 150% and above the pinned badge uses its smaller size.
- The form column keeps its maximum width on tablets and foldables.
- At large font scales each page scrolls under the pinned badge, and the footer stays in place.
