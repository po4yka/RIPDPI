# Plan

1. Step 1 - Add home mode state and card UI models
   - Demo: `MainUiState.modeCards` exposes exactly three cards, with active/busy labels derived from existing connection and diagnostics state.
   - Expected wave: add stable UI models, add defaulted `MainUiState.modeCards`, wire assembly, and add focused state tests.

2. Step 2 - Build the reusable Home mode card composable
   - Demo: a reusable `HomeModeCard` renders active, inactive, and busy modes with disabled primary semantics when needed.
   - Expected wave: create `HomeModeCard.kt`, add previews, add semantics/click tests.

3. Step 3 - Replace Home content with three mode cards
   - Demo: Home renders the existing warning banners and exactly three mode cards while keeping diagnostics bottom sheets functional.
   - Expected wave: update `HomeScreen` layout, preserve expanded layout behavior, update Home UI tests.

4. Step 4 - Wire Local DPI Bypass card actions
   - Demo: Local Bypass toggles `Mode.Proxy` via the existing connection action path and config navigation reaches the local bypass route.
   - Expected wave: add ViewModel toggle method, route lambda wiring, and fake-backed unit tests.

5. Step 5 - Wire VPN card actions
   - Demo: VPN toggles `Mode.VPN` via the existing connection action path and config navigation reaches the VPN route.
   - Expected wave: add ViewModel toggle method, route lambda wiring, and fake-backed unit tests.

6. Step 6 - Wire Diagnostic card actions
   - Demo: the diagnostic card runs the same full-analysis path as the existing Home diagnostics action and opens the Diagnostics tab from card body interaction.
   - Expected wave: connect Home callbacks, update status-line derivation from latest audit, and add focused UI/action tests.

7. Step 7 - Add Config mode section switcher and sub-routes
   - Demo: Config has a saveable Local Bypass / VPN switcher, and `Route.fromStableRoute` resolves both new config sub-routes.
   - Expected wave: extend `Route`, add strings, add saveable section state, and add navigation/section tests.

8. Step 8 - Build Local Bypass config sub-screen
   - Demo: Local Bypass settings render desync, listen address, DNS, and mode rows, with desync and DNS rows invoking the correct callbacks.
   - Expected wave: create `LocalBypassConfigScreen.kt`, reuse existing rows/cards, add preview and focused compose tests.

9. Step 9 - Build VPN config sub-screen
   - Demo: VPN settings render relay, credentials/protocol, and DNS rows, with relay/DNS callbacks wired.
   - Expected wave: create `VpnConfigScreen.kt`, reuse existing rows/cards, add preview and focused compose tests.

10. Step 10 - Make Diagnostics tab standalone
    - Demo: navigating directly to Diagnostics shows an enabled Run button and uses the same `DiagnosticsScanController` path as Home.
    - Expected wave: remove Home-state dependency, wire `DiagnosticsViewModel.runScan()`, add direct-navigation test, and run final verification gates.
