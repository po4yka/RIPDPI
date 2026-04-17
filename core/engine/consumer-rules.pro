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
