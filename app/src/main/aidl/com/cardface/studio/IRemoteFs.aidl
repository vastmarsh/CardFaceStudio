package com.cardface.studio;

// 运行在 Shizuku 服务进程（shell / root 身份）中的文件访问接口。
// 通过分块读写规避 Binder 1MB 事务限制。
interface IRemoteFs {
    int uidValue() = 1;
    String selinuxContext() = 2;
    String listDir(String path) = 3;
    String statFile(String path) = 4;
    long sizeOf(String path) = 5;
    byte[] readChunk(String path, long offset, int length) = 6;
    boolean writeChunk(String path, long offset, in byte[] data, boolean truncateFirst) = 7;
    boolean setStat(String path, int mode, int uid, int gid) = 8;
    boolean exists(String path) = 9;
    boolean isDirectory(String path) = 10;
    boolean deleteFile(String path) = 11;
    String exec(String command) = 12;

    // Shizuku 服务端约定的销毁方法
    void destroy() = 16777114;
}
