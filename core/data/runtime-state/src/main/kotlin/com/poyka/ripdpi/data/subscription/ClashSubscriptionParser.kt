package com.poyka.ripdpi.data.subscription

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.yamlMap
import com.poyka.ripdpi.data.ProxyProfile
import java.util.UUID

/**
 * A node entry in a Clash `proxies:` array that could not be mapped to a
 * [ProxyProfile]. Carries the failing node index so the caller can surface a
 * precise, non-fatal diagnostic instead of a fatal stack trace.
 */
data class SubscriptionParseError(
    val nodeIndex: Int,
    val reason: String,
)

/** Outcome of a [ClashSubscriptionParser] run: mapped profiles plus per-node errors. */
data class ClashSubscriptionResult(
    val profiles: List<ProxyProfile>,
    val errors: List<SubscriptionParseError>,
)

/**
 * Parses the `proxies:` array of a Clash / Clash.Meta YAML subscription into
 * [ProxyProfile] records.
 *
 * Detection mirrors NekoBox's `RawUpdater.parseRaw()`: a payload is treated as
 * Clash YAML when it carries a top-level `proxies:` key. Each entry is
 * dispatched on its `type:` field; `ss`, `vmess`, `vless`, `trojan`,
 * `hysteria2` map to first-class [ProxyProfile] subtypes, every other type
 * (`socks5`, `http`, `hysteria`, `anytls`, `tuic`, …) round-trips as
 * [ProxyProfile.RawConfig]. Unknown keys on a node are ignored. Routing
 * sections (`rules:`, `proxy-groups:`) are not consulted.
 */
object ClashSubscriptionParser {
    private val yaml = Yaml.default

    /** True when [payload] carries a top-level `proxies:` key (the Clash marker). */
    fun looksLikeClash(payload: String): Boolean {
        val node = runCatching { yaml.parseToYamlNode(payload) }.getOrNull() ?: return false
        val map = node as? YamlMap ?: return false
        return map.entries.keys.any { it.content == "proxies" }
    }

    /**
     * Parses [payload] into a [ClashSubscriptionResult]. Every produced
     * [ProxyProfile] is stamped with [groupId]. Never throws: a non-Clash or
     * malformed payload comes back as an empty result with a populated
     * [ClashSubscriptionResult.errors] list.
     */
    fun parse(
        payload: String,
        groupId: String,
    ): ClashSubscriptionResult {
        val root =
            runCatching { yaml.parseToYamlNode(payload) }.getOrNull()
                ?: return failure("payload is not valid YAML")
        val rootMap = root as? YamlMap ?: return failure("payload is not a Clash document")
        if (rootMap.entries.keys.none { it.content == "proxies" }) {
            return failure("missing top-level 'proxies' key")
        }
        val proxies =
            runCatching { rootMap.get<YamlList>("proxies") }.getOrNull()
                ?: return failure("'proxies' is not a list")

        val profiles = mutableListOf<ProxyProfile>()
        val errors = mutableListOf<SubscriptionParseError>()
        proxies.items.forEachIndexed { index, item ->
            val nodeMap = item as? YamlMap
            if (nodeMap == null) {
                errors += SubscriptionParseError(nodeIndex = index, reason = "node is not a mapping")
                return@forEachIndexed
            }
            val result = runCatching { mapNode(nodeMap, index, groupId) }
            val profile = result.getOrNull()
            if (profile != null) {
                profiles += profile
            } else {
                errors +=
                    SubscriptionParseError(
                        nodeIndex = index,
                        reason = result.exceptionOrNull()?.message ?: "node could not be mapped",
                    )
            }
        }
        return ClashSubscriptionResult(profiles = profiles, errors = errors)
    }

    private fun failure(reason: String): ClashSubscriptionResult =
        ClashSubscriptionResult(
            profiles = emptyList(),
            errors = listOf(SubscriptionParseError(nodeIndex = -1, reason = reason)),
        )

    private fun mapNode(
        node: YamlMap,
        index: Int,
        groupId: String,
    ): ProxyProfile {
        val type = node.string("type") ?: error("node $index missing 'type'")
        val name = node.string("name") ?: node.string("server") ?: "node-$index"
        return when (type.lowercase()) {
            "ss", "shadowsocks" -> {
                ProxyProfile.Shadowsocks(
                    id = newId(),
                    displayName = name,
                    groupId = groupId,
                    server = node.requireString("server", index),
                    serverPort = node.requirePort("port", index),
                    method =
                        node.string("cipher") ?: node.string("method")
                            ?: error("node $index ss missing 'cipher'"),
                    password = node.requireString("password", index),
                )
            }

            "vmess", "vless" -> {
                ProxyProfile.Vless(
                    id = newId(),
                    displayName = name,
                    groupId = groupId,
                    server = node.requireString("server", index),
                    serverPort = node.requirePort("port", index),
                    uuid = node.requireString("uuid", index),
                )
            }

            "trojan" -> {
                ProxyProfile.Trojan(
                    id = newId(),
                    displayName = name,
                    groupId = groupId,
                    server = node.requireString("server", index),
                    serverPort = node.requirePort("port", index),
                    password = node.requireString("password", index),
                )
            }

            "hysteria2", "hy2" -> {
                ProxyProfile.Hysteria2(
                    id = newId(),
                    displayName = name,
                    groupId = groupId,
                    server = node.requireString("server", index),
                    serverPort = node.requirePort("port", index),
                    password = node.requireString("password", index),
                )
            }

            else -> {
                // socks5, http, hysteria (v1), anytls, tuic, … — no first-class
                // subtype, so round-trip the node as an opaque raw config.
                ProxyProfile.RawConfig(
                    id = newId(),
                    displayName = name,
                    groupId = groupId,
                    config = yaml.encodeToString(YamlNode.serializer(), node),
                )
            }
        }
    }

    private fun YamlMap.string(key: String): String? =
        runCatching { getScalar(key)?.content }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun YamlMap.requireString(
        key: String,
        index: Int,
    ): String = string(key) ?: error("node $index missing '$key'")

    private fun YamlMap.requirePort(
        key: String,
        index: Int,
    ): Int =
        string(key)?.toIntOrNull()?.takeIf { it > 0 }
            ?: error("node $index missing or invalid '$key'")

    private fun newId(): String = UUID.randomUUID().toString()
}
