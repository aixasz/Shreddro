# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.shreddro.**$$serializer { *; }
-keepclassmembers class com.shreddro.** { *** Companion; }
-keepclasseswithmembers class com.shreddro.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit
-keepattributes Signature, Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * { @retrofit2.http.* <methods>; }

# AppAuth
-keep class net.openid.appauth.** { *; }
