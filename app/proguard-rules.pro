# The C++ core is bound by name (Java_com_arrowpuzzle_game_Native_*), so these must not be renamed or removed.
-keep class com.arrowpuzzle.game.Native { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Readable crash reports in Play Console.
-keepattributes SourceFile,LineNumberTable
