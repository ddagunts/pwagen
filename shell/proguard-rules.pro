# The generated APK's manifest names this class by its fully-qualified name so
# that classes.dex stays byte-identical across every generated package. R8 must
# not rename or remove it.
-keep class dev.pwagen.shell.MainActivity { *; }

# kotlinx.serialization generated serializers for the shared config schema.
-keepclassmembers class dev.pwagen.config.** {
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class dev.pwagen.config.** {
    kotlinx.serialization.KSerializer serializer(...);
}
