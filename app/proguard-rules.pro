# App-only shrinker rules live here.
#
# Keep this file intentionally small:
# - Manifest entry points keep their own names through the Android manifest.
# - Hilt, Compose, Room, and Retrofit should rely on generated/library rules.
# - Engine/Data compatibility boundaries belong in module-level consumer rules.
#
# If a future minified build fails, add the smallest rule that names the exact
# compatibility boundary. Do not paste missing_rules.txt suggestions verbatim
# and do not add blanket -dontwarn or -keep rules.

# Guava references J2ObjC annotations that only exist on iOS/macOS targets.
# They are compile-time-only and never loaded on Android.
-dontwarn com.google.j2objc.annotations.**

# WorkManager can emit debug/verbose scheduler chatter before app code starts.
# Release builds already use WARN in Configuration.Provider; strip any remaining
# debug/verbose calls from the minified APK so support logs stay low-noise.
-assumenosideeffects class androidx.work.Logger {
    public void verbose(...);
    public void debug(...);
}

# The release AndroidTest APK supplies a custom Hilt Application that implements
# WorkManager's Configuration.Provider. R8 processes the target APK before the
# test APK and cannot see those test-only references, so these types must remain
# available under their source names for the instrumentation class loader.
-keep class androidx.work.Configuration { *; }
-keep class androidx.work.Configuration$Builder { *; }
-keep interface androidx.work.Configuration$Provider
-keep class dagger.hilt.android.EntryPointAccessors { *; }
-keep interface dagger.hilt.internal.GeneratedComponentManager { *; }
-keep interface dagger.hilt.internal.GeneratedComponentManagerHolder { *; }
-keep interface dagger.hilt.internal.TestSingletonComponent { *; }
-keep interface dagger.hilt.internal.TestSingletonComponentManager { *; }
-keep,allowoptimization,allowobfuscation class androidx.hilt.work.WorkerFactoryModule_ProvideFactoryFactory
-keepclassmembers class androidx.hilt.work.WorkerFactoryModule_ProvideFactoryFactory { *; }
-keep,allowoptimization,allowobfuscation class com.poyka.ripdpi.RipDpiApp_MembersInjector
-keepclassmembers class com.poyka.ripdpi.RipDpiApp_MembersInjector { *; }
-keep,allowoptimization,allowobfuscation class dagger.hilt.android.flags.FragmentGetContextFix$FragmentGetContextFixEntryPoint
-keepclassmembers class dagger.hilt.android.flags.FragmentGetContextFix$FragmentGetContextFixEntryPoint { *; }
-keep,allowoptimization,allowobfuscation class dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories$InternalFactoryFactory
-keepclassmembers class dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories$InternalFactoryFactory { *; }
-keep,allowoptimization,allowobfuscation class dagger.hilt.android.internal.managers.FragmentComponentManager$FragmentComponentBuilderEntryPoint
-keepclassmembers class dagger.hilt.android.internal.managers.FragmentComponentManager$FragmentComponentBuilderEntryPoint { *; }
-keep,allowoptimization,allowobfuscation class dagger.hilt.components.SingletonComponent
-keepclassmembers class dagger.hilt.components.SingletonComponent { *; }
-keep class androidx.tracing.Trace { *; }
-keep public class kotlin.** {
    public protected *;
}
-keep class kotlinx.coroutines.BuildersKt { public *; }
-keep class androidx.core.content.ContextCompat { public protected *; }
-keep interface dagger.MembersInjector { *; }
-keep class dagger.hilt.android.internal.Contexts { *; }
-keep class dagger.hilt.internal.Preconditions { *; }
-keep interface dagger.internal.Factory { *; }
-keep class dagger.internal.Preconditions { *; }
-keep interface com.poyka.ripdpi.core.RipDpiProxyBindings { *; }
-keep interface com.poyka.ripdpi.core.RipDpiProxyFactory { *; }
-keep interface com.poyka.ripdpi.core.RipDpiProxyRuntime { *; }
-keep interface com.poyka.ripdpi.core.Tun2SocksBindings { *; }
-keep interface com.poyka.ripdpi.core.Tun2SocksBridge { *; }
-keep interface com.poyka.ripdpi.core.Tun2SocksBridgeFactory { *; }
-keep class com.poyka.ripdpi.core.RipDpiProxyFactoryModule { *; }
-keep class com.poyka.ripdpi.core.Tun2SocksBridgeFactoryModule { *; }
-keep class com.poyka.ripdpi.e2e.VpnConsentUiCandidate { *; }
-keep class com.poyka.ripdpi.e2e.VpnConsentUiSelector { *; }
-keep interface com.poyka.ripdpi.data.ServiceStateStore { *; }
