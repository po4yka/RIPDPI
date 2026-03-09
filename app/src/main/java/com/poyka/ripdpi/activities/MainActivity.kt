package com.poyka.ripdpi.activities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.feedback.showRipDpiSnackbar
import com.poyka.ripdpi.ui.navigation.RipDpiNavHost
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val openHomeRequests = MutableStateFlow(false)

    companion object {
        private val TAG: String = MainActivity::class.java.simpleName
        private const val EXTRA_OPEN_HOME = "com.poyka.ripdpi.extra.OPEN_HOME"

        private fun collectLogs(): String? =
            try {
                Runtime
                    .getRuntime()
                    .exec("logcat *:D -d")
                    .inputStream
                    .bufferedReader()
                    .use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to collect logs", e)
                null
            }

        fun createLaunchIntent(
            context: Context,
            openHome: Boolean = false,
        ): Intent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (openHome) {
                    putExtra(EXTRA_OPEN_HOME, true)
                }
            }

        internal fun requestsHomeTab(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_OPEN_HOME, false) == true
    }

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.onVpnPermissionResult(this, it.resultCode == RESULT_OK)
        }

    private val logsRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val logs = collectLogs()

                if (logs == null) {
                    withContext(Dispatchers.Main) {
                        Toast
                            .makeText(
                                this@MainActivity,
                                R.string.logs_failed,
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                    return@launch
                }

                val uri =
                    it.data?.data ?: run {
                        Log.e(TAG, "No data in result")
                        return@launch
                    }
                contentResolver.openOutputStream(uri)?.use { stream ->
                    try {
                        stream.write(logs.toByteArray())
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to save logs", e)
                    }
                } ?: run {
                    Log.e(TAG, "Failed to open output stream")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openHomeRequests.value = requestsHomeTab(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val openHomeRequested by openHomeRequests.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }

            LaunchedEffect(viewModel, snackbarHostState) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is MainEffect.RequestVpnPermission -> {
                            vpnRegister.launch(effect.prepareIntent)
                        }

                        is MainEffect.ShowError -> {
                            snackbarHostState.showRipDpiSnackbar(
                                message = effect.message,
                                tone = RipDpiSnackbarTone.Error,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }
                }
            }

            RipDpiTheme(themePreference = uiState.theme) {
                RipDpiNavHost(
                    onSaveLogs = { saveLogs() },
                    mainViewModel = viewModel,
                    launchHomeRequested = openHomeRequested,
                    onLaunchHomeHandled = {
                        openHomeRequests.value = false
                    },
                    snackbarHostState = snackbarHostState,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (requestsHomeTab(intent)) {
            openHomeRequests.value = true
        }
    }

    private fun saveLogs() {
        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "ripdpi.log")
            }
        logsRegister.launch(intent)
    }
}
