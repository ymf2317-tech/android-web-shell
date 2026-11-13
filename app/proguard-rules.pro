# 只保留我们自己的类，其余交给 R8
-keep class io.dsh.shell.** { *; }
-dontwarn android.webkit.**
