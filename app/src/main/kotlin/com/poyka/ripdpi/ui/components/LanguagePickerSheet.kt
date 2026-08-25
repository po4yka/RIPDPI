package com.poyka.ripdpi.ui.components

import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.ui.semantics.Role
import androidx.core.os.LocaleListCompat
import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.LocalesConfig
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tags = remember(context) { LocalesConfig.parse(context) }
    // Resolve the language that is actually in effect. getApplicationLocales() is
    // empty until the user sets a per-app override, so without this fallback the
    // currently-active (system) language would show no selection at all.
    val currentTag =
        remember(tags) {
            val applied = AppCompatDelegate.getApplicationLocales()
            val effective = if (applied.isEmpty) LocaleListCompat.getAdjustedDefault() else applied
            val locale = effective.get(0)
            tags.firstOrNull { tag ->
                val offered = Locale.forLanguageTag(tag)
                offered.language == locale?.language &&
                    (offered.country.isEmpty() || offered.country.equals(locale.country, ignoreCase = true))
            } ?: tags.firstOrNull { it.equals("en", ignoreCase = true) }
        }

    RipDpiBottomSheet(
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.language_picker_sheet_title),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            tags.forEach { tag ->
                LanguageRow(
                    tag = tag,
                    selected = tag == currentTag,
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
    val spacing = RipDpiThemeTokens.spacing
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiSelectable(selected = selected, role = Role.RadioButton, onClick = onSelected)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text =
                stringResource(
                    R.string.language_picker_native_format,
                    stringResource(nameRes),
                ),
            modifier = Modifier.padding(start = spacing.md),
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

        // Android resource keys forbid hyphens; BCP-47 pt-BR maps to language_name_pt_br.
        "pt-BR" -> R.string.language_name_pt_br

        "hi" -> R.string.language_name_hi

        else -> R.string.language_name_en
    }
