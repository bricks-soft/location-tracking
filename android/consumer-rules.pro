# Consumer ProGuard/R8 rules for @bricks-soft/capacitor-location-tracking.

# The plugin compiles against both location SDKs (compileOnly); an app may package only one of them.
-dontwarn com.google.android.gms.**
-dontwarn com.huawei.**

# Provider bundles are instantiated reflectively by DefaultProviderFactory.
-keep class com.brickssoft.locationtracking.provider.**.*ProviderBundle {
    public <init>(android.content.Context);
}

# Huawei HMS recommended keeps.
-keep class com.huawei.hms.** { *; }
-keep class com.huawei.hianalytics.** { *; }
-keep class com.huawei.updatesdk.** { *; }
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
