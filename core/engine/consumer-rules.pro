# JNI symbol lookup depends on the exact declaring class and native method names.
# Keep only those names stable; higher-level engine wrappers remain shrinkable.
-keepclasseswithmembernames class com.poyka.ripdpi.core.RipDpiProxyNativeBindings {
    native <methods>;
}

-keepclasseswithmembernames class com.poyka.ripdpi.core.Tun2SocksNativeBindings {
    native <methods>;
}

-keepclasseswithmembernames class com.poyka.ripdpi.core.NetworkDiagnosticsNativeBindings {
    native <methods>;
}

-keepclasseswithmembernames class com.poyka.ripdpi.core.RipDpiRelayNativeBindings {
    native <methods>;
}

-keepclasseswithmembernames class com.poyka.ripdpi.core.RipDpiWarpNativeBindings {
    native <methods>;
}

# Native readiness push (ADR 0003): the native runtime invokes onRuntimeReady()
# by name via JNI call_method. It is never called from Kotlin, so R8 would
# otherwise strip or rename it. Keep the method on the interface and on every
# implementor (including the SAM lambdas the wrappers register).
-keep,allowobfuscation interface com.poyka.ripdpi.core.RuntimeReadinessListener {
    void onRuntimeReady();
}
-keepclassmembers class * implements com.poyka.ripdpi.core.RuntimeReadinessListener {
    void onRuntimeReady();
}
