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
