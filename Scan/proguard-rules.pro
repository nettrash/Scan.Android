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
