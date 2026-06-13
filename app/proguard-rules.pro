# --- Bouncy Castle (Argon2id / HKDF) --------------------------------------------------
# Providers and algorithm classes are loaded reflectively.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- Tink (backs androidx.security EncryptedSharedPreferences) -------------------------
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# --- ML Kit text recognition (on-device OCR) ------------------------------------------
# The Play Services text-recognition libs ship their own consumer rules; these just
# silence warnings about optional transitive references under R8.
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_text_common.**

# --- kotlinx.serialization ------------------------------------------------------------
# Keep kotlinx.serialization generated serializers for vault model classes.
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep,includedescriptorclasses class app.docsafe.**$$serializer { *; }
-keepclassmembers class app.docsafe.** {
    *** Companion;
}
-keepclasseswithmembers class app.docsafe.** {
    kotlinx.serialization.KSerializer serializer(...);
}
