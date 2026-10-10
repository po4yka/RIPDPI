package com.poyka.ripdpi.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.feedback.RipDpiAccordion
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.theme.RipDpiTheme

@Preview(name = "Quality dialog large font", widthDp = 320, heightDp = 640, fontScale = 2f)
@Composable
private fun QualityDialogLargeFontPreview() {
    RipDpiTheme {
        RipDpiDialogCard(
            title = "Export traffic",
            dismissAction = RipDpiDialogAction("Cancel export", {}),
            confirmAction = RipDpiDialogAction("Export captured network traffic", {}),
            visuals = RipDpiDialogVisuals(icon = null),
        ) { Text("Packet headers and endpoint addresses.") }
    }
}

@Preview(name = "Quality dialog medium window large font", widthDp = 800, heightDp = 640, fontScale = 2f)
@Composable
private fun QualityDialogMediumWindowPreview() {
    RipDpiTheme {
        Box(Modifier.requiredWidth(320.dp)) {
            RipDpiDialogCard(
                title = "Export traffic",
                dismissAction = RipDpiDialogAction("Cancel traffic export", {}),
                confirmAction = RipDpiDialogAction("Export captured network traffic", {}),
                visuals = RipDpiDialogVisuals(icon = null),
            )
        }
    }
}

@Preview(name = "Quality feedback large font", widthDp = 320, heightDp = 640, fontScale = 2f)
@Composable
private fun QualityFeedbackLargeFontPreview() {
    RipDpiTheme {
        Column {
            WarningBanner("Permission needed", "Open Android settings.", onDismiss = {})
            RipDpiAccordion("Network modes and diagnostics", true, {}) {
                Text("Advanced options stay readable.")
            }
        }
    }
}
