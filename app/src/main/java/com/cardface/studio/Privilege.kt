package com.cardface.studio

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

enum class PrivilegeMode { NONE, USER_SERVICE, SU }

/**
 * 特权访问入口。
 *
 * 首选 Shizuku UserService：把 [RemoteFsService] 跑在 Shizuku 服务进程里，
 * 该进程以 shell（uid 2000，ADB 启动）或 root（uid 0）身份运行。
 * 其次回退到设备自带的 su。
 */
object Privilege {

    private const val CHUNK_SIZE = 256 * 1024
    private const val MAX_FILE_BYTES = 28L * 1024 * 1024

    @Volatile
    var mode: PrivilegeMode = PrivilegeMode.NONE
        private set

    @Volatile
    var uid: Int = -1
        private set

    @Volatile
    private var service: IRemoteFs? = null

    @Volatile
    private var bound = false

    private val lock = java.lang.Object()

    private val args: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, RemoteFsService::class.java.name)
        )
            .daemon(false)
            .tag("cardface-remote-fs")
            .processNameSuffix("fs")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val remote = binder?.let { IRemoteFs.Stub.asInterface(it) }
            if (remote != null) {
                service = remote
                mode = PrivilegeMode.USER_SERVICE
                uid = try {
                    remote.uidValue()
                } catch (_: Throwable) {
                    -1
                }
            }
            wake()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            mode = PrivilegeMode.NONE
            wake()
        }
    }

    fun isReady(): Boolean = mode != PrivilegeMode.NONE

    fun describe(): String = when (mode) {
        PrivilegeMode.NONE -> "未连接"
        PrivilegeMode.USER_SERVICE ->
            if (uid == 0) "root（uid 0）· Shizuku UserService" else "shell（uid $uid）· Shizuku UserService"
        PrivilegeMode.SU -> "root（uid 0）· su"
    }

    /** 绑定用户服务；必须保证在主线程调用（内部会自动 post 到主线程）。 */
    fun ensureBound(context: Context) {
        if (service != null || bound) return
        if (!ShizukuBridge.binderAlive() || !ShizukuBridge.granted()) return

        val action = Runnable {
            if (!bound && service == null) {
                try {
                    Shizuku.bindUserService(args, connection)
                    bound = true
                } catch (_: Throwable) {
                    bound = false
                }
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            Handler(Looper.getMainLooper()).post(action)
        }
    }

    /** 探测可用通道，耗时操作，请在 IO 线程调用。 */
    fun refresh(context: Context): Boolean {
        ensureBound(context)

        if (awaitService(4000)) {
            mode = PrivilegeMode.USER_SERVICE
            uid = try {
                service?.uidValue() ?: -1
            } catch (_: Throwable) {
                -1
            }
            return true
        }

        if (probeSu()) {
            mode = PrivilegeMode.SU
            uid = 0
            return true
        }

        mode = PrivilegeMode.NONE
        uid = -1
        return false
    }

    private fun awaitService(timeoutMs: Long): Boolean {
        if (service != null) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (service == null) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                try {
                    lock.wait(remaining.coerceAtMost(300L))
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return service != null
    }

    private fun wake() {
        synchronized(lock) {
            lock.notifyAll()
        }
    }

    private fun probeSu(): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
        val output = String(process.inputStream.readBytes(), Charsets.UTF_8).trim()
        try {
            process.errorStream.close()
        } catch (_: Throwable) {
        }
        process.waitFor(3, TimeUnit.SECONDS) && output == "0"
    } catch (_: Throwable) {
        false
    }

    // ---------------- 目录 ----------------

    fun list(path: String): List<FileEntry> = when (mode) {
        PrivilegeMode.USER_SERVICE -> listViaService(path)
        PrivilegeMode.SU -> listViaSu(path)
        PrivilegeMode.NONE -> throw ShellException("未连接 Shizuku 或 root：请先完成授权")
    }

    private fun listViaService(path: String): List<FileEntry> {
        val remote = service ?: throw ShellException("Shizuku 用户服务未就绪，请稍后重试")
        val raw = try {
            remote.listDir(path)
        } catch (t: Throwable) {
            throw toShellError(t, "无法列出目录 $path")
        }
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split("\t")
                FileEntry(parts.getOrNull(0).orEmpty(), parts.getOrNull(1) == "D")
            }
            .toList()
    }

    private fun listViaSu(path: String): List<FileEntry> {
        val result = execSu("LC_ALL=C ls -1Ap -- ${PathUtil.quote(path)} 2>&1", timeoutMs = 30_000)
        if (result.code != 0) {
            throw ShellException("无法列出目录 $path：${String(result.out, Charsets.UTF_8).trim()}")
        }
        return String(result.out, Charsets.UTF_8).lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .map { if (it.endsWith("/")) FileEntry(it.dropLast(1), true) else FileEntry(it, false) }
            .filter { it.name != "." && it.name != ".." }
            .toList()
    }

    // ---------------- 读 ----------------

    fun readBytes(path: String): ByteArray = when (mode) {
        PrivilegeMode.USER_SERVICE -> readViaService(path)
        PrivilegeMode.SU -> readViaSu(path)
        PrivilegeMode.NONE -> throw ShellException("未连接 Shizuku 或 root：请先完成授权")
    }

    /** 只读文件头若干字节，用于无后缀名文件的格式嗅探。 */
    fun readHead(path: String, length: Int): ByteArray = when (mode) {
        PrivilegeMode.USER_SERVICE -> try {
            service?.readChunk(path, 0L, length) ?: ByteArray(0)
        } catch (_: Throwable) {
            ByteArray(0)
        }

        PrivilegeMode.SU ->
            execSu("head -c $length -- ${PathUtil.quote(path)} 2>/dev/null", timeoutMs = 20_000).out

        PrivilegeMode.NONE -> ByteArray(0)
    }

    private fun readViaService(path: String): ByteArray {
        val remote = service ?: throw ShellException("Shizuku 用户服务未就绪，请稍后重试")
        val size = try {
            remote.sizeOf(path)
        } catch (t: Throwable) {
            throw toShellError(t, "读取 $path")
        }
        if (size <= 0) return ByteArray(0)
        if (size > MAX_FILE_BYTES) throw ShellException("文件过大：$size 字节")

        val buffer = ByteArrayOutputStream(size.toInt())
        var offset = 0L
        while (offset < size) {
            val chunk = try {
                remote.readChunk(path, offset, CHUNK_SIZE)
            } catch (t: Throwable) {
                throw toShellError(t, "读取 $path")
            }
            if (chunk.isEmpty()) break
            buffer.write(chunk)
            offset += chunk.size
        }
        return buffer.toByteArray()
    }

    private fun readViaSu(path: String): ByteArray {
        val result = execSu("cat -- ${PathUtil.quote(path)} 2>/dev/null", timeoutMs = 120_000)
        if (result.code != 0) {
            throw ShellException("读取失败：$path（${result.err.trim().ifBlank { "exit ${result.code}" }}）")
        }
        if (result.out.size.toLong() > MAX_FILE_BYTES) throw ShellException("文件过大：${result.out.size} 字节")
        if (result.out.isEmpty() && sizeOf(path) > 0) throw ShellException("读取失败：$path（无权限或文件被占用）")
        return result.out
    }

    // ---------------- 写 ----------------

    fun writeBytes(path: String, data: ByteArray, stat: FileOps.Stat?) {
        when (mode) {
            PrivilegeMode.USER_SERVICE -> writeViaService(path, data)
            PrivilegeMode.SU -> writeViaSu(path, data)
            PrivilegeMode.NONE -> throw ShellException("未连接 Shizuku 或 root：请先完成授权")
        }
        applyStat(path, stat)
    }

    private fun writeViaService(path: String, data: ByteArray) {
        val remote = service ?: throw ShellException("Shizuku 用户服务未就绪，请稍后重试")

        if (data.isEmpty()) {
            try {
                remote.writeChunk(path, 0L, ByteArray(0), true)
            } catch (t: Throwable) {
                throw toShellError(t, "写入 $path")
            }
            return
        }

        var offset = 0
        var truncate = true
        while (offset < data.size) {
            val end = minOf(offset + CHUNK_SIZE, data.size)
            val part = data.copyOfRange(offset, end)
            try {
                remote.writeChunk(path, offset.toLong(), part, truncate)
            } catch (t: Throwable) {
                throw toShellError(t, "写入 $path")
            }
            truncate = false
            offset = end
        }
    }

    private fun writeViaSu(path: String, data: ByteArray) {
        val result = execSu("cat > ${PathUtil.quote(path)}", stdin = data, timeoutMs = 120_000)
        if (result.code != 0) {
            throw ShellException("写入失败：$path（${result.err.trim().ifBlank { "exit ${result.code}" }}）")
        }
    }

    private fun applyStat(path: String, stat: FileOps.Stat?) {
        if (stat == null) return
        when (mode) {
            PrivilegeMode.USER_SERVICE -> {
                val modeInt = stat.mode.toIntOrNull(8) ?: -1
                val uidInt = stat.uid.toIntOrNull() ?: -1
                val gidInt = stat.gid.toIntOrNull() ?: -1
                try {
                    service?.setStat(path, modeInt, uidInt, gidInt)
                } catch (_: Throwable) {
                }
                try {
                    service?.exec("restorecon ${PathUtil.quote(path)} 2>/dev/null")
                } catch (_: Throwable) {
                }
            }

            PrivilegeMode.SU -> execSu(
                buildString {
                    append("chown ${stat.uid}:${stat.gid} ${PathUtil.quote(path)} 2>/dev/null ; ")
                    append("chmod ${stat.mode} ${PathUtil.quote(path)} 2>/dev/null ; ")
                    append("restorecon ${PathUtil.quote(path)} 2>/dev/null ; true")
                },
                timeoutMs = 20_000
            )

            PrivilegeMode.NONE -> Unit
        }
    }

    // ---------------- 属性 ----------------

    fun stat(path: String): FileOps.Stat? = when (mode) {
        PrivilegeMode.USER_SERVICE -> {
            val remote = service
            if (remote == null) {
                null
            } else {
                try {
                    if (!remote.exists(path)) {
                        null
                    } else {
                        val parts = remote.statFile(path).split("|")
                        if (parts.size < 4) null else FileOps.Stat(
                            parts[0], parts[1], parts[2], parts[3].toLongOrNull() ?: -1L
                        )
                    }
                } catch (_: Throwable) {
                    null
                }
            }
        }

        PrivilegeMode.SU -> {
            val result = execSu("stat -c '%a|%u|%g|%s' -- ${PathUtil.quote(path)} 2>/dev/null", timeoutMs = 20_000)
            if (result.code != 0) {
                null
            } else {
                val parts = String(result.out, Charsets.UTF_8).trim().split("|")
                if (parts.size < 4) null else FileOps.Stat(
                    parts[0], parts[1], parts[2], parts[3].toLongOrNull() ?: -1L
                )
            }
        }

        PrivilegeMode.NONE -> null
    }

    fun exists(path: String): Boolean = when (mode) {
        PrivilegeMode.USER_SERVICE -> try {
            service?.exists(path) == true
        } catch (_: Throwable) {
            false
        }

        PrivilegeMode.SU -> execSu("test -e ${PathUtil.quote(path)}", timeoutMs = 15_000).code == 0
        PrivilegeMode.NONE -> false
    }

    fun isDirectory(path: String): Boolean = when (mode) {
        PrivilegeMode.USER_SERVICE -> try {
            service?.isDirectory(path) == true
        } catch (_: Throwable) {
            false
        }

        PrivilegeMode.SU -> execSu("test -d ${PathUtil.quote(path)}", timeoutMs = 15_000).code == 0
        PrivilegeMode.NONE -> false
    }

    fun sizeOf(path: String): Long = when (mode) {
        PrivilegeMode.USER_SERVICE -> try {
            service?.sizeOf(path) ?: -1L
        } catch (_: Throwable) {
            -1L
        }

        PrivilegeMode.SU -> stat(path)?.size ?: -1L
        PrivilegeMode.NONE -> -1L
    }

    fun deleteFile(path: String): Boolean = when (mode) {
        PrivilegeMode.USER_SERVICE -> try {
            service?.deleteFile(path) == true
        } catch (_: Throwable) {
            false
        }

        PrivilegeMode.SU -> execSu("rm -f -- ${PathUtil.quote(path)}", timeoutMs = 15_000).code == 0
        PrivilegeMode.NONE -> false
    }

    fun forceStop(pkg: String): Boolean = when (mode) {
        PrivilegeMode.USER_SERVICE -> try {
            service?.exec("am force-stop ${PathUtil.quote(pkg)} 2>&1") != null
        } catch (_: Throwable) {
            false
        }

        PrivilegeMode.SU -> execSu("am force-stop ${PathUtil.quote(pkg)}", timeoutMs = 30_000).code == 0
        PrivilegeMode.NONE -> false
    }

    // ---------------- su 通道 ----------------

    class SuResult(val code: Int, val out: ByteArray, val err: String)

    private fun execSu(command: String, stdin: ByteArray? = null, timeoutMs: Long = 120_000): SuResult {
        val process = try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        } catch (t: Throwable) {
            throw ShellException("无法执行 su：${t.message ?: t.javaClass.simpleName}")
        }

        val outBuffer = ByteArrayOutputStream()
        val errBuffer = ByteArrayOutputStream()

        val outReader = Thread {
            try {
                process.inputStream.use { it.copyTo(outBuffer) }
            } catch (_: Throwable) {
            }
        }.apply {
            isDaemon = true
            start()
        }
        val errReader = Thread {
            try {
                process.errorStream.use { it.copyTo(errBuffer) }
            } catch (_: Throwable) {
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            if (stdin != null) {
                process.outputStream.write(stdin)
                process.outputStream.flush()
            }
        } catch (_: Throwable) {
        } finally {
            try {
                process.outputStream.close()
            } catch (_: Throwable) {
            }
        }

        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!finished) {
            try {
                process.destroy()
            } catch (_: Throwable) {
            }
        }

        try {
            outReader.join(3000)
            errReader.join(3000)
        } catch (_: Throwable) {
        }

        val code = try {
            process.exitValue()
        } catch (_: Throwable) {
            -1
        }

        return SuResult(code, outBuffer.toByteArray(), String(errBuffer.toByteArray(), Charsets.UTF_8))
    }

    private fun toShellError(t: Throwable, what: String): ShellException {
        val message = t.message ?: t.javaClass.simpleName
        return ShellException("$what：$message")
    }
}
