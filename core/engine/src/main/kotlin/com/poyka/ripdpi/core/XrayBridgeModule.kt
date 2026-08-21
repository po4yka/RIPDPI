package com.poyka.ripdpi.core

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Absolute path to the geo `.dat` directory libXray reads at start.
 *
 * Never carries secrets — it is the geoip/geosite asset directory written by the
 * service-layer `GeoAssetRepository` via [resolveGeoDatabasePaths].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class XrayDatDir

/**
 * Binds the real [XrayNativeBridge] singleton.
 *
 * Lives in the `main` source set so it compiles in both the `xrayLinked`
 * (gomobile-backed) and `xrayStub` (offline) variants — both define the
 * `XrayNativeBridgeLibXrayImpl(datDir: String)` constructor used here. A
 * `@Provides` (not `@Binds`) is required because the implementation constructor
 * takes a non-injected `ffi` default in the linked variant.
 *
 * NOTE: no `:core:service` code injects [XrayNativeBridge] yet (the service layer
 * does not select/run the Xray provider), so this is currently a ready-to-inject
 * binding rather than an actively constructed one — wiring the orchestrator
 * consumer is a separate epic-xray-provider-mode task.
 */
@Module
@InstallIn(SingletonComponent::class)
object XrayBridgeModule {
    @Provides
    @Singleton
    @XrayDatDir
    fun provideXrayDatDir(
        @ApplicationContext context: Context,
    ): String = resolveGeoDatabasePaths(context).geoDirPath

    @Provides
    @Singleton
    fun provideXrayNativeBridge(
        @XrayDatDir datDir: String,
    ): XrayNativeBridge = XrayNativeBridgeLibXrayImpl(datDir)
}
