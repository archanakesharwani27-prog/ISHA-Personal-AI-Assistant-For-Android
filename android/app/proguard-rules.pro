# Flutter rules
-keep class io.flutter.** { *; }

# ONNX Runtime JNI reflection
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# OpenWakeWord rules
-keep class xyz.rementia.** { *; }
-dontwarn xyz.rementia.**

# App native classes and services
-keep class com.aura.assistant.** { *; }
