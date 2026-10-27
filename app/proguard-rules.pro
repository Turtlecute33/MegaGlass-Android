# kotlinx.serialization: navigation keys are saved with their serializers.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Release logs: no exception text or file paths (MEGAGLASS-PRIV-004). nz.mega.sdk.AndroidGfxProcessor
# writes exceptions to System.out and System.err.
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}
-assumenosideeffects class java.io.PrintStream {
    public void println(java.lang.String);
}
# Owner rule: no verbose, debug or info logs in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
