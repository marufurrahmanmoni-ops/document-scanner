# OpenCV Java bindings call into JNI by name
-keep class org.opencv.** { *; }

# SQLCipher JNI
-keep class net.zetetic.database.** { *; }

# Tink uses protobuf-lite reflection for key templates
-keep class com.google.crypto.tink.proto.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Navigation type-safe routes are serialized by name
-keepclassmembers @kotlinx.serialization.Serializable class com.docscanner.app.** { *; }
