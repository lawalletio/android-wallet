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
-dontwarn java.awt.**
-dontwarn org.slf4j.**
