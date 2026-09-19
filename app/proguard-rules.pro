# Gson serialises these by reflection, so their field names have to survive shrinking.
-keep class music.ai.recommend.model.Song { *; }
-keep class music.ai.recommend.model.Folder { *; }
-keep class music.ai.recommend.Playlist { *; }
-keep class music.ai.recommend.EqBand { *; }
-keep class music.ai.recommend.EqPreset { *; }
-keep class music.ai.recommend.ai.SmartAlbumBuilder$Stored { *; }
-keep class music.ai.recommend.ai.SmartAlbumBuilder$StoredAlbum { *; }

# Gson needs generic signatures and the TypeToken machinery intact.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ONNX Runtime bridges into native code by name.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Room's generated implementations are looked up reflectively.
-keep class music.ai.recommend.db.** { *; }
