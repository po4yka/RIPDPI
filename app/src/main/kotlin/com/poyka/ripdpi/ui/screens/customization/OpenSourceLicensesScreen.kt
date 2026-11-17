package com.poyka.ripdpi.ui.screens.customization

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mikepenz.aboutlibraries.Libs
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiScreenBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LicenseNotice(
    val id: String,
    val title: String,
    val subtitle: String,
    val text: String,
)

// Packaged by :core:engine's buildNfqws2 task from native/zapret2/licenses.
private const val NativeNoticeAssetDir = "zapret2/licenses"

internal fun loadLicenseNotices(context: Context): List<LicenseNotice> {
    val json = context.resources.openRawResource(R.raw.aboutlibraries).use { it.readBytes().decodeToString() }
    val libraries =
        Libs.Builder().withJson(json).build().libraries.map { library ->
            val licenseNames = library.licenses.joinToString { it.name }
            val developers = library.developers.mapNotNull { it.name }.joinToString()
            // Terms-only licenses (for example the Android SDK license) carry a URL and no text.
            val licenseTexts = library.licenses.map { it.licenseContent?.ifBlank { null } ?: it.url.orEmpty() }
            LicenseNotice(
                id = library.uniqueId,
                title = library.name,
                subtitle = listOfNotNull(library.artifactVersion, licenseNames).joinToString(" · "),
                text =
                    (listOfNotNull(library.description, developers, library.website) + licenseTexts)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n"),
            )
        }
    val assets = context.assets
    val nativeNotices =
        assets.list(NativeNoticeAssetDir).orEmpty().map { fileName ->
            LicenseNotice(
                id = "$NativeNoticeAssetDir/$fileName",
                title = fileName.substringBeforeLast('.'),
                subtitle = NativeNoticeAssetDir,
                text = assets.open("$NativeNoticeAssetDir/$fileName").use { it.readBytes().decodeToString() },
            )
        }
    return (libraries + nativeNotices).sortedBy { it.title.lowercase() }
}

@Composable
fun OpenSourceLicensesRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val notices by produceState(emptyList<LicenseNotice>(), context) {
        value = withContext(Dispatchers.IO) { loadLicenseNotices(context) }
    }
    OpenSourceLicensesScreen(notices = notices, onBack = onBack, modifier = modifier)
}

@Composable
internal fun OpenSourceLicensesScreen(
    notices: List<LicenseNotice>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.OpenSourceLicenses))
                .fillMaxSize()
                .ripDpiScreenBackground(),
        title = stringResource(R.string.open_source_licenses),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
    ) {
        items(notices, key = { it.id }) { notice ->
            var expanded by rememberSaveable { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
                SettingsRow(
                    title = notice.title,
                    subtitle = notice.subtitle,
                    onClick = { expanded = !expanded },
                )
                if (expanded) {
                    Text(text = notice.text, style = type.caption, color = colors.mutedForeground)
                }
            }
        }
    }
}
