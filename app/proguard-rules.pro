# youtubedl-android maps yt-dlp JSON with Jackson by reflection.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn org.apache.commons.**
-keep class org.apache.commons.compress.** { *; }

# Media3 loads some decoders/extractors by name.
-keep class androidx.media3.decoder.** { *; }
-keep class androidx.media3.effect.** { *; }
-dontwarn androidx.media3.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face** { *; }

# whisper JNI
-keep class com.anintellectualcompany.masterclip.engine.WhisperLib { *; }

# WorkManager (Room database + workers are created by reflection)
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
# Encrypted key storage (Tink)
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }
