package com.poyka.ripdpi.ui.screens.proxyimport

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R

@Composable
fun ProfileShareRoute(
    profileId: String,
    onBack: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    viewModel: ProfileShareRouteViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(viewModel, profileId) {
        viewModel.load(profileId)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val linkTitle = stringResource(R.string.profile_share_link_title)
    val sheetTitle = stringResource(R.string.profile_share_sheet_title)
    val profileLinkLabel = stringResource(R.string.clipboard_label_profile_link)
    val setupSheetLabel = stringResource(R.string.clipboard_label_setup_sheet)
    ProfileShareScreen(
        uiState = uiState,
        onBack = onBack,
        onCopyLink = { copyText(context, label = profileLinkLabel, text = it) },
        onShareLink = { shareText(context, title = linkTitle, text = it) },
        onCopySheet = { copyText(context, label = setupSheetLabel, text = it) },
        onShareSheet = { shareText(context, title = sheetTitle, text = it) },
        modifier = modifier,
    )
}

private fun copyText(
    context: Context,
    label: String,
    text: String,
) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
}

private fun shareText(
    context: Context,
    title: String,
    text: String,
) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
    context.startActivity(Intent.createChooser(intent, title))
}

internal fun renderShareQrBitmap(content: String): Bitmap? {
    val matrix =
        com.poyka.ripdpi.proxyimport.QrCodeEncoder
            .encodeMatrixOrNull(content, QrBitmapSize) ?: return null
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val rowOffset = y * width
        for (x in 0 until width) {
            pixels[rowOffset + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private const val QrBitmapSize = 512
