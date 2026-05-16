# Numeri di riga negli stack trace
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.ridescope.**$$serializer { *; }
-keepclassmembers class com.example.ridescope.** { *** Companion; }
-keepclasseswithmembers class com.example.ridescope.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Garmin FIT SDK
-keep class com.garmin.fit.** { *; }

# BLE callbacks (classi anonime usate dal GATT)
-keep class * extends android.bluetooth.BluetoothGattCallback { *; }
-keep class * extends android.bluetooth.le.ScanCallback { *; }