# The target shrinker cannot see calls from the separate AndroidTest APK.
-keepclassmembers,allowobfuscation,includedescriptorclasses class com.poyka.ripdpi.diagnostics.dpi.QuicH3FingerprintProbe {
    public <init>(com.poyka.ripdpi.diagnostics.dpi.QuicUdpProbe,com.poyka.ripdpi.diagnostics.dpi.QuicInitialPacketFactory,com.poyka.ripdpi.diagnostics.dpi.QuicProbeResponseValidator,int,int,int,kotlin.time.TimeSource,int,kotlin.jvm.internal.DefaultConstructorMarker);
    public java.lang.Object check(java.lang.String,kotlin.coroutines.Continuation);
}
-keepclassmembers,allowobfuscation,includedescriptorclasses class com.poyka.ripdpi.diagnostics.dpi.QuicProbeResult {
    public java.util.Map getAttempts();
    public com.poyka.ripdpi.diagnostics.dpi.QuicProbeVerdict getVerdict();
}
-keepclassmembers,allowobfuscation,includedescriptorclasses enum com.poyka.ripdpi.diagnostics.dpi.QuicProbeVerdict {
    public static final com.poyka.ripdpi.diagnostics.dpi.QuicProbeVerdict QUIC_INCONCLUSIVE;
}
-keepclassmembers,allowobfuscation,includedescriptorclasses class com.poyka.ripdpi.diagnostics.dpi.DatagramSocketQuicUdpProbe {
    public <init>();
    public java.lang.Object sendInitial(java.net.InetAddress,int,com.poyka.ripdpi.diagnostics.dpi.QuicFingerprint,byte[],kotlin.time.TimeMark,kotlin.jvm.functions.Function1,kotlin.coroutines.Continuation);
}
-keepclassmembers,allowobfuscation,includedescriptorclasses class com.poyka.ripdpi.diagnostics.dpi.QuicProbeSample {
    public com.poyka.ripdpi.diagnostics.dpi.QuicProbeOutcome getOutcome();
    public int getInvalidDatagrams();
}
-keepclassmembers,allowobfuscation,includedescriptorclasses enum com.poyka.ripdpi.diagnostics.dpi.QuicProbeOutcome {
    public static final com.poyka.ripdpi.diagnostics.dpi.QuicProbeOutcome INITIAL_CRYPTO;
    public static final com.poyka.ripdpi.diagnostics.dpi.QuicProbeOutcome RETRY;
    public static final com.poyka.ripdpi.diagnostics.dpi.QuicProbeOutcome VERSION_NEGOTIATION;
    public static final com.poyka.ripdpi.diagnostics.dpi.QuicProbeOutcome UNCORRELATED_RESPONSE;
}
-keepclassmembers,allowobfuscation,includedescriptorclasses class com.poyka.ripdpi.diagnostics.dpi.NativeQuicInitialPacketBindings {
    public <init>();
    public com.poyka.ripdpi.diagnostics.dpi.QuicProbeOutcome validate(byte[],byte[],boolean);
}
-keepclassmembers,allowobfuscation,includedescriptorclasses enum com.poyka.ripdpi.diagnostics.dpi.QuicFingerprint {
    public static final com.poyka.ripdpi.diagnostics.dpi.QuicFingerprint CHROME_120;
    public static final com.poyka.ripdpi.diagnostics.dpi.QuicFingerprint GENERIC_V1;
}

-keepclassmembers,allowobfuscation,includedescriptorclasses class com.poyka.ripdpi.core.NativeWarpKeyPairGenerator {
    public <init>();
    public java.lang.Object generate(kotlin.coroutines.Continuation);
}
-keepclassmembers,allowobfuscation,includedescriptorclasses class com.poyka.ripdpi.core.WarpKeyPair {
    public java.lang.String getPrivateKey();
    public java.lang.String getPublicKey();
    public java.lang.String toString();
}
-keepclassmembers,allowobfuscation,includedescriptorclasses class com.poyka.ripdpi.core.RipDpiWarpNativeBindings {
    public <init>();
    public java.lang.String probeEndpoint(java.lang.String,com.poyka.ripdpi.core.WarpProbeSocketController);
}

-keepclassmembers,allowobfuscation,includedescriptorclasses class com.poyka.ripdpi.core.NativeGeoDatabaseValidator {
    public <init>();
    public void validate(java.lang.String,boolean);
}
