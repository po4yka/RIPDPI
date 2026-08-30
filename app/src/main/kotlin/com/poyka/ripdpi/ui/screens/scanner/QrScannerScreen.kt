package com.poyka.ripdpi.ui.screens.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.proxyimport.ProxyImportRequest
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import kotlinx.coroutines.launch

/**
 * QR scanner destination.
 *
 * A Compose screen wrapping a CameraX preview + ML Kit barcode analyzer ([QrCameraAnalyzer]).
 * On a successful decode of an allowlisted proxy URI the screen routes to the
 * profile-import confirmation destination via [onProfileScanned]. The camera-permission
 * flow degrades gracefully: a denied permission keeps the image-file-pick fallback
 * ([QrImageScanner]) available rather than bricking the screen. Invalid QR content shows
 * a redacted error (first 16 chars only); the full payload is never logged.
 * Camera permission is requested once per [sessionId]; later grant-state changes only
 * synchronize the scanner state, so an explicit revocation is not immediately re-prompted.
 */
@Composable
fun QrScannerRoute(
    sessionId: String,
    onBack: () -> Unit,
    onProfileScanned: (ProxyImportRequest.Profile) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QrScannerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onCameraPermissionResult(granted)
        }
    val imagePickLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    QrImageScanner.decode(context, uri)?.let(viewModel::onQrDecoded)
                }
            }
        }

    // The camera permission is the data-determining argument for the
    // state synchronization effect below: it is re-evaluated whenever the host resumes,
    // so a grant made from system settings while this destination stays
    // composed still reaches the scanner state machine.
    val cameraPermissionGranted = rememberCameraPermissionGranted(context)

    LaunchedEffect(cameraPermissionGranted) {
        viewModel.syncCameraPermission(cameraPermissionGranted)
    }

    LaunchedEffect(sessionId) {
        viewModel.requestCameraPermissionOnce(
            sessionId = sessionId,
            cameraPermissionGranted = cameraPermissionGranted,
            requestCameraPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
        )
    }

    LaunchedEffect(uiState.importRequest) {
        val request = uiState.importRequest
        if (request is ProxyImportRequest.Profile) {
            viewModel.consumeImportRequest()
            onProfileScanned(request)
        }
    }

    QrScannerScreen(
        uiState = uiState,
        onBack = onBack,
        onQrDecoded = viewModel::onQrDecoded,
        onDismissError = viewModel::consumeDecodeError,
        onPickImage = { imagePickLauncher.launch(arrayOf("image/*")) },
        modifier = modifier,
    )
}

@Composable
internal fun QrScannerScreen(
    uiState: QrScannerUiState,
    onBack: () -> Unit,
    onQrDecoded: (String) -> Unit,
    onDismissError: () -> Unit,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.scanner_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.QrScanner)),
    ) {
        when (uiState.cameraState) {
            ScannerCameraState.REQUESTING_PERMISSION -> {
                RipDpiCard {
                    RipDpiPanelHeader(
                        title = stringResource(R.string.scanner_permission_rationale_title),
                        supporting = stringResource(R.string.scanner_permission_rationale_body),
                    )
                }
            }

            ScannerCameraState.SCANNING -> {
                RipDpiCard {
                    RipDpiPanelHeader(
                        title = stringResource(R.string.scanner_scanning_title),
                        supporting = stringResource(R.string.scanner_scanning_body),
                    )
                    CameraPreview(
                        onQrDecoded = onQrDecoded,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                    )
                }
            }

            ScannerCameraState.PERMISSION_DENIED -> {
                WarningBanner(
                    title = stringResource(R.string.scanner_permission_denied_title),
                    message = stringResource(R.string.scanner_permission_denied_body),
                    tone = WarningBannerTone.Restricted,
                )
            }
        }

        uiState.decodeError?.let { redactedPreview ->
            WarningBanner(
                title = stringResource(R.string.scanner_invalid_qr_title),
                message = stringResource(R.string.scanner_invalid_qr_body, redactedPreview),
                tone = WarningBannerTone.Warning,
                onDismiss = onDismissError,
            )
        }

        if (uiState.imageFallbackAvailable) {
            RipDpiButton(
                text = stringResource(R.string.scanner_pick_image_action),
                onClick = onPickImage,
                variant = RipDpiButtonVariant.Secondary,
                leadingIcon = RipDpiIcons.Image,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CameraPreview(
    onQrDecoded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val currentOnQrDecoded by rememberUpdatedState(onQrDecoded)
    val analyzerExecutor =
        remember {
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        }

    DisposableEffect(lifecycleOwner, previewView) {
        var disposed = false
        var cameraProvider: ProcessCameraProvider? = null
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val preview =
            Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analyzer = QrCameraAnalyzer { currentOnQrDecoded(it) }
        val analysis =
            ImageAnalysis
                .Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analyzerExecutor, analyzer) }
        cameraProviderFuture.addListener(
            {
                if (disposed) return@addListener
                runCatching {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            analyzer.close()
            runCatching { cameraProvider?.unbindAll() }
            analyzerExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Observes the runtime camera-permission grant state as Compose state.
 *
 * The value is read once on composition entry and re-evaluated whenever the
 * host lifecycle resumes, so a permission granted (or revoked) from system
 * settings while this destination stays composed is picked up without an
 * activity recreation. This is the data-determining key for the scanner's
 * state synchronization [LaunchedEffect] in [QrScannerRoute].
 */
@Composable
internal fun rememberCameraPermissionGranted(context: Context): Boolean {
    var granted by remember(context) { mutableStateOf(isCameraPermissionGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    granted = isCameraPermissionGranted(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted
}

private fun isCameraPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

internal fun QrScannerViewModel.syncCameraPermission(cameraPermissionGranted: Boolean) {
    onCameraPermissionResult(granted = cameraPermissionGranted)
}
