# DIA-18 repair evidence

Portfolio: `DGN-1794748905877489`. OpenSpec: `dia18-dns-ip-evidence`.

This change repairs the twelve confirmed findings from the local DIA-18 audit dated 2026-11-15. It does not add an active DNS leak test or attest an underlay route. The original audit remains a record of the pre-fix state.

## Requirement coverage

| Finding | Result | Evidence |
|---|---|---|
| F1: active network switch | Both entry points pass the switch. The scheduler suppresses active checks, reflection and ASN enrichment. Local inventory remains available. | Scheduler, presenter and assembler regressions; real loopback sink stays quiet with requests disabled. |
| F2: family grouping | Consensus compares IPv4 with IPv4 and IPv6 with IPv6. Ordinary dual-stack observations do not create channel conflicts. | Actual comparison, consensus and verdict regressions for equal dual-stack and disjoint families. |
| F3: numeric identity | Reflection and consensus use canonical numeric IP identity. | Equivalent IPv6 spellings compare equal; cached ASN keys use the same identity. |
| F4: provider contracts | Absent traits remain unknown. Provider identity must match the requested public IP. Empty or malformed responses cannot vote. | Provider parser regressions, including contradictory aliases and equivalent IPv6 aliases. |
| F5: country uncertainty | Country and ASN require sufficient agreeing opinions. Partial data remains explicit. | Country quorum, tie and final-verdict regressions. |
| F6: resolver context | Preflight and ASN enrichment retain the selected resolver configuration and binding. Home uses the shared settings mapper. App DNS settings remain distinct from diagnostic settings. | Resolver-context regressions and controlled local DoH/HTTP fixtures. |
| F7: IPv6 error state | Only a successful lookup without AAAA is skipped. HTTP, TLS and payload errors remain errors. | Real IPv6 loopback HTTP 500 and malformed-response regressions. |
| F8: execution state | Observation, incomplete, skipped and error states remain visible through reports, exports and UI. Incomplete results cannot display a perfect score. Confirmed partial positives remain visible. | DNS, export, report, score and UI semantics regressions; rendered incomplete-result card. |
| F9: reflected payload | A complete plain IP or an explicit JSON IP field is required. Ambiguous, local and malformed payloads are rejected. | Actual local HTTP responses with valid JSON and invalid payload variants. |
| F10: cancellation and limits | Owned HTTP reads and managed DNS calls follow coroutine cancellation. Response bytes and execution time are bounded. | Real stalled-body and oversized-body tests; local resolver cancellation tests. |
| F11: route claims | Unbound observations are named default-route. An ASN difference has low confidence and does not attest WARP or VPN bypass. | Consensus, narrative and verdict regressions. |
| F12: copied report | IP comparison and consensus retain endpoint statuses, DNS answers and reasons in the copied report. Privacy masking still applies. | Actual comparison-result report regression in public and private modes. |

## Observed regression run

Source under test: `2a3731223a6895056b3eeeccda3d422bf890c2f1`, with the equivalent PCAP loop correction and formatter output then committed as part of `d9ed51170d6af6e21625209181f6255ed150678d`. The later rebase adds only upstream Rust CLI test changes and task records. It does not change the tested Kotlin sources.

The following commands ran through `build-gate --lane ripdpi` with `-Pripdpi.skipNativeBuild=true --max-workers=4 --console=plain`:

```text
./gradlew :core:diagnostics-data:testDebugUnitTest :core:detection:testDebugUnitTest \
  :core:diagnostics-data:ktlintCheck :core:diagnostics-data:detekt \
  :core:detection:ktlintCheck :core:detection:detekt :app:ktlintCheck :app:detekt
./gradlew :app:testGithubFullDebugUnitTest \
  --tests '*DetectionScreenSemanticsTest' --tests '*DetectionAuxStateOwnerTest' \
  --tests '*DetectionGeneratedLocaleTest' --tests '*DetectionResultPresenterTest' \
  --tests '*HomeDiagnosticsAugmentationModuleTest' --tests '*PcapCaptureContentTest'
```

The full two-module run passed 552 tests. The selected app run passed 49 tests: 13 screen semantics, 11 state owner, 10 presenter, five locale, three Home and seven PCAP tests. Both runs had zero failures, errors or skipped tests. JUnit reports and rendered incomplete-state cards were saved before disposable build outputs were removed.

The cancellation oracle includes real loopback TLS DoH traffic and raw sockets returned by the standard socket factory. It does not rely on recorded fake calls. A prior native DNS `Call.cancel()` attempt returned while its HTTP cache query still ran. The final managed DoH call owns its dispatcher and cancels that transport as well as its DNS subscription. A late-enqueue guard prevents a cancelled call from starting another HTTP child call. Calls share the existing executor and connection pool; they do not shut down a global executor.

