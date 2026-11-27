# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class io.lawallet.wallet.** { *** Companion; }
-keepclasseswithmembers class io.lawallet.wallet.** { kotlinx.serialization.KSerializer serializer(...); }
# secp256k1 JNI
-keep class fr.acinq.secp256k1.** { *; }
-dontwarn org.bouncycastle.**
# ML Kit discovers its components reflectively (MlKitInitProvider)
-keep class com.google.mlkit.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-dontwarn com.google.mlkit.**
# BDK (uniffi over JNA): JNA reflects over its own classes and the generated bindings
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.bitcoindevkit.** { *; }
# The Payjoin Dev Kit bindings: the same uniffi-over-JNA shape as BDK's.
-keep class org.payjoindevkit.** { *; }
-dontwarn java.awt.**
-dontwarn org.slf4j.**

# dnsjava (BIP-353 names): Windows resolver config (JNA), the JDK's name-service SPI and Lombok annotations are
# desktop-only paths the app never takes (it resolves over DoH through OkHttp).
-dontwarn com.sun.jna.platform.win32.**
-dontwarn lombok.**
-dontwarn sun.net.spi.nameservice.**
