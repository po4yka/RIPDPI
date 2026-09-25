# RLY-1790340340749310

## Objective

Create and edit all six missing relay kinds without losing profile fields or credentials.

## Ownership

Relay editor agent: `ModeEditorRelaySection.kt`, `RelayFields.kt`, `RelayFieldActions.kt`, `ModeEditorActions.kt`, `ModeEditorActionFactory.kt`, `ConfigDraftSupport.kt`, `ConfigRelayDraftMapping.kt`, `ConfigDraftValidationSupport.kt`, `ConfigRelayPersistenceSupport.kt`, new relay field composables, `ConfigScreen.kt`, `VpnConfigScreen.kt`, `Route.kt`, `RipDpiNavHost.kt`, Mieru/SSH dedicated editor state/ViewModels/routes, navigation test and UI audit route registry, and matching tests. Shared locales and generated board are serialized at integration.

## Execution

- [x] RLY-1790340429179687 Map all six relay kinds through draft hydration, editing, validation and persistence #bug @item:RLY-1790340340749310
- [x] RLY-1790340431151340 Verify relay round trips, rejection cases and app lint #bug @item:RLY-1790340340749310

## Verification

Run targeted app unit tests, `:app:lintGithubFullDebug`, `./taskctl validate`, and combined-tree gates.
