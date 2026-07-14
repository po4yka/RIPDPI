package com.poyka.ripdpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Collects one-shot UI events only while their host can safely execute navigation and Activity actions. */
@Composable
internal fun <T> LifecycleEventEffect(
    flow: Flow<T>,
    onEvent: suspend (T) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnEvent = rememberUpdatedState(onEvent)
    LaunchedEffect(flow, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            flow.collect { event -> currentOnEvent.value(event) }
        }
    }
}

/** Captures a producer-facing shared flow immediately and queues events while its UI collector is stopped. */
internal fun <T> MutableSharedFlow<T>.bufferForUiLifecycle(scope: CoroutineScope): Flow<T> {
    val pendingEvents = Channel<T>(capacity = Channel.BUFFERED)
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        collect { event -> pendingEvents.send(event) }
    }
    return pendingEvents.receiveAsFlow()
}
