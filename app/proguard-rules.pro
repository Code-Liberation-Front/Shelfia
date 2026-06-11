# Keep serializable models (kotlinx.serialization generates serializers referenced reflectively in some paths)
-keepclassmembers class app.shelfie.data.** {
    *** Companion;
}
-keepclasseswithmembers class app.shelfie.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
