package com.poyka.ripdpi.ui.screens.crash

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.crash.CrashReport
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals

@Composable
internal fun CrashReportDialog(
    report: CrashReport,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val exceptionSummary =
        buildString {
            append(report.exceptionClass)
            if (report.message.isNotEmpty()) {
                append(": ")
                append(
                    report.message
                        .lineSequence()
                        .first()
                        .take(MaxMessageLength),
                )
            }
        }

    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.crash_report_title),
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.crash_report_dismiss),
                onClick = onDismiss,
            ),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.crash_report_share),
                onClick = onShare,
            ),
        visuals =
            RipDpiDialogVisuals(
                message = stringResource(R.string.crash_report_body, exceptionSummary),
                tone = RipDpiDialogTone.Destructive,
            ),
    )
}

private const val MaxMessageLength = 120
