package com.cardface.studio

import android.content.Context

data class ReplaceResult(
    val backupId: String,
    val width: Int,
    val height: Int,
    val verifyNote: String,
    val warning: String?
)

object CardEngine {

    /**
     * 列出卡面文件。
     *
     * 卡面文件通常没有后缀名，因此不看扩展名，而是读文件头按真实格式判断。
     * 逐个文件只读 64 字节，代价很小；若一个都没识别出来（罕见格式），
     * 退回全部普通文件，交给解码阶段再判断。
     */
    fun listCards(dir: String): List<FileEntry> {
        val entries = FileOps.list(dir).filter { !it.isDir }
        if (entries.isEmpty()) return emptyList()

        val images = ArrayList<FileEntry>(entries.size)
        for (entry in entries) {
            val head = try {
                FileOps.readHead(FileOps.join(dir, entry.name), 64)
            } catch (_: Throwable) {
                ByteArray(0)
            }
            if (head.isNotEmpty() && ImageCodec.isImage(head)) images.add(entry)
        }
        return if (images.isEmpty()) entries else images
    }

    fun backupOne(context: Context, pkg: String, dir: String, fileName: String): BackupRecord {
        val path = FileOps.join(dir, fileName)
        val data = FileOps.readBytes(path)
        val stat = FileOps.stat(path)
        return BackupStore.create(context, pkg, path, fileName, data, stat)
    }

    /**
     * 替换流程：备份原图 → 按原图尺寸/格式生成新图 → 覆盖写入（保留文件名与 inode）→ 校验。
     * 校验不通过时自动回滚为原图。
     */
    fun replace(
        context: Context,
        pkg: String,
        dir: String,
        fileName: String,
        newImage: ByteArray
    ): ReplaceResult {
        val path = FileOps.join(dir, fileName)

        val statBefore = FileOps.stat(path)
        val original = FileOps.readBytes(path)
        if (original.isEmpty()) throw ShellException("原卡面文件为空，已中止替换")

        // 1) 先备份
        val backup = BackupStore.create(context, pkg, path, fileName, original, statBefore)

        // 2) 计算目标尺寸与格式
        val bounds = ImageCodec.bounds(original)
        val decoded = ImageCodec.decodeScaled(newImage, bounds?.first ?: 0, bounds?.second ?: 0)
            ?: throw ShellException("无法解码所选图片，请换一张试试")
        val (data, warning) = ImageCodec.buildReplacement(original, decoded)

        // 3) 写入
        FileOps.writeBytes(path, data, statBefore)

        // 4) 校验，失败则自动回滚
        val after = try {
            FileOps.readBytes(path)
        } catch (t: Throwable) {
            runCatching { FileOps.writeBytes(path, original, statBefore) }
            throw ShellException("写入后读取校验失败，已自动还原原卡面：${t.message}")
        }

        if (after.size != data.size) {
            runCatching { FileOps.writeBytes(path, original, statBefore) }
            throw ShellException("写入校验失败（期望 ${data.size} 字节，实际 ${after.size} 字节），已自动还原原卡面。")
        }

        val boundsAfter = ImageCodec.bounds(after)
        val sizeMatch = bounds != null && boundsAfter != null &&
            bounds.first == boundsAfter.first && bounds.second == boundsAfter.second
        val note = if (sizeMatch && bounds != null) {
            "校验通过：文件名、尺寸（${bounds.first}×${bounds.second}）与原卡面完全一致。"
        } else {
            "校验提示：写入 ${after.size} 字节，尺寸 ${boundsAfter?.let { "${it.first}×${it.second}" } ?: "无法解析"}。"
        }

        return ReplaceResult(
            backupId = backup.id,
            width = boundsAfter?.first ?: 0,
            height = boundsAfter?.second ?: 0,
            verifyNote = note,
            warning = warning
        )
    }

    /** 从备份还原单个卡面。 */
    fun restore(record: BackupRecord): String {
        val data = record.backupFile.readBytes()
        if (data.isEmpty()) throw ShellException("备份文件为空：${record.backupFile.absolutePath}")

        val stat = FileOps.stat(record.originalPath)
            ?: record.mode?.let { FileOps.Stat(it, record.uid ?: "0", record.gid ?: "0", data.size.toLong()) }

        FileOps.writeBytes(record.originalPath, data, stat)

        val after = FileOps.readBytes(record.originalPath)
        if (after.size != data.size) throw ShellException("还原后校验失败（期望 ${data.size} 字节，实际 ${after.size} 字节）")
        return "已还原 ${record.fileName}"
    }
}
