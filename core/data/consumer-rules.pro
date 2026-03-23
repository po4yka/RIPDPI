# Preserve only the project's generated lite proto surface that is read and
# written through DataStore and shared with native/config contracts.
-keep class com.poyka.ripdpi.proto.** { *; }
