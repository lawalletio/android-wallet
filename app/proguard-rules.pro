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
