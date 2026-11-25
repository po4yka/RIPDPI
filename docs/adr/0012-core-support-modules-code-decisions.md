# ADR 0012: Core support modules code decisions

> Status: Accepted. Decision date: 2026-11-25.

## Context

These decisions for `core/diagnostics-data`, `core/pcap-export`, `core/detection`, and `baselineprofile` moved out of source comments. The code keeps the behavior. This file keeps the reason.

## Decisions

### core/diagnostics-data/build.gradle.kts#testOptions

Robolectric must merge the test assets, which are the exported Room schema JSON files. Then `MigrationTestHelper` can create databases at old schema versions.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DetectionResolverNetworkStack.kt#executeCancellable

An OkHttp failure is retried through the native curl bridge. When both transports fail, one diagnostic exception holds both errors. The cancellable path keeps OkHttp cancellation active through the bounded body read and does not use the native fallback. `resolveAddressesCancellable` cancels the calls that the resolver owns. A platform SYSTEM lookup can finish in the OS after cancellation.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DiagnosticsDatabaseMigrations.kt#DESTRUCTIVE_FROM_VERSIONS

Schema versions 1 to 3 (v0.0.8 and earlier) have no migration. They hold only disposable diagnostics history, so Room may drop and recreate the database from them. Never add a version to this list when a migration in `ALL` starts from that version.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DiagnosticsDatabaseMigrations.kt#migration4To5

Release v0.1.0 shipped schema 4. Release v0.1.1 relied on destructive fallback, so this step was missing for v0.1.0 upgrades. Both new columns are nullable INTEGER without a default, as in the exported schema 5.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DiagnosticsDatabaseModule.kt#provideDiagnosticsDatabase

Schema 4 and later migrate in place. Schemas 1 to 3 have no migration and are recreated, because their diagnostics data is disposable. `fallbackToDestructiveMigrationOnDowngrade` resets `requireMigration` to true, so the destructive-upgrade opt-in must come after it. Cleanup uses `diagnosticsHistoryRetentionDays`. Do not add a TTL when the database opens.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DiagnosticsHistoryStores.kt#getBlockedPathKeys

A block that was seen during a short outage must not lower a resolver on this network forever.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DiagnosticsHistoryStores.kt#DefaultDiagnosticsRetentionStore

Legacy terminal markers stay in `native_session_events` until the outbox store migrates them. Their JSON payload is opaque to SQL, so retention must fail closed. It must not delete a marker or related evidence. The policy delete runs under the policy lock, so a store update that read a row cannot write it back after the delete. A history clear takes the lock before the transaction, as the outbox does, so a policy update cannot undo the clear.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DiagnosticsRememberedPolicyDao.kt#DiagnosticsRememberedPolicyDao

A replayable policy that also blocks automatic probing must pass all of these rules. A probe validated it; older builds validated session rows by time and traffic. It is within the maximum age and not in the future, because a bad clock would keep it forever. It has a measured TCP or QUIC lane to replay. A suppressed policy stays out until a probe validates it again, and `suppressedUntil` is not read.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DiagnosticsScanDao.kt#DiagnosticsScanDao

A strategy test can be older than any row limit, so the stored report tells which scan was one. The query checks that the key exists and is not null, in compact or pretty JSON. The key cannot match inside a JSON string, because a quote is escaped there. The reader still decodes the report and decides. `ScanSessionProjection` projects `reportJson` through CASE and returns null for a payload above about 1.5 MB, because larger rows overflow the 2 MB Android CursorWindow. The limit is `DiagnosticsReportPersister.MaxInlineReportJsonBytes`.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DiagnosticsTerminalOutboxStore.kt#checkpointTerminalPolicy

The update reads the stored policy in the same transaction as the marker swap, so a concurrent store update is not overwritten. A missing row (the user deleted it) or a null update writes no policy. The marker is still replaced.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DirectDns.kt#DirectDns

The direct DNS reader ignores unrelated or invalid datagrams until the original request deadline. Cancellation stays terminal when the socket was already closed.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DnsMessages.kt#SizingOption

RFC 7830 section 6 forbids the Padding option (code 12) on cleartext DNS. Size probes use a local-use option code (65001, RFC 6891 section 9). Android `rawQuery` owns its transaction ID, but the caller still owns the expected question. DNS wire parsing belongs to dnsjava. Network selection belongs to the caller.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DnsRecordWire.kt#validateHttpsRdata

dnsjava does not check the RFC 9460 wire constraints, so this code enforces them. For a truncated (TC) message, dnsjava lists only complete records, so the code does not read the partial record. The OPT TTL field is an unsigned control word, so RFC 2181 TTL clamping does not apply to it.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/OwnedDohDns.kt#OwnedDohDns

This class owns the DoH HTTP calls, because the upstream DNS cache keeps queries running after a subscriber is cancelled.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/RememberedNetworkPolicyStore.kt#rememberedNetworkPolicyWriteLock

