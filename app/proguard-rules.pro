# Shizuku
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# Shizuku UserService 由 Shizuku 进程通过反射实例化，必须保留
-keep class com.cardface.studio.RemoteFsService { *; }
-keep class com.cardface.studio.IRemoteFs { *; }
-keep class com.cardface.studio.IRemoteFs$Stub { *; }
