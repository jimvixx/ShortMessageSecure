-keepattributes *Annotation*,EnclosingMethod,Signature,Exceptions

-dontwarn android.test.**
-dontwarn com.android.support.test.**
-dontwarn org.assertj.**
-dontwarn org.hamcrest.**
-dontwarn org.mockito.**

-keepclassmembers class ** {
  public void onEvent*(**);
}

-keep @interface dagger.*,javax.inject.*
-keep @dagger.Module class *
-keepclassmembers class * {
  @javax.inject.* *;
  @dagger.* *;
  <init>();
}
-keepclasseswithmembernames class * {
  @javax.inject.* <fields>;
}
-keep class javax.inject.** { *; }
-keep class **$$ModuleAdapter
-keep class **$$InjectAdapter
-keep class **$$StaticInjection
-keep class dagger.** { *; }
-keep class * extends dagger.** { *; }
-keep interface dagger.** { *; }
-dontwarn dagger.internal.codegen.**

-keep public enum com.bumptech.glide.load.resource.bitmap.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

-keepnames class * implements android.os.Parcelable {
  public static final ** CREATOR;
}

-keepnames class com.fasterxml.jackson.** { *; }
-keepnames interface com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.**
-keep class org.codehaus.** { *; }

-dontwarn retrofit.**
-dontwarn okio.**

-keep class org.spongycastle.crypto.** { *; }
-keep class org.spongycastle.jcajce.** { *; }
-keep class org.spongycastle.jce.** { *; }

-keep class org.sqlite.** { *; }
-keep class org.sqlite.database.** { *; }

-dontwarn org.apache.http.**

-dontwarn com.squareup.okhttp.**

-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.ParametersAreNonnullByDefault
-dontwarn javax.naming.**
-dontwarn java.nio.file.**
-dontwarn uk.co.senab.photoview.**