This lock serializes the read-modify-write operations on `remembered_network_policies`. The callers are the default store, the terminal outbox policy checkpoint, the history reset, and the retention delete. Callers hold entity snapshots from different coroutines, so each change reads the row again under the lock. A delete cannot fall between that read and the write. Lock order is this lock, then a Room transaction.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/RememberedNetworkPolicyStore.kt#mutateCurrent

The transform applies to the stored row, not to the caller snapshot, so concurrent updates are not lost. Without a stored row, nothing is written and the snapshot is returned. Only the remember calls create a policy, so a missing row was deleted by the user or a clear. Writing the snapshot would bring it back or REPLACE a newer row of the same network.

### core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/RememberedNetworkPolicyStore.kt#deletePolicy

The function returns the number of remembered policy rows that still use the same fingerprint hash in other modes. The value `0` means the network scope is forgotten, and the companion per-network learned state can be purged.

### core/pcap-export/src/main/kotlin/com/poyka/ripdpi/jni/PcapBridge.kt#PcapBridge

`jniPcapStart` returns a positive capture-set id on success and 0 on failure. The capture set is bound to the tunnel session handle, and `jniDestroy` retires it. `jniPcapStop` returns a typed JSON completion response. With `finishedOnly`, a pending response leaves a running writer untouched. A null response means the JNI call failed. `jniPcapListCaptures` returns finalized files and saved drop counts. The packet reader computes the packet count. A missing store gives an empty array. With `cleanupOnly`, the call applies retention and returns an empty array. A null response means the storage operation failed. `jniPcapRedactToFile` takes a detached descriptor to a private staging file. Rust closes it and returns the bytes written, -1 for an incomplete source, or 0 for another failure. Only `PcapController.prepareRedactedExport` may call it, so the `detachFd` ownership contract holds. The class is `internal` so UI and engine layers use `PcapController`.

### core/pcap-export/src/main/kotlin/com/poyka/ripdpi/pcap/PcapCaptureMetadata.kt#PcapCaptureMetadata

The JSON shape mirrors the Rust `PcapCaptureMetadata` struct with `camelCase` names. The Kotlin field names are explicit, so a future change of Kotlin naming does not break the contract. The stop result lists only files that native code flushed, synced, and renamed atomically. The failure value is a stable writer code and not a filesystem error, so it is safe in local diagnostics.

### core/pcap-export/src/main/kotlin/com/poyka/ripdpi/pcap/PcapCaptureRuntimeController.kt#PcapCaptureRuntimeController

The VPN runtime binds its live bridge and retires it before native teardown. The UI observes a native start as recording only after it succeeds. Diagnostic acquisitions own receipts until their run settles. Manual captures keep only the last visible outcome. The VPN runtime calls `bindTunnel` only after a native tunnel has started. `retireTunnel` finalizes the capture before the bridge is stopped or destroyed, so the writer result stays observable and native retirement does not discard it. A `StopFailed` state keeps the lease for a retry, but the bridge is about to be destroyed, so the failed stop is retired.

### core/pcap-export/src/main/kotlin/com/poyka/ripdpi/pcap/PcapController.kt#PcapController

There is one capture for each tunnel session, and native code enforces it. The session handle alone is enough to stop it. Redaction completes in private storage before the document picker opens. Only the staged output can be published to the document URI. A channel copy lets cancellation interrupt writes to a provider pipe. A missing JNI symbol never took ownership of the detached descriptor, so the caller closes it.

### core/pcap-export/src/main/kotlin/com/poyka/ripdpi/pcap/PcapExportStaging.kt#PcapExportStaging

Only a dead process releases its ownership lock. The lock file is separate from the draft, because JNI also opens and closes the draft.

### core/pcap-export/src/main/kotlin/com/poyka/ripdpi/pcap/PcapReader.kt#PcapReader

The reader type mirrors `ripdpi_pcap::PcapRecord`. It does not use the UI packet type, so `:core:pcap-export` stays UI-agnostic. The reader accepts only classic PCAP 2.4 little-endian raw IP captures with microsecond timestamps. The constructor consumes the 24-byte global header and validates it. A partial record throws `EOFException`.

### core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/DetectionCheckScheduler.kt#DetectionCheckScheduler

`collectLatest` cancels the check in flight when the state leaves Running, and a restart gets its own check. A handover check belongs to the Running session. A handover during the start check waits for it, so two checks never save results at once. An automatic check uses local checks only. It must not send the public IP to a third-party GeoIP or IP-echo service. The user runs those lookups from the Detection screen. The launcher activity reads the notification link, because `core:detection` cannot reference the activity class of the app.

### core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/BypassProxyProbes.kt#relayHostOrNull

An IPv6 literal goes as ATYP 4. A host name stays a domain, so the proxy resolves it. A wildcard relay address (0.0.0.0 or ::) means the address of the proxy itself (RFC 1928 section 6). For a domain target, the connected relay owns DNS, so its numeric origin must be usable.

