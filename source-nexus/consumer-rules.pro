# Consumer ProGuard rules for :source-nexus

# kotlinx-serialization: keep generated serializers of the Nexus DTOs.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.modhub.nexus.**$$serializer { *; }
-keepclassmembers class com.modhub.nexus.** {
    *** Companion;
}
-keepclasseswithmembers class com.modhub.nexus.** {
    kotlinx.serialization.KSerializer serializer(...);
}
