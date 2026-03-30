package com.poyka.ripdpi.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.data.ApplicationScope
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

interface ScreenStateObserver {
    val isInteractive: StateFlow<Boolean>
}

@Singleton
class DefaultScreenStateObserver
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) : ScreenStateObserver {
        private val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as PowerManager

        private val initialValue = powerManager.isInteractive

        override val isInteractive: StateFlow<Boolean> =
            callbackFlow {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            context: Context,
                            intent: Intent,
                        ) {
                            when (intent.action) {
                                Intent.ACTION_SCREEN_ON -> trySend(true)
                                Intent.ACTION_SCREEN_OFF -> trySend(false)
                            }
                        }
                    }

                val filter =
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                    }
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )

                awaitClose {
                    runCatching { context.unregisterReceiver(receiver) }
                }
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
                initialValue = initialValue,
            )
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ScreenStateObserverModule {
    @Binds
    @Singleton
    abstract fun bindScreenStateObserver(observer: DefaultScreenStateObserver): ScreenStateObserver
}
