# Keep the JNI entry point: the native library resolves it by exact name.
-keep class df.root.MainActivity { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Keep the exploit bridge classes reached from Kotlin/JNI.
-keep class df.root.Runner { *; }
-keep class df.root.RootProbe { *; }
-keep class df.root.RootRecord { *; }
-keep interface df.root.IReporter { *; }
