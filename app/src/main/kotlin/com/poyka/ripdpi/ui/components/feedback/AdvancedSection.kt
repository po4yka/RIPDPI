package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R

@Composable
fun AdvancedSection(
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    testTag: String? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    RipDpiAccordion(
        title = stringResource(R.string.persona_advanced),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
        headerTestTag = testTag,
        body = content,
    )
}
