package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.serialization.RipDpiLenientJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Resolves bounded selector graphs without depending on outbound declaration order. */
internal class SelectorTopology(
    payload: String,
    outbounds: List<JsonElement>,
) {
    private val entries = outbounds.filterIsInstance<JsonObject>()
    private val byTag = entries.mapNotNull { entry -> entry.text("tag")?.let { it to entry } }.toMap()
    private val selectors = entries.filter { it.kind() == "selector" }
    private val finalTag =
        ((RipDpiLenientJson.parseToJsonElement(payload) as? JsonObject)?.get("route") as? JsonObject)?.text("final")

    init {
        require(entries.mapNotNull { it.text("tag") }.size == byTag.size) { "duplicate outbound tags" }
    }

    private val leafCache = mutableMapOf<String, List<String>>()
    private val selectedLeafCache = mutableMapOf<String, List<String>>()

    val selector: JsonObject? = selectRoot()
    val memberTags: List<String> = selector?.let { leaves(it, emptySet()) }.orEmpty().distinct()
    val defaultTags: List<String> = selector?.let { selectedLeaves(it, emptySet()) }.orEmpty()
    private val reachableGroups = linkedSetOf<String>()

    fun urltest(): JsonObject? {
        selector?.let { collectGroups(it, emptySet()) }
        val probes = reachableGroups.mapNotNull(byTag::get).filter { it.kind() == "urltest" }
        require(probes.size <= 1) { "multiple urltest policies are not supported" }
        return probes.singleOrNull()
    }

    fun selectsOnlyDirect(): Boolean {
        val directTags = entries.filter { it.kind() == "direct" }.mapNotNull { it.text("tag") }.toSet()
        return memberTags.isNotEmpty() && memberTags.all { it in directTags } &&
            defaultTags.isNotEmpty() && defaultTags.all { it in directTags } &&
            entries.all { it.kind() in setOf("selector", "direct", "block", "dns") }
    }

    private fun selectRoot(): JsonObject? =
        when {
            selectors.isEmpty() -> {
                null
            }

            finalTag != null -> {
                requireNotNull(selectors.singleOrNull { it.text("tag") == finalTag }) {
                    "route final does not name a selector"
                }
            }

            else -> {
                val referenced = selectors.flatMap { it.members() }.toSet()
                requireNotNull(selectors.singleOrNull { it.text("tag") !in referenced }) {
                    "selector root is ambiguous or cyclic"
                }
            }
        }

    private fun leaves(
        entry: JsonObject,
        path: Set<String>,
    ): List<String> {
        val tag = requireNotNull(entry.text("tag")) { "selector tag is missing" }
        require(tag !in path && path.size < MAX_DEPTH) { "selector cycle or depth limit" }
        return leafCache.getOrPut(tag) { entry.members().flatMap { leavesForTag(it, path + tag) }.distinct() }
    }

    private fun leavesForTag(
        tag: String,
        path: Set<String>,
    ): List<String> {
        val entry = byTag[tag]
        return if (entry?.kind() in GROUP_TYPES) leaves(requireNotNull(entry), path) else listOf(tag)
    }

    private fun selectedLeaves(
        entry: JsonObject,
        path: Set<String>,
    ): List<String> {
        val tag = requireNotNull(entry.text("tag"))
        require(tag !in path && path.size < MAX_DEPTH) { "selector cycle or depth limit" }
        return selectedLeafCache.getOrPut(tag) {
            val members = entry.members()
            require(members.isNotEmpty()) { "selected group has no members" }
            val selected =
                if (entry.kind() == "urltest") {
                    members
                } else {
                    listOf(entry.text("default") ?: members.first())
                }
            require(selected.all { it in members }) { "selector default is not a member" }
            selected
                .flatMap { member ->
                    val child = byTag[member]
                    if (child != null && child.kind() in GROUP_TYPES) {
                        selectedLeaves(child, path + tag)
                    } else {
                        listOf(member)
                    }
                }.distinct()
        }
    }

    private fun collectGroups(
        entry: JsonObject,
        path: Set<String>,
    ) {
        val tag = requireNotNull(entry.text("tag"))
        require(tag !in path && path.size < MAX_DEPTH) { "selector cycle or depth limit" }
        if (!reachableGroups.add(tag)) return
        entry.members().mapNotNull(byTag::get).filter { it.kind() in GROUP_TYPES }.forEach {
            collectGroups(it, path + tag)
        }
    }

    private fun JsonObject.members(): List<String> =
        (this["outbounds"] as? JsonArray)
            ?.map { value ->
                requireNotNull((value as? JsonPrimitive)?.contentOrNull) { "invalid selector member" }
            }.orEmpty()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.kind(): String? = text("type")?.lowercase()

    private companion object {
        const val MAX_DEPTH = 32
        val GROUP_TYPES = setOf("selector", "urltest")
    }
}
