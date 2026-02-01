# Preserve Gomobile runtime and generated bindings
-keep class go.** { *; }
-keep class wlproxy.** { *; }

# Preserve application classes (especially those accessed via reflection or JNI if any)
-keep class com.wlturnapp.** { *; }

# General Android rules usually handled by default files, but adding common ones just in case
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
