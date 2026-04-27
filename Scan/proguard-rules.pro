-keep class me.nettrash.scan.data.payload.** { *; }
-keep class me.nettrash.scan.data.db.** { *; }
-keepattributes *Annotation*

# ML Kit barcode scanning models
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
