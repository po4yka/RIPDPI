package com.poyka.ripdpi.services

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ServiceStateStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject

@AndroidEntryPoint
class QuickTileService :
    TileService(),
    QuickTileHost {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    lateinit var serviceController: ServiceController

    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    private var scope: CoroutineScope? = null
    private val controller by lazy {
        QuickTileController(
            appSettingsRepository = appSettingsRepository,
            serviceController = serviceController,
            serviceStateStore = serviceStateStore,
        )
    }

    override fun onStartListening() {
        scope?.cancel()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = newScope
        controller.onStartListening(host = this, scope = newScope)
    }

    override fun onStopListening() {
        controller.onStopListening()
        scope?.cancel()
        scope = null
    }

    override fun launchStartResolution() {
        TileServiceCompat.startActivityAndCollapse(
            this,
            PendingIntentActivityWrapper(
                this,
                0,
                MainActivity.createLaunchIntent(
                    this,
                    openHome = true,
                    requestStartConfiguredMode = true,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT,
                false,
            ),
        )
    }

    override fun onClick() {
        unlockAndRun {
            controller.onClick(this)
        }
    }

    override fun renderTileState(state: QuickTileVisualState) {
        val tileState =
            when (state) {
                QuickTileVisualState.Active -> Tile.STATE_ACTIVE
                QuickTileVisualState.Inactive -> Tile.STATE_INACTIVE
                QuickTileVisualState.Unavailable -> Tile.STATE_UNAVAILABLE
            }
        qsTile.apply {
            this.state = tileState
            updateTile()
        }
    }

    override fun showStartFailure(senderName: String) {
        Toast
            .makeText(
                this,
                getString(R.string.failed_to_start, senderName),
                Toast.LENGTH_SHORT,
            ).show()
    }

    override fun notificationsPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    override fun vpnPermissionRequired(): Boolean = VpnService.prepare(this) != null
}
