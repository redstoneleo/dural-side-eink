# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep LZ4 library
-keep class net.jpountz.** { *; }
-dontwarn net.jpountz.**
