/*
 * Based on the Android Open Source Project Navigation 3 shared ViewModel recipe.
 * https://github.com/android/nav3-recipes/tree/main/app/src/main/java/com/example/nav3recipes/sharedviewmodel
 */
package com.poyka.ripdpi.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.ViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreProvider
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.savedstate.compose.LocalSavedStateRegistryOwner

@Composable
internal fun <T : Any> rememberRipDpiSharedViewModelStoreNavEntryDecorator(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
): RipDpiSharedViewModelStoreNavEntryDecorator<T> {
    val viewModelStoreProvider = rememberViewModelStoreProvider(viewModelStoreOwner)
    return remember(viewModelStoreOwner) {
        RipDpiSharedViewModelStoreNavEntryDecorator(viewModelStoreProvider)
    }
}

internal class RipDpiSharedViewModelStoreNavEntryDecorator<T : Any>(
    viewModelStoreProvider: ViewModelStoreProvider,
) : NavEntryDecorator<T>(
        onPop = viewModelStoreProvider::clearKey,
        decorate = { entry ->
            val localOwner =
                rememberViewModelStoreOwner(
                    entry.contentKey,
                    viewModelStoreProvider,
                    savedStateRegistryOwner = LocalSavedStateRegistryOwner.current,
                )
            val localValues = mutableListOf<ProvidedValue<*>>(LocalViewModelStoreOwner provides localOwner)

            entry.metadata[ParentKey]?.let { parentContentKey ->
                val parentOwner =
                    rememberViewModelStoreOwner(
                        parentContentKey,
                        viewModelStoreProvider,
                        savedStateRegistryOwner = LocalSavedStateRegistryOwner.current,
                    )
                localValues += LocalRipDpiSharedViewModelStoreOwner provides parentOwner
            }
            CompositionLocalProvider(values = localValues.toTypedArray()) {
                entry.Content()
            }
        },
    ) {
    companion object {
        fun parent(key: Any) = metadata { put(ParentKey, key) }

        object ParentKey : NavMetadataKey<Any>
    }
}

internal val LocalRipDpiSharedViewModelStoreOwner =
    staticCompositionLocalOf<ViewModelStoreOwner> {
        error("No shared ViewModelStoreOwner was provided")
    }
