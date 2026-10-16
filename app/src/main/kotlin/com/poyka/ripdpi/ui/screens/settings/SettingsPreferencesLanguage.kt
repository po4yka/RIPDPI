package com.poyka.ripdpi.ui.screens.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.LanguagePickerSheet
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

@Composable
internal fun SettingsLanguagePreference() {
    var showLanguagePicker by remember { mutableStateOf(false) }
    SettingsRow(
        title = stringResource(R.string.settings_language_title),
        subtitle = stringResource(R.string.settings_language_body),
        value = selectedLanguageValue(),
        onClick = { showLanguagePicker = true },
        showChevron = true,
        testTag = RipDpiTestTags.SettingsLanguage,
    )
    if (showLanguagePicker) {
        LanguagePickerSheet(onDismissRequest = { showLanguagePicker = false })
    }
}

@Composable
private fun selectedLanguageValue(): String {
    val locale =
        AppCompatDelegate.getApplicationLocales().get(0)
            ?: return stringResource(R.string.language_picker_system_language)
    val nameResource =
        when (locale.language) {
            "en" -> R.string.language_name_en
            "ru" -> R.string.language_name_ru
            "es" -> R.string.language_name_es
            "de" -> R.string.language_name_de
            "fr" -> R.string.language_name_fr
            "fa" -> R.string.language_name_fa
            "ar" -> R.string.language_name_ar
            "hi" -> R.string.language_name_hi
            "zh" -> R.string.language_name_zh_cn.takeIf { locale.country.isEmpty() || locale.country == "CN" }
            "pt" -> R.string.language_name_pt_br.takeIf { locale.country == "BR" }
            else -> null
        }
    return nameResource?.let { stringResource(it) } ?: locale.getDisplayName(locale)
}