The pinned OkHttp 5.5.1 cache implementation explicitly retains an in-flight query after its last subscriber is cancelled. This explains why forwarding DNS cancellation alone was insufficient. See [RealDnsCache at the pinned commit](https://raw.githubusercontent.com/square/okhttp/be5151ac3b63d3db405b9dbb62632c36cc6a657e/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/dns/RealDnsCache.kt). The fix retains the upstream DoH encoder, parser, bootstrap and TLS checks. This source check establishes S for that specific cache contract, not full DoH conformance.

The first combined run on the newer upstream base stopped at the PCAP loop Detekt rule before tests. The separate loop correction keeps its EOF and packet/byte limit behavior. The accepted run includes all seven existing PCAP content tests. No baseline or gate was relaxed.

The full PCAP export suite then passed 36 tests with zero failures, errors or skips, together with its Detekt and ktlint checks:

```text
./gradlew :core:pcap-export:testDebugUnitTest :core:pcap-export:detekt \
  :core:pcap-export:ktlintCheck -Pripdpi.skipNativeBuild=true --max-workers=4 --console=plain
```

The additional upstream gate corrections are isolated in `0673cec203` and `ea6418994f`. They preserve file-lock ownership, EOF and size limits, cancellation rollback and the full provider exception boundary. New tests cover checked and runtime exceptions with provider data and a fatal error without rollback. The final source revision for global checks is `ea6418994f176e6417c8c77cc2f67b22fb8d3882`.

## Combined gate status

`./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true --max-workers=4 --console=plain` ran on the final combined source. It is blocked by an upstream Detekt `LargeClass` finding at `core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnTunnelRuntime.kt:58`. DIA-18 does not modify that class. The earlier upstream PCAP failures were corrected and their owning tests passed. No suppression, baseline increase or gate removal was used. Full static analysis is therefore **not green** on the final base; the earlier passing run on an older base is not substituted for it.

The following final checks passed on source revision `ea6418994f176e6417c8c77cc2f67b22fb8d3882`:

| Command | Observed result |
|---|---|
| `./gradlew :app:verifyRoborazziGithubFullDebug --tests '*RipDpiScreenCatalogScreenshotTest.detectionCheck*' --tests '*StatusVisualIndicatorScreenshotTest*' -Pripdpi.skipNativeBuild=true --max-workers=4 --console=plain` | Eight tests passed; zero failures, errors or skips. No golden changes. |
| `just ci-gates` | Passed, including contracts, locale parity, translation export, budgets and API snapshots. |
| `python3 scripts/ci/check_architecture_health.py` | Passed. |
| `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1` | Passed. This is metadata validation, not a native build. |
| `./taskctl validate` | Passed. |
| `./taskctl openspec cli validate dia18-dns-ip-evidence --strict --no-interactive` | Passed. |
| `git diff --check` | Passed. |

The local evidence archive is `/Users/npochaev/.codex/tmp/dia18-repair-evidence/`: `rebased-integration-accepted.log`, `integration-rebased/`, `pcap-gate/`, `final-gates-accepted/` for the blocked full static run, and `final-gates-remaining/` for the passing remaining gates. These are local evidence files, not published CI artifacts. No Cargo lock, dependency version, baseline or PNG golden file changed in the repair.

## Finding commits

The repair uses separate finding commits, followed by reviewed contract integration. Shared cancellation and execution-state fixes require more than one commit.

| Finding | Main repair commits |
|---|---|
| F1 | `d8a671f90e` |
| F2 | `fd3d777228` |
| F3 | `72692b76ee` |
| F4 | `f717089016` |
| F5 | `145dcc50be` |
| F6 | `a14c6af351`, `1d20dc5af9` |
| F7 | `c36de9fb82` |
| F8 | `fa400b678b`, `a737ffb94e`, `657013ff87`, `161c8a4bc9` |
| F9 | `960b120ff9` |
| F10 | `e7f86ab384`, `39b1772523`, `29e74929d7`, `a9aa9ddaaf`, `fe872422af`, `9d05c5ceff` |
| F11 | `c92dccf84b` |
| F12 | `ddf677c82f` |

Independent reviewers checked provider consensus, resolver cancellation, status propagation, UI and rebase conflict boundaries. Their final verdict had no actionable critical or warning finding. Review C does not replace the observed V runs.

## Evidence limits

C means inspected implementation and callers. V means an observed local test run. S applies only to a specific checked primary-source requirement. E means observed compatibility with a concrete external service. Local fixtures do not establish E.

The local tests use controlled JVM/Robolectric, loopback HTTP, HTTPS and DNS fixtures. They do not establish compatibility with the hosted reflection or GeoIP services. No physical-device probe, external active scan, root activation or real credential use is part of this change.

SYSTEM DNS uses platform lookup APIs. Cancellation prevents later application requests, but the operating system may finish an in-flight platform lookup. Controlled DIRECT and DoH calls own their transport cancellation.

Network handover freshness, Android cleartext-redirect policy and the accuracy of provider databases remain separate acceptance risks from the audit. A foreign-country or ASN finding remains a heuristic, not proof of a VPN.


The catalog still has eleven reflection descriptors. Strict parsing deliberately rejects arbitrary HTML. The public response contract for `2ip.ru` and `ip.mail.ru` remains unverified; no undocumented HTML scraper, replacement token API or hosted compatibility claim was added. GeoIP still enriches one reflected seed with provider opinions; it does not measure five independent egress addresses.

Direct resolver admission limits and independent-call cancellation received code review and bounded local tests. A load test for queue saturation, a network handover epoch test and an approved VPN-on underlay sentinel run remain unexecuted. DNS settings observation remains observation-only. An active leak test is a separate scope.

The JVM commands use the documented native-build skip flag. A packaging attempt without that flag was blocked by an absent pinned NaiveProxy ARM64 artifact. No APK, native artifact or device acceptance is claimed. Required device and remote verification entries stay pending until their evidence exists; the portfolio remains in review.
