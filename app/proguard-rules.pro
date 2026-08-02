# R8 rules for the release build.
#
# Most dependencies ship their own consumer rules and need nothing here: Room,
# Hilt/Dagger, Media3, Coil, Compose and kotlinx-coroutines all bundle what they
# require. What follows covers the parts that do NOT, plus the places where this
# app's own types are reached reflectively rather than by a traceable call.

# ---------------------------------------------------------------------------
# eAlvaTag -- the one genuinely dangerous dependency under R8.
#
# NOTE THE PACKAGE. It is top-level `ealvatag`, NOT `com.ealva.ealvatag`, despite
# the artifact coordinate being com.ealva:ealvatag. Verified against the built
# APK: 623 classes in ealvatag.tag.id3 alone. A `com.ealva.ealvatag.**` rule --
# the obvious guess -- matches nothing and silently protects nothing.
#
# It resolves tag frames by reflecting over frame-id/field mappings, which R8
# cannot trace. The failure mode is SILENT rather than loud: EAlvaTagReader
# catches Throwable and falls back to MediaStore, so a stripped frame class does
# not crash the app -- every file quietly degrades to MediaStore-derived
# metadata, losing album-artist, disc number, embedded art and lyrics. That is
# far worse than a crash, because nothing surfaces it. Keep the library whole.
-keep class ealvatag.** { *; }
-dontwarn ealvatag.**
# Frame-id and language resource bundles are loaded by name, so the resource
# entries must survive resource shrinking too.
-keepdirectories ealvatag/**

# eAlvaTag's logging facade, a separate artifact from the tag library itself.
-keep class com.ealva.ealvalog.** { *; }
-dontwarn com.ealva.ealvalog.**

# Desktop-JVM APIs eAlvaTag references but never reaches on Android. Without
# these, R8 fails the build on unresolved references rather than warning.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**

# ---------------------------------------------------------------------------
# This app's own reflectively-reached types.
#
# Room reconstructs entities and query projections. Room's own rules cover
# @Entity and @Dao, but IndexEntry is a plain projection returned by a @Query.
-keep class com.kaislate.veldtplayer.data.library.db.** { *; }
-keep class com.kaislate.veldtplayer.data.playlist.db.** { *; }

# Enum constants resolved by name where a value is persisted (DataStore
# preferences, saved state) and read back with valueOf.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions

# Readable stack traces from a test build, while still renaming the file itself.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
