-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class io.github.kmmarin.tvbro.** { *** Companion; }
-keepclasseswithmembers class io.github.kmmarin.tvbro.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.github.kmmarin.tvbro.**$$serializer { *; }
-keep class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { *; }
# jcifs-ng (SMB recordings): reflection-free, but it references optional logging / JDK classes
-dontwarn jcifs.**
-dontwarn org.slf4j.**
-dontwarn javax.servlet.**
-keep class jcifs.** { *; }
# Release builds: no logcat output (stream URLs and provider names never reach the system log).
# Diagnostics stay available in Settings > About (in-memory Diag ring).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
