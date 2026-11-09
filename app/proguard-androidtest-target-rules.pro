# The target shrinker cannot see calls from the separate AndroidTest APK.
-keepclassmembers,allowobfuscation,includedescriptorclasses class com.poyka.ripdpi.diagnostics.dpi.QuicH3FingerprintProbe {
    public <init>(com.poyka.ripdpi.diagnostics.dpi.QuicUdpProbe,com.poyka.ripdpi.diagnostics.dpi.QuicInitialPacketFactory,com.poyka.ripdpi.diagnostics.dpi.QuicProbeResponseValidator,int,int,int,kotlin.time.TimeSource,int,kotlin.jvm.internal.DefaultConstructorMarker);
    public java.lang.Object check(java.lang.String,kotlin.coroutines.Continuation);
}
-keepclassmembers,allowobfuscation,includedescriptorclasses class com.poyka.ripdpi.diagnostics.dpi.QuicProbeResult {
    public boolean getUdpReachable();
    public boolean getChromeOk();
    public boolean getFirefoxOk();
    public boolean getGenericOk();
    public boolean getVnOk();
    public com.poyka.ripdpi.diagnostics.dpi.QuicProbeVerdict getVerdict();
}
-keepclassmembers,allowobfuscation,includedescriptorclasses enum com.poyka.ripdpi.diagnostics.dpi.QuicProbeVerdict {
    public static final com.poyka.ripdpi.diagnostics.dpi.QuicProbeVerdict QUIC_DROPPED;
}
-keepclassmembers,allowobfuscation,includedescriptorclasses class com.poyka.ripdpi.diagnostics.dpi.DatagramSocketQuicUdpProbe {
    public <init>();
    public java.lang.Object sendInitial(java.lang.String,int,com.poyka.ripdpi.diagnostics.dpi.QuicFingerprint,byte[],int,kotlin.coroutines.Continuation);
}
-keepclassmembers,allowobfuscation,includedescriptorclasses class com.poyka.ripdpi.diagnostics.dpi.QuicProbeSample {
    public byte[] getResponse();
}
-keepclassmembers,allowobfuscation,includedescriptorclasses enum com.poyka.ripdpi.diagnostics.dpi.QuicFingerprint {
    public static final com.poyka.ripdpi.diagnostics.dpi.QuicFingerprint GENERIC_V1;
}
