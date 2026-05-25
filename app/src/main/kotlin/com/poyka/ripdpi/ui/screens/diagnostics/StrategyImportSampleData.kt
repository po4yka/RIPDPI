package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.theme.RipDpiIcons

/**
 * Spec-card sample data for [StrategyImportScreen]. Lives in its own file
 * to keep the screen file's keyword surface scoped to the presentation
 * layer — the architecture-delta gate flags multi-feature keyword spread
 * within a single screen file.
 */
fun sampleStrategyImportState(): StrategyImportState =
    StrategyImportState(
        sources =
            listOf(
                ImportSourceOption(
                    icon = RipDpiIcons.Archive,
                    titleRes = R.string.strategy_import_source_file_title,
                    subtitleRes = R.string.strategy_import_source_file_sub,
                    onSelect = {},
                ),
                ImportSourceOption(
                    icon = RipDpiIcons.QrCodeScanner,
                    titleRes = R.string.strategy_import_source_qr_title,
                    subtitleRes = R.string.strategy_import_source_qr_sub,
                    onSelect = {},
                ),
                ImportSourceOption(
                    icon = RipDpiIcons.Public,
                    titleRes = R.string.strategy_import_source_url_title,
                    subtitleRes = R.string.strategy_import_source_url_sub,
                    onSelect = {},
                ),
                ImportSourceOption(
                    icon = RipDpiIcons.Copy,
                    titleRes = R.string.strategy_import_source_clipboard_title,
                    subtitleRes = R.string.strategy_import_source_clipboard_sub,
                    onSelect = {},
                ),
            ),
        recentImports =
            listOf(
                RecentImportEntry(name = "tlsrec_split_host_aggressive.cfg", `when` = "14:08"),
                RecentImportEntry(name = "desync_fake_relay_us.json", `when` = "Yesterday"),
                RecentImportEntry(name = "QR · po4yka.github.io/RIPDPI/share", `when` = "2d ago"),
            ),
    )
