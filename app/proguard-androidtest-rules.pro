# R8 rules for the minified release androidTest APK (-Pripdpi.testBuildType=release).
# Kaspresso, Kakao and espresso-contrib reference Material views, but the Material artifact is excluded
# from androidTest because the app is Compose-only and those view matchers are never used.
-dontwarn com.google.android.material.**
# The accessibility framework is compiled against protobuf-lite 3.0.1, which is excluded so it cannot
# shadow the app's protobuf-javalite; only these two removed visitor types are unresolved.
-dontwarn com.google.protobuf.GeneratedMessageLite$MergeFromVisitor
-dontwarn com.google.protobuf.GeneratedMessageLite$Visitor
# ui-tooling's PreviewAnimationClock reaches the test APK without its internal animation-tooling artifact.
-dontwarn androidx.compose.animation.tooling.**
