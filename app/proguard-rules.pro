# Anything the native library reaches by name must survive R8 untouched: JNI
# resolves methods from strings, so a renamed or inlined member fails only at
# runtime, with "NoSuchMethodError: no non-static method ...".
#
# Every entry here has a counterpart in native/src — keep the two in step when
# adding a call across the boundary.

# native fn declarations resolved against this class
-keepclasseswithmembernames class dev.emanuele.spot.nativecore.NativeBridge {
    native <methods>;
}

# called from the engine's event pump (native/src/engine.rs)
-keep class dev.emanuele.spot.nativecore.NativeEvents { *; }
-keep class * implements dev.emanuele.spot.nativecore.NativeEvents { *; }

# start / stop / write, called from the audio sink (native/src/sink.rs)
-keep class dev.emanuele.spot.playback.AudioOutput { *; }

# kotlinx.serialization generates serializers reflectively from these.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.emanuele.spot.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit keeps generic signatures on its interface methods.
-keepattributes Signature, RuntimeVisibleAnnotations
-keep,allowobfuscation interface dev.emanuele.spot.data.SpotifyApi

# Resolved by name from app/src/main/cpp/stretch.cpp, whose JNI symbols embed
# the fully qualified class name.
-keepclasseswithmembernames class dev.emanuele.spot.playback.Stretcher { native <methods>; }
-keep class dev.emanuele.spot.playback.Stretcher { *; }
