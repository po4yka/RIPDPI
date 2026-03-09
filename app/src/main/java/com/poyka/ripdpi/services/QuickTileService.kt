package com.poyka.ripdpi.services

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.settingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickTileService : TileService() {
    companion object {
        private val TAG: String = QuickTileService::class.java.simpleName
    }

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = newScope
        updateStatus()

        newScope.launch {
            AppStateManager.status.collect { updateStatus() }
        }

        newScope.launch {
            AppStateManager.events.collect { event ->
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
                MainActivity.createLaunchIntent(this, openHome = true),
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
        val (status) = AppStateManager.status.value
        setState(if (status == AppStatus.Halted) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE)
    }

    private fun handleClick() {
        setState(Tile.STATE_ACTIVE)
        setState(Tile.STATE_UNAVAILABLE)

        val (status) = AppStateManager.status.value
        when (status) {
            AppStatus.Halted -> {
                scope?.launch {
                    val mode =
                        withContext(Dispatchers.IO) {
                            val settings = settingsStore.data.first()
                            val modeStr = settings.ripdpiMode.ifEmpty { "vpn" }
                            Mode.fromString(modeStr)
                        }

                    if (mode == Mode.VPN && VpnService.prepare(this@QuickTileService) != null) {
                        updateStatus()
                        launchActivity()
                        return@launch
                    }

                    ServiceManager.start(this@QuickTileService, mode)
                }
            }

            AppStatus.Running -> {
                ServiceManager.stop(this)
            }
        }
    }
}
