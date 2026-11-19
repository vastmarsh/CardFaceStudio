package com.cardface.studio

import java.util.Locale

data class FileEntry(val name: String, val isDir: Boolean)

class ShellException(message: String) : Exception(message)

object PathUtil {
    /** shell 单引号转义，防止路径中的空格/特殊字符引发注入。 */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun join(dir: String, name: String): String = dir.trimEnd('/') + "/" + name

    fun parent(path: String): String? {
        if (path == "/" || path.isBlank()) return null
        val trimmed = path.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        return when {
            index < 0 -> null
            index == 0 -> "/"
            else -> trimmed.substring(0, index)
        }
    }
}

/**
 * 对上层暴露的文件操作门面，具体能力由 [Privilege] 的当前通道决定。
 */
object FileOps {

    data class Stat(val mode: String, val uid: String, val gid: String, val size: Long)

    fun join(dir: String, name: String): String = PathUtil.join(dir, name)

    fun parent(path: String): String? = PathUtil.parent(path)

    fun list(path: String): List<FileEntry> =
        Privilege.list(path).sortedWith(compareBy({ !it.isDir }, { it.name.lowercase(Locale.ROOT) }))

    fun exists(path: String): Boolean = Privilege.exists(path)

    fun isDir(path: String): Boolean = Privilege.isDirectory(path)

    fun sizeOf(path: String): Long = Privilege.sizeOf(path)

    fun stat(path: String): Stat? = Privilege.stat(path)

    fun readBytes(path: String, maxBytes: Long = 28L * 1024 * 1024): ByteArray {
        val data = Privilege.readBytes(path)
        if (data.size.toLong() > maxBytes) throw ShellException("文件过大：${data.size} 字节")
        return data
    }

    fun readHead(path: String, length: Int): ByteArray = Privilege.readHead(path, length)

    fun writeBytes(path: String, data: ByteArray, stat: Stat?) {
        Privilege.writeBytes(path, data, stat)
    }

    fun deleteFile(path: String) {
        Privilege.deleteFile(path)
    }

    fun forceStop(pkg: String): Boolean = Privilege.forceStop(pkg)
}
