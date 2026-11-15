# Add project specific ProGuard rules here.
-keep class com.dpad.messaging.models.** { *; }
-keep class com.dpad.messaging.databases.** { *; }
-keep class com.dpad.messaging.events.** { *; }

# EventBus
-keepattributes *Annotation*
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep generated serializers and serializable models
-keep class kotlinx.serialization.** { *; }
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class **$$serializer { *; }

# MMS transaction service + mmslib internals used reflectively
-keep class com.android.mms.transaction.TransactionService { *; }
-keep class com.klinker.android.send_message.** { *; }
-keep class com.google.android.mms.** { *; }
-keep class com.google.android.mms.pdu_alt.** { *; }
