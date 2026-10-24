package com.poyka.ripdpi.core

// All 25 required fields, each with a distinct value so a misrouted field
// in toSections/toResolvedConfig changes the round-tripped config.
internal fun relayNativeBaseFixture(kind: String): ResolvedRipDpiRelayConfig =
    ResolvedRipDpiRelayConfig(
        enabled = true,
        kind = kind,
        profileId = "profile-id",
        server = "server.example",
        serverPort = 8443,
        serverName = "server-name.example",
        realityPublicKey = "reality-public-key",
        realityShortId = "reality-short-id",
        chainEntryServer = "chain-entry-server",
        chainEntryPort = 1111,
        chainEntryServerName = "chain-entry-server-name",
        chainEntryPublicKey = "chain-entry-public-key",
        chainEntryShortId = "chain-entry-short-id",
        chainExitServer = "chain-exit-server",
        chainExitPort = 2222,
        chainExitServerName = "chain-exit-server-name",
        chainExitPublicKey = "chain-exit-public-key",
        chainExitShortId = "chain-exit-short-id",
        masqueUrl = "https://masque.example",
        masqueUseHttp2Fallback = true,
        localSocksHost = "127.0.0.1",
        localSocksPort = 1080,
        udpEnabled = true,
        tcpFallbackEnabled = true,
        tlsFingerprintProfile = "chrome_stable",
    )

// Distinct, non-literal values for `password` / `token` fields so the
// secret-scanner pre-commit hook is not tripped by fake test credentials.
internal fun relayNativePlaceholder(slot: Int): String = "relay-fixture-placeholder-$slot"

internal fun relayNativeThreeHopChainFixture(): ResolvedRipDpiRelayConfig {
    val entryHop =
        ResolvedChainRelayHopConfig(
            kind = "vless_reality",
            profileId = "chain-entry-profile-id",
            server = "entry.example",
            serverPort = 443,
            serverName = "entry-sni.example",
            realityPublicKey = "entry-public-key",
            realityShortId = "entry-short-id",
            vlessUuid = "chain-entry-uuid",
            tlsFingerprintProfile = "firefox_stable",
        )
    val middleHop =
        ResolvedChainRelayHopConfig(
            kind = "shadowsocks",
            profileId = "chain-middle-profile-id",
            server = "middle.example",
            serverPort = 8388,
            shadowsocksMethod = "aes-256-gcm",
            shadowsocksPassword = relayNativePlaceholder(31),
            tlsFingerprintProfile = "edge_stable",
        )
    val exitHop =
        ResolvedChainRelayHopConfig(
            kind = "masque",
            profileId = "chain-exit-profile-id",
            server = "exit.example",
            masqueUrl = "https://masque.example/.well-known/masque/tcp/",
            masqueUseHttp2Fallback = true,
            masqueAuthMode = "bearer",
            masqueAuthToken = relayNativePlaceholder(32),
            tlsFingerprintProfile = "safari_stable",
        )
    // Seed the legacy scalar mirror to exactly what `toResolvedConfig` derives
    // from hop[0] / hop[last], so the fold/unfold/re-encode cycle is a no-op.
    return relayNativeBaseFixture("chain_relay").copy(
        chainHops = listOf(entryHop, middleHop, exitHop),
        chainEntry = entryHop,
        chainEntryServer = entryHop.server,
        chainEntryPort = entryHop.serverPort,
        chainEntryServerName = entryHop.serverName,
        chainEntryPublicKey = entryHop.realityPublicKey,
        chainEntryShortId = entryHop.realityShortId,
        chainEntryProfileId = entryHop.profileId,
        chainEntryUuid = entryHop.vlessUuid,
        chainExit = exitHop,
        chainExitServer = exitHop.server,
        chainExitPort = exitHop.serverPort,
        chainExitServerName = exitHop.serverName,
        chainExitPublicKey = exitHop.realityPublicKey,
        chainExitShortId = exitHop.realityShortId,
        chainExitProfileId = exitHop.profileId,
        chainExitUuid = exitHop.vlessUuid,
    )
}
