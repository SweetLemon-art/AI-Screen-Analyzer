# Keep data models used in JSON serialization / reflection
-keep class com.example.data.** { *; }
-keep class com.example.ai.** { *; }

# Keep OkHttp & Moshi rules if needed
-dontwarn okhttp3.**
-dontwarn okio.**
