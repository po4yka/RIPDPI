package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

interface ServiceAutomationController {
    fun interceptStart(mode: Mode): Boolean = false

    fun interceptStop(currentMode: Mode): Boolean = false
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceAutomationControllerOptionalBindingsModule {
    @BindsOptionalOf
    abstract fun bindServiceAutomationController(): ServiceAutomationController
}
