package com.poyka.ripdpi.ui.components.profiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

@Stable
internal class ProfileSearchState(
    query: String = "",
    filter: String? = null,
) {
    var query by mutableStateOf(query)
    var filter by mutableStateOf(filter)

    fun reset() {
        query = ""
        filter = null
    }

    companion object {
        val Saver =
            listSaver<ProfileSearchState, String>(
                save = { listOf(it.query, if (it.filter == null) "0" else "1", it.filter.orEmpty()) },
                restore = { ProfileSearchState(it[0], it[2].takeIf { _ -> it[1] == "1" }) },
            )
    }
}

@Composable
internal fun rememberProfileSearchState(): ProfileSearchState =
    rememberSaveable(saver = ProfileSearchState.Saver) {
        ProfileSearchState()
    }

internal fun matchesProfileQuery(
    query: String,
    visibleFields: List<String>,
): Boolean =
    query.trim().split(Regex("\\s+")).filter(String::isNotEmpty).all { term ->
        visibleFields.any { it.contains(term, ignoreCase = true) }
    }
