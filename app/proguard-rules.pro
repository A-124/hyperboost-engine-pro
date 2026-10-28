# R8 rules for HyperBoost Engine Pro.
#
# Shrinking is enabled for release builds. These rules exist because two things in the app are
# reached by name at runtime rather than through a direct reference, which R8 cannot see.

# --- Shizuku ---------------------------------------------------------------------------------
# ShizukuShellExecutor invokes Shizuku.newProcess(...) reflectively, because the method is not part
# of the published API surface. Stripping or renaming it breaks every privileged command.
-keep class rikka.shizuku.** { *; }
-keepclassmembers class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# --- App components --------------------------------------------------------------------------
# Services, the accessibility service and the quick-settings tile are declared in the manifest and
# instantiated by the system.
-keep class com.hyperboost.engine.services.** { *; }
-keep class com.hyperboost.engine.MainActivity { *; }
-keep class com.hyperboost.engine.HyperBoostApp { *; }

# --- Stack traces ----------------------------------------------------------------------------
# Keep line numbers so a user-submitted crash report stays actionable, but rename the source file so
# the obfuscation is not undone by the file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Third-party -----------------------------------------------------------------------------
# Unity Ads and Firebase ship their own consumer rules. These only silence missing-class warnings
# for optional integrations that are referenced but not present in every build.
-dontwarn com.unity3d.**
-dontwarn com.google.firebase.**
