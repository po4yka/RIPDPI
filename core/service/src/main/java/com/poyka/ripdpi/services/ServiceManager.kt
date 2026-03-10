package com.poyka.ripdpi.services

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import logcat.LogPriority
import logcat.logcat

interface ServiceController {
    fun start(mode: Mode)

    fun stop()
}

@Singleton
class DefaultServiceController
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val serviceStateStore: ServiceStateStore,
    ) : ServiceController {
        override fun start(mode: Mode) {
            when (mode) {
                Mode.VPN -> {
                    logcat(LogPriority.INFO) { "Starting VPN" }
                    val intent = Intent(context, RipDpiVpnService::class.java).apply {
                        action = START_ACTION
                    }
                    ContextCompat.startForegroundService(context, intent)
                }

                Mode.Proxy -> {
                    logcat(LogPriority.INFO) { "Starting proxy" }
                    val intent = Intent(context, RipDpiProxyService::class.java).apply {
                        action = START_ACTION
                    }
                    ContextCompat.startForegroundService(context, intent)
                }
            }
        }

        override fun stop() {
            when (serviceStateStore.status.value.second) {
                Mode.VPN -> {
                    logcat(LogPriority.INFO) { "Stopping VPN" }
                    val intent = Intent(context, RipDpiVpnService::class.java).apply {
                        action = STOP_ACTION
                    }
                    ContextCompat.startForegroundService(context, intent)
                }

                Mode.Proxy -> {
                    logcat(LogPriority.INFO) { "Stopping proxy" }
                    val intent = Intent(context, RipDpiProxyService::class.java).apply {
                        action = STOP_ACTION
                    }
                    ContextCompat.startForegroundService(context, intent)
                }
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceBindingsModule {
    @Binds
    @Singleton
    abstract fun bindServiceStateStore(
        serviceStateStore: DefaultServiceStateStore,
    ): ServiceStateStore

    @Binds
    @Singleton
    abstract fun bindServiceController(
        serviceController: DefaultServiceController,
    ): ServiceController
}