### core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/DetectionHostResolver.kt#DetectionHostResolver

`getaddrinfo` cannot be interrupted. The resolver keeps its lease until the call returns and detaches the caller. A cancelled queued lookup is dropped. An active blocking lookup keeps its slot until its `finally` block runs. `StunProbeIo` uses the same rule: a cancelled caller must not free admission while its blocking platform lookup still runs.

### core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/DirectSignsChecker.kt#defaultNetworkCapabilities

Android can rethrow Binder failures as `RuntimeException`, so the code catches the generic exception. `VpnInventoryBudget.readPackageManager` follows the same rule.

### core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/IndirectDumpsysSignals.kt#reportedPackages

VPN management can report a prepared package. A service record also does not prove a live tunnel.

### core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/LocationSignalsChecker.kt#LocationSignalsChecker

A Finding string never holds the raw BSSID. It holds only a presence flag. The raw value is forbidden in any persisted or serialized artifact (see `.claude/rules/network-fingerprint-privacy.md`).

### core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/ProcNetTcpProxyScanner.kt#ProcNetTcpProxyScanner

Linux prints each native-order 32-bit word separately, also in tcp6. The decoder reads the words in native byte order.

### core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/StunBindingResponseDecoder.kt#StunBindingResponseDecoder

The decoder follows RFC 8489 sections 14.1 and 14.2 and validates the complete message before it returns an address. Legacy MAPPED-ADDRESS is an extraction-only compatibility fallback. A valid Binding response still requires XOR-MAPPED-ADDRESS. The known attributes that carry authentication data do not authenticate anything in this unauthenticated probe.

### core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/SystemPingProber.kt#parseRttMs

The summary average (iputils and toybox) wins. Without it, the mean of all replies is used, so a slow first reply (radio wake-up) does not stand for the whole sample. iputils prints `mdev` and toybox does not, so `parseJitterMs` derives it from the replies as a population deviation. A failure to close a stream must not replace cancellation or the probe outcome.

### core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/community/CommunityStatsRepository.kt#CommunityStatsRepository

The app has no built-in endpoint. Without a user-configured URL, the comparison stays local.

### core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/consensus/IpConsensusBuilder.kt#resolveConcurrently

The GeoIP check already fetched the ASN data of its IP, so the providers are not queried again. All IPs resolve at once, and an IP that does not resolve within `AsnResolutionBudgetMs` gets no ASN data. The budget equals the per-request GeoIP timeout, so one slow provider round does not cut a normal lookup.

### core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/dpi/DpiErrorClassifier.kt#DpiErrorClassifier

Android wraps ENETUNREACH, ECONNRESET, and ECONNREFUSED alike in `ConnectException`, so the errno text decides first and the exception class is only the fallback. A TLS alert that names a certificate the server rejected (`bad_certificate`, `certificate_required`) is the answer of the server and not an interception.

### core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/privacy/DetectionReportPrivacy.kt#DetectionReportPrivacy

Narrative rows are rebuilt from the private view, because the cached rows can contain app identities.

### core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/probe/ProxyScanner.kt#ProxyScanner

The workers can finish before `select` delivers a match that they already published, so the scanner checks the published result.

### core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/probe/ReflectedIpAddress.kt#publicIpOrNull

Local, multicast, and tunnel-only addresses are rejected before they become public-IP evidence. IP evidence from the default path does not attest an underlay route.

### baselineprofile/build.gradle.kts#baselineProfile

The benchmark variant matches the release-derived, profileable variant of `:app`. The existing debug lane stays for profile generation and startup diagnostics.

### baselineprofile/src/main/kotlin/com/poyka/ripdpi/baselineprofile/BaselineProfileGenerator.kt#BaselineProfileGenerator

The first launch shows onboarding, and later iterations start on Home. A fling can still be settling when the swipe returns, and a click on a moving list misses, so the generator waits for the list to settle.

### baselineprofile/src/benchmark/kotlin/com/poyka/ripdpi/baselineprofile/ResponsivenessBenchmark.kt#ResponsivenessBenchmark

Accessibility text input does not open the keyboard. The text field also contains the query, so the benchmark asserts the identity of the actual result row. After Compose replaces accessibility nodes, the benchmark retries the complete snapshot.

### baselineprofile/src/benchmark/kotlin/com/poyka/ripdpi/performance/ComposeStylesBenchmark.kt#ComposeStylesBenchmark

The same workloads run before the dependency changes, after them, and after the Styles migration. The harness stays identical in all three APKs. Frame tests use real screen composables with fixed local data. They do not measure product navigation, storage, network, or service behavior. Production Application initialization runs outside the frame measurement blocks.

### baselineprofile/src/main/kotlin/com/poyka/ripdpi/baselineprofile/StartupBenchmark.kt#StartupBenchmark

Perfetto can lose the launch marker for one iteration.
