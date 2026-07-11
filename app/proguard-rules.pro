# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# Moshi Rules to prevent JSON deserialization failures under minification
-keep class * {
    @com.squareup.moshi.Json class *;
    @com.squareup.moshi.JsonQualifier class *;
}
-keep class *JsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
    public <init>(com.squareup.moshi.Moshi, java.lang.reflect.Type[]);
}
-keepclassmembers class * {
    @com.squareup.moshi.Json *;
}
-keep class **_JsonAdapter { *; }

# Retrofit Rules
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**

# OkHttp Rules
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# Room Database Rules
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.limit.annotations.RestrictTo
-keep class * extends androidx.room.migration.Migration
-keep class * extends androidx.room.RoomDatabase$Callback

# Suppress R8/ProGuard warnings for missing classes that are not used at runtime
-dontwarn com.google.api.client.http.**
-dontwarn java.lang.management.**
-dontwarn org.joda.time.**
-dontwarn io.ktor.**

# Keep all classes and members in the application and core packages from being stripped/obfuscated
-keep class com.inscopelabs.abxmcp.** { *; }
-keep interface com.inscopelabs.abxmcp.** { *; }
-keep enum com.inscopelabs.abxmcp.** { *; }

