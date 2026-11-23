# Disable R8's cross-class optimization passes (method inlining, class
# merging, etc). They break ML Kit's reflection-based component DI:
# v31 crashed at process start with "Unsatisfied dependency for component"
# inside MlKitInitProvider, which got us a Play policy rejection. AGP 9
# removed support for proguard-android.txt and tells us to opt out this
# way instead. Shrinking + obfuscation still run as normal.
-dontoptimize

-keep class me.nettrash.scan.data.payload.** { *; }
-keep class me.nettrash.scan.data.db.** { *; }
-keepattributes *Annotation*

# ML Kit barcode scanning — keep the entire SDK surface, not just the
# barcode subpackage. MlKitInitProvider builds a component graph at process
# start using runtime reflection, and dependencies live in sibling packages
# (mlkit.common, mlkit.vision.common, odml). Stripping any of them causes
# "Unsatisfied dependency for component" at app launch, which is what got
# v31 rejected by Play's policy review.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.odml.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# Glance creates ActionCallbacks reflectively (Class.forName, then
# getDeclaredConstructor().newInstance()) when a widget action fires — the
# Recent-scans widget's Refresh (RecentScansRefreshCallback). Glance's own
# consumer rule keeps only the class under R8 full mode, not its no-arg
# constructor, so without this every Refresh tap in a release build threw
# NoSuchMethodException, swallowed as "BroadcastReceiver execution failed".
-keepclassmembers class * implements androidx.glance.appwidget.action.ActionCallback {
    <init>();
}
