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
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.services.ServiceController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class QuickTileService : TileService() {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    lateinit var serviceController: ServiceController

    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = newScope
        updateStatus()

        newScope.launch {
            serviceStateStore.status.collect { updateStatus() }
        }

        newScope.launch {
            serviceStateStore.events.collect { event ->
                when (event) {
                    is ServiceEvent.Failed -> {
                        Toast
                            .makeText(
                                this@QuickTileService,
                                getString(R.string.failed_to_start, event.sender.senderName),
                                Toast.LENGTH_SHORT,
                            ).show()
                        updateStatus()
                    }
                }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    private fun launchActivity() {
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
        if (qsTile.state == Tile.STATE_UNAVAILABLE) {
            return
        }

        unlockAndRun(this::handleClick)
    }

    private fun setState(newState: Int) {
        qsTile.apply {
            state = newState
            updateTile()
        }
    }

    private fun updateStatus() {
        val (status) = serviceStateStore.status.value
        setState(if (status == AppStatus.Halted) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE)
    }

    private fun handleClick() {
        setState(Tile.STATE_ACTIVE)
        setState(Tile.STATE_UNAVAILABLE)

        val (status) = serviceStateStore.status.value
        when (status) {
            AppStatus.Halted -> {
                scope?.launch {
                    val settings = appSettingsRepository.snapshot()
                    val mode = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" })

                    if (needsPermissionResolution(mode)) {
                        updateStatus()
                        launchActivity()
                        return@launch
                    }

                    serviceController.start(mode)
                }
            }

            AppStatus.Running -> {
                serviceController.stop()
            }
        }
    }

    private fun needsPermissionResolution(mode: Mode): Boolean =
        needsNotificationsPermission() || (mode == Mode.VPN && VpnService.prepare(this) != null)

    private fun needsNotificationsPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
}
