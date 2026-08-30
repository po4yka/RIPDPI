package com.poyka.ripdpi.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsEffect
import com.poyka.ripdpi.activities.SettingsNoticeTone
import com.poyka.ripdpi.data.SecretString
import com.poyka.ripdpi.data.WsTunnelWorkerTransportProvisioner
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.components.bufferForUiLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WsTunnelWorkerSettingsViewModel
    @Inject
    constructor(
        private val provisioner: WsTunnelWorkerTransportProvisioner,
        private val stringResolver: StringResolver,
    ) : ViewModel() {
        private val mutableEffects =
            MutableSharedFlow<SettingsEffect>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        val effects = mutableEffects.bufferForUiLifecycle(viewModelScope)

        fun save(
            workerUrl: String,
            credentialRef: String,
            bearer: String,
        ) {
            viewModelScope.launch {
                runProvisioningAction(
                    failureTitle = R.string.ws_tunnel_worker_save_failed_title,
                    failureMessage = R.string.ws_tunnel_worker_save_failed_message,
                ) {
                    provisioner.provision(
                        workerUrl = workerUrl,
                        credentialRef = credentialRef,
                        bearer = SecretString(bearer),
                    )
                }
            }
        }

        fun clear() {
            viewModelScope.launch {
                runProvisioningAction(
                    failureTitle = R.string.ws_tunnel_worker_clear_failed_title,
                    failureMessage = R.string.ws_tunnel_worker_clear_failed_message,
                    action = provisioner::clear,
                )
            }
        }

        private suspend fun runProvisioningAction(
            failureTitle: Int,
            failureMessage: Int,
            action: suspend () -> Unit,
        ) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableEffects.emit(
                    SettingsEffect.Notice(
                        title = stringResolver.getString(failureTitle),
                        message = stringResolver.getString(failureMessage),
                        tone = SettingsNoticeTone.Error,
                    ),
                )
            }
        }
    }
