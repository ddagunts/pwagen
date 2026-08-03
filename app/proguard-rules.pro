# ARSCLib and apksig do reflective and ASN.1 work over their own model classes.
-keep class com.reandroid.** { *; }
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**
-dontwarn com.reandroid.**

# apksig references desktop-JVM-only crypto entry points that are absent on
# Android; they sit on code paths pwagen does not take.
-dontwarn java.awt.**
-dontwarn javax.naming.**

-keepclassmembers class dev.pwagen.config.** {
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class dev.pwagen.config.** {
    kotlinx.serialization.KSerializer serializer(...);
}
