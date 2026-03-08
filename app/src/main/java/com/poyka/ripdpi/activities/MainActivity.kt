package com.poyka.ripdpi.activities

import android.Manifest
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.services.ServiceEvent
import com.poyka.ripdpi.ui.navigation.RipDpiNavHost
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    companion object {
        private val TAG: String = MainActivity::class.java.simpleName

        private fun collectLogs(): String? =
            try {
                Runtime.getRuntime()
                    .exec("logcat *:D -d")
                    .inputStream.bufferedReader()
                    .use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to collect logs", e)
                null
            }
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
                        Toast.makeText(
                            this@MainActivity,
                            R.string.logs_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                val uri = it.data?.data ?: run {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) {
                viewModel.events.collect { event ->
                    when (event) {
                        is ServiceEvent.Failed -> {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.failed_to_start, event.sender.senderName),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }

            LaunchedEffect(Unit) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is MainEffect.RequestVpnPermission ->
                            vpnRegister.launch(effect.prepareIntent)
                        is MainEffect.NavigateToSettings -> Unit
                        is MainEffect.ShowToast ->
                            Toast.makeText(this@MainActivity, effect.messageResId, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            RipDpiTheme(themePreference = uiState.theme) {
                RipDpiNavHost(
                    onSaveLogs = { saveLogs() },
                )
            }
        }
    }

    private fun saveLogs() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "ripdpi.log")
        }
        logsRegister.launch(intent)
    }
}
