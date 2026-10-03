package com.poyka.ripdpi.services.selector

import com.poyka.ripdpi.data.Mode

/**
 * A selector-runtime component whose watch/probe loops should run only while a
 * foreground runtime is alive. Each service [start]s every bound listener in
 * `onCreate` and [stop]s them in `onDestroy`, so selector hot-reload and the
 * urltest prober follow the service lifecycle without the service knowing the
 * concrete (app-layer) implementations.
 *
 * Bound as a Hilt multibinding (`@IntoSet`): each independent selector loop
 * contributes one listener. [start] / [stop] must be idempotent per owner and retain loops until the last owner stops.
 */
interface SelectorRuntimeLifecycleListener {
    /** Applies a durable selection before the initial policy snapshot, under lifecycle serialization. */
    suspend fun prepare() = Unit

    /** Reconciles changes that arrived while the first runtime was being built. */
    suspend fun afterStart() = Unit

    fun start(owner: Mode)

    fun stop(owner: Mode)
}
