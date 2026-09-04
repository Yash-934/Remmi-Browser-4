# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# JNI Native method preservation
-keepclasseswithmembernames class * {
    native <methods>;
}

# Remmi Adblock Rust JNI Bridge
-keep class com.remmi.adblock.** { *; }
-dontwarn com.remmi.adblock.**

# Remmi Browser Classes
-keep class com.remmi.browser.** { *; }
-dontwarn com.remmi.browser.**

# Mozilla GeckoView & Gecko Engine
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-keep class org.mozilla._uniffi.** { *; }
-dontwarn org.mozilla.geckoview.**
-dontwarn org.mozilla.gecko.**
-dontwarn org.mozilla._uniffi.**
-keepclasseswithmembernames class org.mozilla.** {
    native <methods>;
}
-keepclassmembers class org.mozilla.gecko.** {
    public *;
    protected *;
}
-keepclassmembers class org.mozilla.geckoview.** {
    public *;
    protected *;
}

# Tor Android Service
-keep class org.torproject.** { *; }
-dontwarn org.torproject.**

# SQLCipher Database Encryption
-keep class net.zetetic.database.sqlcipher.** { *; }
-dontwarn net.zetetic.database.sqlcipher.**

# Common Android missing classes referenced by transitive dependencies (R8 fix)
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-dontwarn org.yaml.snakeyaml.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn sun.misc.Unsafe

# Room & SQLite
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Moshi / Serialization
-keepclassmembers class * {
    @com.squareup.moshi.* <fields>;
    @com.squareup.moshi.* <methods>;
}
-dontwarn com.squareup.moshi.**

# Bouncy Castle Cryptography (Argon2id, HMAC, AES-GCM)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# JSoup Optional RE2/J Dependency
-dontwarn com.google.re2j.**

