package com.poyka.ripdpi.ui.components

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.LocalesConfig
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tags = remember(context) { LocalesConfig.parse(context) }
    val current = remember { AppCompatDelegate.getApplicationLocales().toLanguageTags() }

    RipDpiBottomSheet(
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.language_picker_sheet_title),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            tags.forEach { tag ->
                LanguageRow(
                    tag = tag,
                    selected = tag.equals(current, ignoreCase = true),
                    onSelected = {
                        AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.forLanguageTags(tag),
                        )
                        onDismissRequest()
                    },
                )
            }
        }
    }
}

@Composable
private fun LanguageRow(
    tag: String,
    selected: Boolean,
    onSelected: () -> Unit,
) {
    val nameRes = languageNameResource(tag)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelected)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelected)
        Text(
            text =
                stringResource(
                    R.string.language_picker_native_format,
                    stringResource(nameRes),
                ),
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

private fun languageNameResource(tag: String): Int =
    when (tag) {
        "en" -> R.string.language_name_en

        "ru" -> R.string.language_name_ru

        "es" -> R.string.language_name_es

        "de" -> R.string.language_name_de

        "fr" -> R.string.language_name_fr

        "fa" -> R.string.language_name_fa

        "ar" -> R.string.language_name_ar

        // Android resource keys forbid hyphens; BCP-47 zh-CN maps to language_name_zh_cn.
        "zh-CN" -> R.string.language_name_zh_cn

        else -> R.string.language_name_en
    }
