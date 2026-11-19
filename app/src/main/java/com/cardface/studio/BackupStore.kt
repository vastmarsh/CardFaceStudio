package com.cardface.studio

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Crypto {
    fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
}

data class BackupRecord(
    val id: String,
    val pkg: String,
    val originalPath: String,
    val fileName: String,
    val backupFile: File,
    val size: Long,
    val sha256: String,
    val createdAt: Long,
    val mode: String?,
    val uid: String?,
    val gid: String?
) {
    fun timeText(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(createdAt))
}

/**
 * 备份保存位置：/sdcard/Android/data/com.cardface.studio/files/backups/<pkg>/<备份ID>/
 * 每条备份包含原图文件 + meta.json（原路径、权限、哈希等）。
 */
object BackupStore {

    fun rootDir(context: Context): File {
        val external = context.getExternalFilesDir(null)
        val base = external ?: context.filesDir
        return File(base, "backups")
    }

    private fun safeName(raw: String): String = raw.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("")

    private fun stamp(ts: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(ts))

    fun create(
        context: Context,
        pkg: String,
        originalPath: String,
        fileName: String,
        data: ByteArray,
        stat: FileOps.Stat?
    ): BackupRecord {
        val now = System.currentTimeMillis()
        val id = "${stamp(now)}-${(1000..9999).random()}"
        val pkgDir = File(rootDir(context), safeName(pkg)).apply { mkdirs() }
        val recordDir = File(pkgDir, id).apply { mkdirs() }

        val target = File(recordDir, fileName)
        target.writeBytes(data)

        val meta = JSONObject().apply {
            put("id", id)
            put("pkg", pkg)
            put("originalPath", originalPath)
            put("fileName", fileName)
            put("size", data.size.toLong())
            put("sha256", Crypto.sha256(data))
            put("createdAt", now)
            if (stat != null) {
                put("mode", stat.mode)
                put("uid", stat.uid)
                put("gid", stat.gid)
            }
        }
        File(recordDir, "meta.json").writeText(meta.toString())

        return BackupRecord(
            id = id,
            pkg = pkg,
            originalPath = originalPath,
            fileName = fileName,
            backupFile = target,
            size = data.size.toLong(),
            sha256 = Crypto.sha256(data),
            createdAt = now,
            mode = stat?.mode,
            uid = stat?.uid,
            gid = stat?.gid
        )
    }

    fun list(context: Context, pkgFilter: String? = null): List<BackupRecord> {
        val result = ArrayList<BackupRecord>()
        val pkgDirs = rootDir(context).listFiles()?.toList().orEmpty()
        for (pkgDir in pkgDirs) {
            if (pkgDir.isFile) continue
            val dirs = pkgDir.listFiles()?.toList().orEmpty()
            for (recordDir in dirs) {
                if (recordDir.isFile) continue
                val metaFile = File(recordDir, "meta.json")
                if (!metaFile.isFile) continue
                try {
                    val json = JSONObject(metaFile.readText())
                    val pkg = json.optString("pkg")
                    if (pkgFilter != null && pkg != pkgFilter) continue
                    val fileName = json.getString("fileName")
                    val file = File(recordDir, fileName)
                    if (!file.isFile) continue
                    result.add(
                        BackupRecord(
                            id = json.optString("id", recordDir.name),
                            pkg = pkg,
                            originalPath = json.optString("originalPath"),
                            fileName = fileName,
                            backupFile = file,
                            size = file.length(),
                            sha256 = json.optString("sha256"),
                            createdAt = json.optLong("createdAt", recordDir.lastModified()),
                            mode = json.optString("mode").takeIf { it.isNotBlank() },
                            uid = json.optString("uid").takeIf { it.isNotBlank() },
                            gid = json.optString("gid").takeIf { it.isNotBlank() }
                        )
                    )
                } catch (_: Throwable) {
                }
            }
        }
        return result.sortedByDescending { it.createdAt }
    }

    fun delete(record: BackupRecord): Boolean {
        val dir = record.backupFile.parentFile ?: return false
        return try {
            dir.deleteRecursively()
        } catch (_: Throwable) {
            false
        }
    }
}
