package com.cardface.studio

import android.os.Process
import android.system.Os
import java.io.File
import java.io.RandomAccessFile

/**
 * 由 Shizuku 启动的 User Service。
 *
 * 该类在本应用进程之外、以 Shizuku 服务的身份（shell / root）运行，
 * 因此这里直接用 java.io / android.system 读写文件，即可获得与
 * adb shell 或 root 相同的文件访问能力。
 *
 * 注意：它不是普通的 Android Service，不需要在 Manifest 中注册。
 */
class RemoteFsService : IRemoteFs.Stub() {

    override fun uidValue(): Int = Process.myUid()

    override fun selinuxContext(): String = try {
        File("/proc/self/attr/current").readText().trim()
    } catch (_: Throwable) {
        ""
    }

    override fun listDir(path: String): String {
        val dir = File(path)
        if (!dir.exists()) throw IllegalArgumentException("路径不存在：$path")
        if (!dir.isDirectory) throw IllegalArgumentException("不是目录：$path")
        val files = dir.listFiles() ?: throw IllegalArgumentException("无法读取目录（权限不足）：$path")
        return files
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .joinToString("\n") { f ->
                val name = f.name.replace("\t", " ").replace("\n", " ")
                if (f.isDirectory) "$name\tD\t0" else "$name\tF\t${f.length()}"
            }
    }

    override fun statFile(path: String): String {
        val st = Os.stat(path)
        val permission = st.st_mode.toInt() and 0x1FF
        return "${Integer.toOctalString(permission)}|${st.st_uid}|${st.st_gid}|${st.st_size}"
    }

    override fun sizeOf(path: String): Long = File(path).length()

    override fun readChunk(path: String, offset: Long, length: Int): ByteArray {
        RandomAccessFile(path, "r").use { file ->
            val total = file.length()
            if (offset >= total || length <= 0) return ByteArray(0)
            file.seek(offset)
            val want = minOf(total - offset, length.toLong()).toInt()
            val buffer = ByteArray(want)
            var read = 0
            while (read < want) {
                val n = file.read(buffer, read, want - read)
                if (n <= 0) break
                read += n
            }
            return if (read == want) buffer else buffer.copyOf(read)
        }
    }

    override fun writeChunk(path: String, offset: Long, data: ByteArray, truncateFirst: Boolean): Boolean {
        RandomAccessFile(path, "rw").use { file ->
            if (truncateFirst) file.setLength(0)
            file.seek(offset)
            file.write(data)
        }
        return true
    }

    override fun setStat(path: String, mode: Int, uid: Int, gid: Int): Boolean {
        var chmodOk = true
        try {
            Os.chmod(path, mode)
        } catch (_: Throwable) {
            chmodOk = false
        }
        try {
            if (uid >= 0 && gid >= 0) Os.chown(path, uid, gid)
        } catch (_: Throwable) {
        }
        return chmodOk
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

    override fun deleteFile(path: String): Boolean = File(path).delete()

    override fun exec(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = String(process.inputStream.readBytes(), Charsets.UTF_8)
            try {
                process.errorStream.readBytes()
            } catch (_: Throwable) {
            }
            process.waitFor()
            output
        } catch (_: Throwable) {
            ""
        }
    }

    override fun destroy() {
    }
}
