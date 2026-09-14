# Coder Mobile release rules. Keep this file intentionally small; R8 can optimize Compose and OkHttp.
-keepclassmembers class * implements android.os.Parcelable { public static ** CREATOR; }
-dontwarn okhttp3.**
