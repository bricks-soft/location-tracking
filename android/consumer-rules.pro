# Consumer ProGuard/R8 rules for @bricks-soft/capacitor-location-tracking.

# The plugin compiles against both location SDKs (compileOnly); an app may package only one of them.
-dontwarn com.google.android.gms.**
-dontwarn com.huawei.**

# Provider bundles are instantiated reflectively by DefaultProviderFactory.
-keep class com.brickssoft.locationtracking.provider.**.*ProviderBundle {
    public <init>(android.content.Context);
}

# DefaultProviderFactory probes for the packaged SDKs by class name (Class.forName). Keep those names, or a
# minified release app would never detect Google Play services and fall back to the Android backend.
-keepnames class com.google.android.gms.location.LocationServices
-keepnames class com.google.android.gms.common.GoogleApiAvailability
-keepnames class com.huawei.hms.location.LocationServices

# Companion native API (docs/native-api.md): companion plugins and app code call these classes and interfaces. Keep
# every public member of the package, and the error type its callbacks deliver (TrackingException with an ErrorCode),
# so a companion library that is shrunk separately still finds them.
-keep class com.brickssoft.locationtracking.api.** { public *; }
-keep class com.brickssoft.locationtracking.core.TrackingException { public *; }
-keep class com.brickssoft.locationtracking.core.ErrorCode { public *; }

# Listener classes named in <meta-data android:name="com.brickssoft.locationtracking.LISTENER[.suffix]"> are created
# by reflection (Class.forName with the manifest name, then the public no-arg constructor). Keep the class names,
# that constructor and the listener methods of every implementation.
-keep class * implements com.brickssoft.locationtracking.api.LocationTrackingListener {
    public <init>();
    public void onRecord(android.content.Context, org.json.JSONObject);
    public void onEvent(android.content.Context, java.lang.String, org.json.JSONObject);
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
