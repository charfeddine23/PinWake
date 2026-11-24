# Pin Wake release obfuscation & shrinking rules

# Keep Retrofit service interfaces and Moshi @JsonClass DTOs
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class com.example.data.remote.dto.** { *; }

# Keep Room database & entities
-keep class * extends androidx.room.RoomDatabase
-keep class com.example.data.local.entity.** { *; }

# Strip debug-only logs in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
