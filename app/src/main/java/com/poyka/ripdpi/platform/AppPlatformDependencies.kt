package com.poyka.ripdpi.platform

import android.content.Context
import androidx.annotation.StringRes
import com.poyka.ripdpi.activities.LauncherIconManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface LauncherIconController {
    fun applySelection(
        iconKey: String,
        iconStyle: String,
    )
}

@Singleton
class DefaultLauncherIconController
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : LauncherIconController {
        override fun applySelection(
            iconKey: String,
            iconStyle: String,
        ) {
            LauncherIconManager.applySelection(
                context = context,
                iconKey = iconKey,
                iconStyle = iconStyle,
            )
        }
    }

interface StringResolver {
    fun getString(
        @StringRes resId: Int,
        vararg formatArgs: Any,
    ): String
}

@Singleton
class AndroidStringResolver
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : StringResolver {
        override fun getString(
            @StringRes resId: Int,
            vararg formatArgs: Any,
        ): String = context.getString(resId, *formatArgs)
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class AppPlatformBindingsModule {
    @Binds
    @Singleton
    abstract fun bindLauncherIconController(
        controller: DefaultLauncherIconController,
    ): LauncherIconController

    @Binds
    @Singleton
    abstract fun bindStringResolver(
        resolver: AndroidStringResolver,
    ): StringResolver
}
