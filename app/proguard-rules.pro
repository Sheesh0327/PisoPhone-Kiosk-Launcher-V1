# Add project specific ProGuard rules here.
# Control applied configuration files using the proguardFiles setting in build.gradle.

# Aggressive Obfuscation & Shrinking Settings
-repackageclasses ''
-allowaccessmodification
-dontusemixedcaseclassnames
-verbose

# Preserve Android Manifest entry points (Activities, Services, Receivers, Device Admin)
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.admin.DeviceAdminReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application

# Preserve Kiosk Device Admin Receiver specifically
-keep class com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver { *; }
-keep class com.pisophone.kiosk.receiver.BootReceiver { *; }
-keep class com.pisophone.kiosk.receiver.KioskWatchdogReceiver { *; }
-keep class com.pisophone.kiosk.receiver.KioskAdminActionReceiver { *; }

# Preserve Kiosk Services
-keep class com.pisophone.kiosk.KioskService { *; }

# Preserve Room database models and Dao implementations
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.pisophone.kiosk.db.** { *; }

# Preserve Data Models and DTOs
-keep class com.pisophone.kiosk.model.** { *; }

# NanoHTTPD reflection preservation
-keep class fi.iki.elonen.** { *; }
-keep interface fi.iki.elonen.** { *; }
-keep class com.pisophone.kiosk.server.** { *; }

# Preserve Cryptography & Security helper classes from member-level obfuscation if reflection is needed
-keepclassmembers class com.pisophone.kiosk.security.KioskSecurity {
    public static *** getSharedSecret(...);
    public static *** setSharedSecret(...);
}

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keepclassmembers class * {
    @org.json.* <fields>;
}

# AndroidX Security Crypto
-keep class androidx.security.crypto.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn com.google.errorprone.annotations.**

