package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.GeositeCatalog
import com.poyka.ripdpi.proto.GeositeDomain
import com.poyka.ripdpi.proto.GeositeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostPackCatalogTest {
    @Test
    fun `catalog json parses host packs and sources`() {
        val catalog =
            hostPackCatalogFromJson(
                """
                {
                  "version": 1,
                  "generatedAt": "2026-03-12T00:00:00Z",
                  "packs": [
                    {
                      "id": "youtube",
                      "title": "YouTube",
                      "description": "Video hosts",
                      "hostCount": 2,
                      "hosts": ["youtube.com", "ytimg.com"],
                      "sources": [
                        {
                          "name": "runetfreedom/russia-blocked-geosite",
                          "url": "https://example.test/youtube.txt",
                          "ref": "release",
                          "commit": "abc123"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(1, catalog.version)
        assertEquals("2026-03-12T00:00:00Z", catalog.generatedAt)
        assertEquals(1, catalog.packs.size)
        assertEquals("youtube", catalog.packs.single().id)
        assertEquals(
            "abc123",
            catalog.packs.single().sources.single().commit,
        )
    }

    @Test
    fun `normalizes host tokens using RIPDPI-compatible rules`() {
        assertEquals("example.com", normalizeHostSpecToken("Example.COM"))
        assertEquals("cdn/video-1", normalizeHostSpecToken("CDN/VIDEO-1"))
        assertNull(normalizeHostSpecToken("bad^host"))
    }

    @Test
    fun `extract normalized host tokens skips invalid entries`() {
        val tokens = extractNormalizedHostTokens("Example.COM bad^host api-1.test")

        assertEquals(listOf("example.com", "api-1.test"), tokens)
    }

    @Test
    fun `format host pack hosts normalizes and deduplicates`() {
        val formatted =
            formatHostPackHosts(
                listOf("Example.COM", "example.com", "ytimg.com", "bad^host"),
            )

        assertEquals("example.com\nytimg.com", formatted)
    }

    @Test
    fun `merge keeps existing raw text and appends only missing hosts`() {
        val merged =
            mergeHostPackHosts(
                existingText = "Example.COM\ncustom.example\n",
                presetHosts = listOf("example.com", "ytimg.com", "custom.example"),
            )

        assertEquals("Example.COM\ncustom.example\nytimg.com", merged)
    }

    @Test
    fun `replace updates only blacklist target`() {
        val result =
            applyCuratedHostPack(
                currentBlacklist = "old.example",
                currentWhitelist = "allowed.example",
                presetHosts = listOf("YouTube.com", "ytimg.com"),
                targetMode = HostPackTargetBlacklist,
                applyMode = HostPackApplyModeReplace,
            )

        assertEquals(HostPackTargetBlacklist, result.hostsMode)
        assertEquals("youtube.com\nytimg.com", result.hostsBlacklist)
        assertEquals("allowed.example", result.hostsWhitelist)
    }

    @Test
    fun `merge updates only whitelist target`() {
        val result =
            applyCuratedHostPack(
                currentBlacklist = "blocked.example",
                currentWhitelist = "telegram.org",
                presetHosts = listOf("telegram.org", "t.me"),
                targetMode = HostPackTargetWhitelist,
                applyMode = HostPackApplyModeMerge,
            )

        assertEquals(HostPackTargetWhitelist, result.hostsMode)
        assertEquals("blocked.example", result.hostsBlacklist)
        assertEquals("telegram.org\nt.me", result.hostsWhitelist)
    }

    @Test
    fun `snapshot json preserves fetch metadata`() {
        val snapshot =
            hostPackCatalogSnapshotFromJson(
                """
                {
                  "catalog": {
                    "version": 1,
                    "generatedAt": "2026-03-12T00:00:00Z",
                    "packs": []
                  },
                  "source": "downloaded",
                  "lastFetchedAtEpochMillis": 1741765600000,
                  "verifiedChecksumSha256": "96b19c3ec2011e4e5ec87dd54b3c209f1e0efaa36fe8b5dd275129b032a01438"
                }
                """.trimIndent(),
            )

        assertEquals(HostPackCatalogSourceDownloaded, snapshot.source)
        assertEquals(1741765600000, snapshot.lastFetchedAtEpochMillis)
        assertEquals(
            "96b19c3ec2011e4e5ec87dd54b3c209f1e0efaa36fe8b5dd275129b032a01438",
            snapshot.verifiedChecksumSha256,
        )
    }

    @Test
    fun `curated geosite extraction keeps root and full domains only`() {
        val source =
            HostPackSource(
                name = HostPackCatalogRemoteSourceName,
                url = HostPackCatalogRemoteSourceUrl,
                ref = HostPackCatalogRemoteSourceRef,
            )

        val catalog =
            curatedHostPackCatalogFromGeosite(
                geositeCatalog =
                    GeositeCatalog
                        .newBuilder()
                        .addEntry(
                            GeositeEntry
                                .newBuilder()
                                .setCountryCode("youtube")
                                .addDomain(
                                    GeositeDomain
                                        .newBuilder()
                                        .setType(GeositeDomain.Type.ROOT_DOMAIN)
                                        .setValue("YouTube.com")
                                        .build(),
                                ).addDomain(
                                    GeositeDomain
                                        .newBuilder()
                                        .setType(GeositeDomain.Type.FULL)
                                        .setValue("ytimg.com")
                                        .build(),
                                ).addDomain(
                                    GeositeDomain
                                        .newBuilder()
                                        .setType(GeositeDomain.Type.PLAIN)
                                        .setValue("keyword-youtube")
                                        .build(),
                                ).build(),
                        ).addEntry(
                            GeositeEntry
                                .newBuilder()
                                .setCountryCode("telegram")
                                .addDomain(
                                    GeositeDomain
                                        .newBuilder()
                                        .setType(GeositeDomain.Type.ROOT_DOMAIN)
                                        .setValue("Telegram.org")
                                        .build(),
                                ).addDomain(
                                    GeositeDomain
                                        .newBuilder()
                                        .setType(GeositeDomain.Type.REGEX)
                                        .setValue(".*telegram.*")
                                        .build(),
                                ).build(),
                        ).addEntry(
                            GeositeEntry
                                .newBuilder()
                                .setCountryCode("discord")
                                .addDomain(
                                    GeositeDomain
                                        .newBuilder()
                                        .setType(GeositeDomain.Type.FULL)
                                        .setValue("discord.gg")
                                        .build(),
                                ).addDomain(
                                    GeositeDomain
                                        .newBuilder()
                                        .setType(GeositeDomain.Type.ROOT_DOMAIN)
                                        .setValue("Discord.com")
                                        .build(),
                                ).addDomain(
                                    GeositeDomain
                                        .newBuilder()
                                        .setType(GeositeDomain.Type.FULL)
                                        .setValue("discord.gg")
                                        .build(),
                                ).build(),
                        ).build(),
                generatedAt = "2026-03-12T00:00:00Z",
                source = source,
            )

        assertEquals(listOf("youtube.com", "ytimg.com"), catalog.packs[0].hosts)
        assertEquals(listOf("telegram.org"), catalog.packs[1].hosts)
        assertEquals(listOf("discord.gg", "discord.com"), catalog.packs[2].hosts)
        assertEquals(
            HostPackCatalogRemoteSourceUrl,
            catalog.packs[0].sources.single().url,
        )
    }
}
